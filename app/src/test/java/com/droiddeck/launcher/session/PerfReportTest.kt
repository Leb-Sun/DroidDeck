package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.CapProbe
import com.droiddeck.launcher.core.LogRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PerfReportTest {
    private val kinds = PerfCounters.STOP_KINDS

    /** Counters that grow with [scale]: openat most, then the exec family and a few SIGSYS. */
    private fun stops(scale: Long): ProotStops {
        val k = LongArray(kinds.size)
        k[kinds.indexOf("seccomp")] = 1000 * scale
        k[kinds.indexOf("sysexit")] = 100 * scale
        k[kinds.indexOf("sigsys")] = 5 * scale
        k[kinds.indexOf("exec")] = 10 * scale
        return ProotStops(k.sum(), 20_000_000 * scale, k, LongArray(kinds.size) { k[it] * 20_000 }, linkedMapOf(
            "openat" to SyscallStops(900 * scale, 0, 0, 18_000_000 * scale),
            "execve" to SyscallStops(10 * scale, 10 * scale, 0, 5_000_000 * scale),
            "set_robust_list" to SyscallStops(0, 0, 5 * scale, 100_000 * scale),
        ))
    }

    private fun fastPath(scale: Long) = mapOf(
        "steam" to FamilyCounts(longArrayOf(90 * scale, 0, 0, 0, 0, 0, 0), longArrayOf(10 * scale, 0, 0, 0, 0, 0, 0)),
        "python3" to FamilyCounts(longArrayOf(0, 50 * scale, 0, 0, 0, 0, 0), longArrayOf(0, 50 * scale, 0, 0, 0, 0, 0)),
    )

    private fun at(label: String, seconds: Long, scale: Long, appId: Long? = null) = PerfSnapshot(
        label, appId, seconds * 1000, 1_760_000_000_000 + seconds * 1000,
        tracerTicks = 50 * scale, treeTicks = 200 * scale, treeProcesses = 40, stops = stops(scale), fastPath = fastPath(scale),
    )

    private val session = listOf(
        PerfSnapshot(PerfReport.CREATED, null, 0, 1_760_000_000_000, 0, 0, 0, null, emptyMap()),
        at(PerfReport.PROOT, 1, 0),
        at(PerfReport.FIRST_FRAME, 12, 10),
        at(PerfReport.READY, 20, 20),
        at(PerfReport.BIG_PICTURE, 28, 25),
        at(PerfReport.LAUNCH, 90, 30, appId = 1145360),
        at(PerfReport.FOCUS, 130, 50, appId = 1145360),
        at(PerfReport.EXIT, 250, 60, appId = 1145360),
        at(PerfReport.END, 260, 61),
    )

    private val info = PerfInfo(
        generated = "2026-10-12 14:03 UTC",
        build = mapOf("label" to "remote clean · Environment-work at abc1234", "commit" to "abc1234", "package" to "com.droiddeck.env"),
        device = mapOf("model" to "AYN Odin", "soc" to "Snapdragon 8 Gen 2 (SM8550)", "android" to "14", "sdk" to 34,
            "kernel" to "5.15.123-android13-8-g0123456789ab", "pageSize" to 4096L, "cpus" to 8),
        toggles = linkedMapOf("fastPathActive" to true, "prootFastPath" to true, "prootNoSeccomp" to false, "sync" to "fsync"),
        ticksPerSecond = 100,
    )

    @Test fun phasesFollowTheMilestones() {
        assertEquals(listOf("startup", "ready_to_big_picture", "idle_big_picture", "launch_to_focus", "in_game"),
            PerfReport.phases(session).map { it.name })
        // No game: idle in Big Picture runs to the end.
        val idle = session.filter { it.label !in setOf(PerfReport.LAUNCH, PerfReport.FOCUS, PerfReport.EXIT) }
        val phases = PerfReport.phases(idle)
        assertEquals(listOf("startup", "ready_to_big_picture", "idle_big_picture"), phases.map { it.name })
        assertEquals(PerfReport.END, phases.last().to.label)
    }

    @Test fun phaseDeltasCountFromTheirOwnStart() {
        val json = PerfReport.build(info, session)
        assertEquals(PerfReport.SCHEMA, json.getString("schema"))
        val times = json.getJSONObject("times")
        assertEquals(20.0, times.getDouble("readyS"), 0.0)
        assertEquals(28.0, times.getDouble("bigPictureS"), 0.0)
        assertEquals(1145360L, times.getJSONArray("games").getJSONObject(0).getLong("appId"))
        assertEquals(40.0, times.getJSONArray("games").getJSONObject(0).getDouble("launchToFocusS"), 0.0)

        val phases = json.getJSONArray("phases")
        val startup = phases.getJSONObject(0)
        // Created has no counters of its own: startup counts from zero.
        assertEquals(stops(20).stops, startup.getLong("stops"))
        assertEquals(stops(20).stops / 20.0, startup.getDouble("stopsPerSecond"), 0.1)
        // 1000 ticks of tracer CPU at 100 a second, over 20 s: half a core.
        assertEquals(10.0, startup.getDouble("tracerCpuS"), 0.0)
        assertEquals(50.0, startup.getDouble("tracerPct"), 0.0)
        assertEquals(200.0, startup.getDouble("treePct"), 0.0)
        assertEquals(200L, startup.getJSONObject("kinds").getLong("exec"))

        val launch = phases.getJSONObject(3)
        assertEquals("launch_to_focus", launch.getString("name"))
        assertEquals(1145360L, launch.getLong("appId"))
        assertEquals(stops(50).stops - stops(30).stops, launch.getLong("stops"))
        val top = launch.getJSONArray("topSyscalls")
        assertEquals("openat", top.getJSONObject(0).getString("name"))
        assertEquals(900L * 20, top.getJSONObject(0).getLong("stops"))
        assertEquals(5L * 20, launch.getJSONObject("kinds").getLong("sigsys"))
        val steam = launch.getJSONObject("fastPath").getJSONObject("byGroup").getJSONObject("steam")
        assertEquals(90.0, steam.getDouble("hitPct"), 0.0)
        assertEquals(1800L, steam.getLong("hits"))

        val session = json.getJSONObject("session")
        assertEquals(stops(61).stops, session.getJSONObject("stops").getLong("stops"))
        assertEquals(5L * 61, session.getJSONObject("syscalls").getJSONObject("set_robust_list").getLong("emulated"))
        assertEquals(50.0, session.getJSONObject("fastPath").getJSONObject("byGroup").getJSONObject("python").getDouble("hitPct"), 0.0)
    }

    @Test fun unreadCountersAreLeftOutNotZeroed() {
        val missing = session.map {
            if (it.label != PerfReport.READY) it
            else PerfSnapshot(it.label, null, it.atMs, it.wallMs, null, null, null, null, emptyMap())
        }
        val startup = PerfReport.build(info, missing).getJSONArray("phases").getJSONObject(0)
        assertTrue(startup.isNull("stops"))
        assertTrue(startup.isNull("tracerPct"))
        assertFalse(startup.has("fastPath"))
    }

    @Test fun theSummaryIsShortAndSurvivesSharing() {
        val text = PerfReport.summary(info, session)
        val lines = text.trimEnd().lines()
        assertTrue("${lines.size} lines", lines.size <= 60)
        assertTrue(text, text.contains("READY 20.0 s · Big Picture 28.0 s · launch to focus 40.0 s (app 1145360)"))
        assertTrue(text, lines.any { it.startsWith("startup") && it.contains("50.0") })
        assertTrue(text, lines.any { it.startsWith("in game") })
        assertTrue(text, lines.any { it.trim().startsWith("openat") })
        assertTrue(text, text.contains("steam 90.0% of"))
        // Share logs scrubs every line: nothing here may look like an address or a secret.
        lines.forEach { assertEquals(it, LogRedactor.redactForShare(it)) }
    }

    @Test fun theCapabilityProbeIsPartOfTheDevice() {
        val caps = CapProbe.fromReport(
            "capprobe 1 ok\nopenat2 allowed ret=3 errno=0 step=1\nclone3 blocked step=1\nsetresuid allowed ret=0 errno=0 step=1\n",
            mapOf("sdk" to 34), 18,
        )
        val withCaps = PerfInfo(info.generated, info.build, info.device, info.toggles, info.ticksPerSecond, caps)
        val device = PerfReport.build(withCaps, session).getJSONObject("device")
        assertEquals("AYN Odin", device.getString("model"))
        assertEquals("f", device.getJSONObject("caps").getString("nativeBlock"))
        assertEquals("blocked", device.getJSONObject("caps").getJSONObject("tests").getJSONObject("clone3").getString("state"))
        val lines = PerfReport.summary(withCaps, session).lines()
        val at = lines.indexOfFirst { it.startsWith("caps     seccomp: openat2 ok, clone3 blocked") }
        assertTrue(lines.joinToString("\n"), at > 0 && lines[at - 1].startsWith("device") && lines[at + 2].startsWith("toggles"))
        lines.forEach { assertEquals(it, LogRedactor.redactForShare(it)) }
        // Without a probe the report is as it was.
        assertFalse(PerfReport.build(info, session).getJSONObject("device").has("caps"))
        assertFalse(PerfReport.summary(info, session).contains("caps "))
    }

    @Test fun aSessionWithoutCountersStillSaysWhatItKnows() {
        val bare = listOf(
            PerfSnapshot(PerfReport.CREATED, null, 0, 0, 0, 0, 0, null, emptyMap()),
            PerfSnapshot(PerfReport.READY, null, 15_000, 15_000, null, null, null, null, emptyMap()),
        )
        val text = PerfReport.summary(info, bare)
        assertTrue(text, text.contains("READY 15.0 s"))
        assertTrue(text, text.contains("proot stop counters: not read"))
        assertTrue(text, text.contains("fast path counters: none"))
    }
}
