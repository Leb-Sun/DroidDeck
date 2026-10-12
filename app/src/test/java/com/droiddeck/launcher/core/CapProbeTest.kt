package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CapProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    /**
     * hostsys/capprobe.c's report on an x86_64 host under tools/native/launcher/seccomp-launch
     * --profile android15 --allow set_robust_list (tools/tests/test_native_capprobe.py): a kernel
     * without uclamp, so that test fails with EOPNOTSUPP.
     */
    private val android15 = """
        capprobe 1 ok
        openat2 blocked step=1
        resolve_in_root blocked step=1
        faccessat2 blocked step=1
        fchmodat2 blocked step=1
        clone3 allowed ret=-1 errno=22 step=1
        close_range allowed ret=0 errno=0 step=1
        landlock allowed ret=7 errno=0 step=1
        uclamp allowed ret=-1 errno=95 step=1
        fp_stub allowed ret=1 errno=0 step=3
        execmem allowed ret=0 errno=0 step=1
        exec_data allowed ret=0 errno=0 step=1
        userfaultfd allowed ret=-1 errno=22 step=1
        memfd_create allowed ret=-1 errno=14 step=1
        setresuid allowed ret=0 errno=0 step=1
        rseq blocked step=1
        set_robust_list allowed ret=-1 errno=22 step=1
        get_robust_list blocked step=1
        futex_waitv blocked step=1
        io_uring_setup blocked step=1
        setuid blocked step=1
    """.trimIndent()

    /** The same report as another profile would make it: [blocked] trap, the gated rest pass. */
    private fun profile(vararg blocked: String): String = android15.lines().joinToString("\n") { line ->
        val name = line.substringBefore(' ')
        when {
            name in blocked -> "$name blocked step=1"
            name in setOf("openat2", "faccessat2", "fchmodat2") -> "$name allowed ret=${if (name == "openat2") 3 else 0} errno=0 step=1"
            name == "resolve_in_root" -> "$name allowed ret=1 errno=0 step=2"
            else -> line
        }
    }

    private val android14 get() = profile("openat2", "resolve_in_root", "faccessat2", "fchmodat2", "clone3")
    private val android16q get() = profile()
    /** Android 12: close_range too (02 S3). */
    private val android12 get() = profile("openat2", "resolve_in_root", "faccessat2", "fchmodat2", "clone3", "close_range")

    private val device = mapOf("android" to "15", "sdk" to 35, "fingerprint" to "brand/product/device:15/AP3A/1:user/release-keys", "versionCode" to 12)

    private fun caps(report: String?) = CapProbe.fromReport(report, device, 21)

    @Test fun theReportIsReadLineByLine() {
        val (status, results) = CapProbe.parse(android15)
        assertEquals(CapProbe.OK, status)
        assertEquals(20, results.size)
        assertEquals(CapResult("openat2", CapProbe.BLOCKED, step = 1), results[0])
        assertEquals(CapResult("clone3", CapProbe.ALLOWED, -1, 22, 1), results.first { it.name == "clone3" })
        assertEquals(CapResult("fp_stub", CapProbe.ALLOWED, 1, 0, 3), results.first { it.name == "fp_stub" })
        val errored = CapProbe.parse("capprobe 1 timeout\nsetresuid allowed ret=0 errno=0 step=1\nhang error unfinished step=1\n" +
            "uclamp error signal=11 step=2\nclone3 error not-run\nnonsense\n")
        assertEquals("timeout", errored.first)
        assertEquals(listOf("setresuid", "hang", "uclamp", "clone3"), errored.second.map { it.name })
        assertEquals(11, errored.second[2].signal)
        assertEquals("unreadable report", CapProbe.parse("").first)
        assertEquals("failed errno=11", CapProbe.parse("capprobe 1 failed errno=11\n").first)
    }

    @Test fun theMaskHasTheSpikesBitsPlusCloseRange() {
        assertEquals(mapOf("clone3" to 0x1, "faccessat2" to 0x2, "fchmodat2" to 0x4, "close_range" to 0x8), CapProbe.BLOCK_BITS)
        assertEquals(0x7, caps(android14).nativeBlock)
        assertEquals(0x6, caps(android15).nativeBlock)
        assertEquals(0x0, caps(android16q).nativeBlock)
        assertEquals(0xf, caps(android12).nativeBlock)
        assertEquals("BL_NATIVE_BLOCK=6", CapProbe.guestEnv(caps(android15))[0])
        assertEquals("BL_NATIVE_BLOCK=0", CapProbe.guestEnv(caps(android16q))[0])
        assertEquals("BL_NATIVE_BLOCK=f", CapProbe.guestEnv(caps(android12))[0])
    }

    @Test fun onlyAPlainAllowedClearsABit() {
        // An error, or a test the report lacks, is never read as safe to issue.
        val clone3Errored = android16q.replace("clone3 allowed ret=-1 errno=22 step=1", "clone3 error signal=11 step=1")
        assertEquals(0x1, caps(clone3Errored).nativeBlock)
        val noFaccessat2 = android16q.lines().filterNot { it.startsWith("faccessat2 ") }.joinToString("\n")
        assertEquals(0x2, caps(noFaccessat2).nativeBlock)
        // The kernel lacking it is not the filter trapping it: glibc falls back on ENOSYS by itself.
        val oldKernel = android16q.replace("fchmodat2 allowed ret=0 errno=0", "fchmodat2 allowed ret=-1 errno=38")
        assertEquals(0x0, caps(oldKernel).nativeBlock)
        assertFalse("fchmodat2" in caps(oldKernel).extras)
    }

    @Test fun aProbeThatCouldNotTellLeavesTheGuestAlone() {
        for (failed in listOf(
            caps(null),
            caps("capprobe 1 timeout\nsetresuid allowed ret=0 errno=0 step=1\n"),
            caps("capprobe 1 failed signal=9\n"),
            // setresuid is allowed everywhere: a probe that says otherwise is not reading the filter.
            caps(android15.replace("setresuid allowed ret=0 errno=0 step=1", "setresuid blocked step=1")),
        )) {
            assertFalse(failed.status, failed.ok)
            assertNull(failed.nativeBlock)
            assertEquals(emptyList<String>(), CapProbe.guestEnv(failed))
            assertTrue(CapProbe.summary(failed).single(), CapProbe.summary(failed).single().endsWith("BL_NATIVE_BLOCK unset"))
        }
        assertEquals("untrusted: setresuid not allowed",
            caps(android15.replace("setresuid allowed ret=0 errno=0 step=1", "setresuid blocked step=1")).status)
    }

    @Test fun blCapsListsTheUsableExtrasOnly() {
        assertEquals(listOf("clone3", "close_range", "landlock", "fp_stub"), caps(android15).extras)
        assertEquals("BL_CAPS=clone3,close_range,landlock,fp_stub", CapProbe.guestEnv(caps(android15))[1])
        assertEquals(listOf("openat2", "resolve_in_root", "faccessat2", "fchmodat2", "clone3", "close_range", "landlock", "fp_stub"),
            caps(android16q).extras)
        // The controls every Android allows are usable, and stay out of BL_CAPS.
        val controls = caps(android15).results.filter { it.name !in CapProbe.EXTRAS && CapProbe.usable(it) }.map { it.name }
        assertEquals(listOf("execmem", "exec_data", "userfaultfd", "memfd_create", "setresuid", "set_robust_list"), controls)
        assertTrue(CapProbe.toJson(caps(android15)).getJSONObject("tests").getJSONObject("setresuid").getBoolean("usable"))
        // Allowed is not usable: uclamp refused by the kernel, a symlink that left the root, a stub that answered wrong.
        val usable = { line: String -> CapProbe.usable(CapProbe.parse("capprobe 1 ok\n$line").second.single()) }
        assertFalse(usable("uclamp allowed ret=-1 errno=95 step=1"))
        assertFalse(usable("uclamp allowed ret=0 errno=0 step=2"))
        assertTrue(usable("uclamp allowed ret=512 errno=0 step=2"))
        assertFalse(usable("resolve_in_root allowed ret=0 errno=0 step=2"))
        assertFalse(usable("fp_stub allowed ret=-1 errno=17 step=1"))
        assertTrue(usable("fchmodat2 allowed ret=-1 errno=95 step=1"))
        assertTrue(usable("close_range allowed ret=-1 errno=22 step=1"))
        assertFalse(usable("landlock allowed ret=-1 errno=95 step=1"))
        assertFalse(usable("clone3 allowed ret=-1 errno=38 step=1"))
        assertFalse(usable("exec_data error errno=2 step=0"))
    }

    @Test fun theSummaryNamesEachCall() {
        val lines = CapProbe.summary(caps(android15))
        assertEquals(2, lines.size)
        assertEquals("seccomp: openat2 blocked, faccessat2 blocked, fchmodat2 blocked, clone3 ok, close_range ok, landlock ok; " +
            "uclamp EOPNOTSUPP · BL_NATIVE_BLOCK=6 · probed in 21 ms", lines[0])
        assertEquals("fp_stub ok, execmem ok, exec_data ok, userfaultfd ok, memfd_create ok, setresuid ok, set_robust_list ok" +
            " · blocked: resolve_in_root, rseq, get_robust_list, futex_waitv, io_uring_setup, setuid", lines[1])
        // Share logs scrubs every line: nothing here may look like an address or a secret.
        lines.forEach { assertEquals(it, LogRedactor.redactForShare(it)) }
    }

    @Test fun theJsonSaysWhatEachTestFound() {
        val json = CapProbe.toJson(caps(android15))
        assertEquals(CapProbe.SCHEMA, json.getString("schema"))
        assertEquals("ok", json.getString("probe"))
        assertEquals("6", json.getString("nativeBlock"))
        assertEquals(35, json.getInt("sdk"))
        assertEquals("clone3", json.getJSONArray("extras").getString(0))
        val tests = json.getJSONObject("tests")
        assertEquals("blocked", tests.getJSONObject("openat2").getString("state"))
        assertFalse(tests.getJSONObject("openat2").has("ret"))
        assertEquals("EINVAL", tests.getJSONObject("clone3").getString("errno"))
        assertTrue(tests.getJSONObject("clone3").getBoolean("usable"))
        assertEquals("EOPNOTSUPP", tests.getJSONObject("uclamp").getString("errno"))
        assertFalse(tests.getJSONObject("uclamp").getBoolean("usable"))
        assertTrue(CapProbe.toJson(caps(null)).isNull("nativeBlock"))
    }

    @Test fun theCacheHoldsForThisBootInstallAndSystemOnly() {
        val file = temporary.newFile("caps.json")
        val fingerprint = device["fingerprint"] as String
        val installed = 1_760_000_000_000L
        CapProbe.write(file, caps(android15), "boot-1", installed)
        val back = CapProbe.read(file, "boot-1", fingerprint, 12, installed)!!
        assertTrue(back.cached)
        assertEquals(CapProbe.OK, back.status)
        assertEquals(caps(android15).results, back.results)
        assertEquals(0x6, back.nativeBlock)
        assertEquals(21L, back.probeMs)
        assertEquals("15", back.device["android"])
        assertTrue(CapProbe.summary(back)[0], CapProbe.summary(back)[0].endsWith("· cached"))
        assertNull(CapProbe.read(file, "boot-2", fingerprint, 12, installed))
        assertNull(CapProbe.read(file, "boot-1", "brand/product/device:16/BP2A/1:user/release-keys", 12, installed))
        assertNull(CapProbe.read(file, "boot-1", fingerprint, 13, installed))
        // A test build reinstalled in the same boot has the same version code, and probes again.
        assertNull(CapProbe.read(file, "boot-1", fingerprint, 12, installed + 60_000))
        // No boot id or install time to key on: nothing is reused.
        assertNull(CapProbe.read(file, null, fingerprint, 12, installed))
        assertNull(CapProbe.read(file, "boot-1", fingerprint, 12, null))
        // A failure is kept too, with its reason.
        CapProbe.write(file, caps("capprobe 1 timeout\n"), "boot-1", installed)
        assertEquals("timeout", CapProbe.read(file, "boot-1", fingerprint, 12, installed)!!.status)
    }
}
