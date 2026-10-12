package com.droiddeck.launcher.session

import android.content.Context
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeGlibcTest {
    @get:Rule val temp = TemporaryFolder()

    private val version = "2.43+r22+g8362e8ce10b2-2"
    private lateinit var root: File

    /** A temp rootfs's glibc: three ELF stand-ins, one of them (a gconv module) the same in the rebuild. */
    private val stock = linkedMapOf(
        "usr/lib/libc.so.6" to "stock libc".toByteArray(),
        "usr/lib/ld-linux-aarch64.so.1" to "stock ld.so".toByteArray(),
        "usr/lib/gconv/IBM1390.so" to "stock gconv".toByteArray(),
    )
    private val rebuild = mapOf(
        "usr/lib/libc.so.6" to "rebuild libc".toByteArray(),
        "usr/lib/ld-linux-aarch64.so.1" to "rebuild ld.so".toByteArray(),
        "usr/lib/gconv/IBM1390.so" to "stock gconv".toByteArray(),
    )
    private val patched = mapOf(
        "usr/lib/libc.so.6" to "patched libc".toByteArray(),
        "usr/lib/ld-linux-aarch64.so.1" to "patched ld.so".toByteArray(),
        "usr/lib/gconv/IBM1390.so" to "patched gconv".toByteArray(),
    )

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun manifest(v: String = version, patchedFiles: Map<String, ByteArray> = patched): String = buildString {
        append("droiddeck-glibc-manifest 1\nversion $v\ncommit 8362e8ce10b24068bacc19552c128dd10e082fd9\n")
        append("assume __ASSUME_FACCESSAT2=0 __ASSUME_FCHMODAT2=0 __ASSUME_SET_ROBUST_LIST=1 kernel=3.7.0\n")
        append("patches 0001-bl-native-block-gate.patch\n")
        for ((path, bytes) in stock) {
            append("file 755 ${bytes.size} ${sha(bytes)} ${sha(rebuild.getValue(path))} ${sha(patchedFiles.getValue(path))} $path\n")
        }
    }

    /** A directory standing in for the apk's assets/linuxfs/glibc. */
    private class DirOverlay(val dir: File) : NativeGlibc.Overlay {
        override fun manifest(version: String): String? = File(dir, "$version/manifest").takeIf { it.isFile }?.readText()
        override fun open(version: String, flavor: String, path: String): InputStream = File(dir, "$version/$flavor/$path").inputStream()
    }

    private fun overlay(v: String = version, patchedFiles: Map<String, ByteArray> = patched): DirOverlay {
        val dir = temp.newFolder()
        File(dir, "$v/manifest").apply { parentFile!!.mkdirs() }.writeText(manifest(v, patchedFiles))
        for ((flavor, files) in listOf("rebuild" to rebuild, "patched" to patchedFiles)) {
            for ((path, bytes) in files) File(dir, "$v/$flavor/$path").apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        }
        return DirOverlay(dir)
    }

    private fun pacmanEntry(name: String, v: String) {
        File(root, "var/lib/pacman/local/$name-$v/desc").apply { parentFile!!.mkdirs() }
            .writeText("%NAME%\n$name\n\n%VERSION%\n$v\n\n%BASE%\nglibc\n")
    }

    private fun file(path: String) = File(root, path)
    private fun kept(path: String) = File(root, path + NativeGlibc.STOCK_SUFFIX)
    private fun state() = File(root, NativeGlibc.STATE).readText().trim()

    @Before
    fun setUp() {
        root = temp.newFolder("rootfs")
        pacmanEntry("glibc", version)
        pacmanEntry("glibc-locales", version)
        for ((path, bytes) in stock) {
            file(path).apply { parentFile!!.mkdirs(); writeBytes(bytes); setExecutable(true, false); setLastModified(1_700_000_000_000L) }
        }
    }

    @After
    fun tearDown() {
        SessionState.logDirectory = null
        SessionState.eventsFile = null
    }

    @Test
    fun stockAndMatchingStagesTheFlavorAndKeepsTheStockFiles() {
        val result = NativeGlibc.apply(root, overlay(), on = true, flavor = NativeGlibc.PATCHED)
        assertEquals("patched $version", result.line)
        assertEquals(3, result.staged)
        assertEquals("patched $version", state())
        for ((path, bytes) in patched) {
            assertArrayEquals(path, bytes, file(path).readBytes())
            assertArrayEquals(path, stock.getValue(path), kept(path).readBytes())
            assertTrue(path, file(path).canExecute())
        }
        assertTrue(File(root, NativeGlibc.LEDGER).isFile)
    }

    @Test
    fun theRebuildLeavesAFileItSharesWithStockAlone() {
        val result = NativeGlibc.apply(root, overlay(), on = true, flavor = NativeGlibc.REBUILD)
        assertEquals("rebuild $version", result.line)
        assertEquals(2, result.staged)
        assertFalse(kept("usr/lib/gconv/IBM1390.so").exists())
        assertArrayEquals(rebuild.getValue("usr/lib/libc.so.6"), file("usr/lib/libc.so.6").readBytes())
    }

    @Test
    fun offPutsTheStockFilesBack() {
        val overlay = overlay()
        NativeGlibc.apply(root, overlay, on = true, flavor = NativeGlibc.PATCHED)
        val result = NativeGlibc.apply(root, overlay, on = false, flavor = NativeGlibc.PATCHED)
        assertEquals("stock $version", result.line)
        assertEquals(3, result.restored)
        assertEquals("stock $version", state())
        for ((path, bytes) in stock) {
            assertArrayEquals(path, bytes, file(path).readBytes())
            assertFalse(path, kept(path).exists())
        }
        assertFalse(File(root, NativeGlibc.LEDGER).exists())
    }

    @Test
    fun onOffOnLeavesTheStockFilesByteIdentical() {
        val overlay = overlay()
        val before = stock.keys.associateWith { Triple(file(it).readBytes(), file(it).canExecute(), file(it).lastModified()) }
        for (round in 1..2) {
            assertEquals("patched $version", NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED).line)
            assertEquals("stock $version", NativeGlibc.apply(root, overlay, false, NativeGlibc.PATCHED).line)
            for ((path, was) in before) {
                assertArrayEquals("$path round $round", was.first, file(path).readBytes())
                assertEquals("$path round $round", was.second, file(path).canExecute())
                assertEquals("$path round $round", was.third, file(path).lastModified())
            }
        }
        assertEquals("patched $version", NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED).line)
        for ((path, bytes) in stock) assertArrayEquals(path, bytes, kept(path).readBytes())
    }

    @Test
    fun aFileThatIsNeitherStockNorOursStagesNothing() {
        file("usr/lib/libc.so.6").writeBytes("someone else's libc".toByteArray())
        val result = NativeGlibc.apply(root, overlay(), on = true, flavor = NativeGlibc.PATCHED)
        assertEquals("mismatch $version", result.line)
        assertEquals(0, result.staged)
        assertArrayEquals(stock.getValue("usr/lib/ld-linux-aarch64.so.1"), file("usr/lib/ld-linux-aarch64.so.1").readBytes())
        for (path in stock.keys) assertFalse(path, kept(path).exists())
        assertFalse(File(root, NativeGlibc.LEDGER).exists())
    }

    @Test
    fun aMismatchAfterStagingPutsBackWhatItCanAndLosesNothing() {
        val overlay = overlay()
        NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED)
        file("usr/lib/libc.so.6").writeBytes("someone else's libc".toByteArray())
        assertEquals("mismatch $version", NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED).line)
        // The rest are stock again; the foreign libc keeps its stock copy and the ledger stays.
        assertArrayEquals(stock.getValue("usr/lib/ld-linux-aarch64.so.1"), file("usr/lib/ld-linux-aarch64.so.1").readBytes())
        assertArrayEquals(stock.getValue("usr/lib/libc.so.6"), kept("usr/lib/libc.so.6").readBytes())
        assertTrue(File(root, NativeGlibc.LEDGER).isFile)
        assertEquals("mismatch $version", NativeGlibc.apply(root, overlay, false, NativeGlibc.PATCHED).line)
        assertTrue(kept("usr/lib/libc.so.6").isFile)
    }

    @Test
    fun aDamagedStockCopyIsNeverPutBack() {
        val overlay = overlay()
        NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED)
        kept("usr/lib/libc.so.6").writeBytes("damaged".toByteArray())
        assertEquals("mismatch $version", NativeGlibc.apply(root, overlay, false, NativeGlibc.PATCHED).line)
        // libc stays the build it is (ABI-identical to stock) rather than becoming a damaged copy.
        assertArrayEquals(patched.getValue("usr/lib/libc.so.6"), file("usr/lib/libc.so.6").readBytes())
        assertArrayEquals(stock.getValue("usr/lib/ld-linux-aarch64.so.1"), file("usr/lib/ld-linux-aarch64.so.1").readBytes())
        assertTrue(File(root, NativeGlibc.LEDGER).isFile)
    }

    @Test
    fun aBuildOfOursWhoseCopyIsGoneStaysOnRecord() {
        val overlay = overlay()
        NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED)
        kept("usr/lib/libc.so.6").delete()
        assertEquals("mismatch $version", NativeGlibc.apply(root, overlay, false, NativeGlibc.PATCHED).line)
        assertTrue(File(root, NativeGlibc.LEDGER).isFile)
        assertEquals("mismatch $version", NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED).line)
        // pacman -S glibc puts the package's file back; then nothing of ours is left.
        file("usr/lib/libc.so.6").writeBytes(stock.getValue("usr/lib/libc.so.6"))
        assertEquals("stock $version", NativeGlibc.apply(root, overlay, false, NativeGlibc.PATCHED).line)
        assertFalse(File(root, NativeGlibc.LEDGER).exists())
    }

    @Test
    fun aPacmanUpgradeDropsTheOldCopiesAndTouchesNothingElse() {
        NativeGlibc.apply(root, overlay(), true, NativeGlibc.PATCHED)
        // pacman -Syu: the new package's files over ours, and its database entry.
        val newer = "2.44+r50+g1848099f063e-1"
        File(root, "var/lib/pacman/local/glibc-$version").deleteRecursively()
        pacmanEntry("glibc", newer)
        for (path in stock.keys) file(path).writeBytes("2.44 $path".toByteArray())
        val result = NativeGlibc.apply(root, overlay(), true, NativeGlibc.PATCHED)
        assertEquals("mismatch $newer", result.line)
        for (path in stock.keys) {
            assertEquals("2.44 $path", file(path).readText())
            assertFalse(path, kept(path).exists())
        }
        assertFalse(File(root, NativeGlibc.LEDGER).exists())
    }

    @Test
    fun noBuildForTheRootfsVersionStagesNothing() {
        val result = NativeGlibc.apply(root, overlay(v = "2.42+r51+gcbf39c26b258-2"), true, NativeGlibc.PATCHED)
        assertEquals("mismatch $version", result.line)
        for ((path, bytes) in stock) assertArrayEquals(bytes, file(path).readBytes())
    }

    @Test
    fun switchingFlavorsKeepsTheStockCopies() {
        val overlay = overlay()
        NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED)
        val result = NativeGlibc.apply(root, overlay, true, NativeGlibc.REBUILD)
        assertEquals("rebuild $version", result.line)
        assertArrayEquals(rebuild.getValue("usr/lib/libc.so.6"), file("usr/lib/libc.so.6").readBytes())
        assertArrayEquals(stock.getValue("usr/lib/libc.so.6"), kept("usr/lib/libc.so.6").readBytes())
        assertEquals("stock $version", NativeGlibc.apply(root, overlay, false, NativeGlibc.REBUILD).line)
        for ((path, bytes) in stock) assertArrayEquals(path, bytes, file(path).readBytes())
    }

    @Test
    fun anotherApksBuildIsSwappedThroughStock() {
        NativeGlibc.apply(root, overlay(), true, NativeGlibc.PATCHED)
        val newPatched = patched.mapValues { (path, _) -> "patched v2 $path".toByteArray() }
        val result = NativeGlibc.apply(root, overlay(patchedFiles = newPatched), true, NativeGlibc.PATCHED)
        assertEquals("patched $version", result.line)
        assertEquals(3, result.restored)
        for ((path, bytes) in newPatched) assertArrayEquals(path, bytes, file(path).readBytes())
        for ((path, bytes) in stock) assertArrayEquals(path, bytes, kept(path).readBytes())
    }

    @Test
    fun steadyStateChangesNothingAndCrashLeftoversGo() {
        val overlay = overlay()
        NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED)
        File(root, "usr/lib/libc.so.6.droiddeck-new").writeText("half a file")
        val result = NativeGlibc.apply(root, overlay, true, NativeGlibc.PATCHED)
        assertEquals("patched $version", result.line)
        assertEquals(0, result.staged)
        assertFalse(File(root, "usr/lib/libc.so.6.droiddeck-new").exists())
    }

    @Test
    fun offWithNothingStagedOnlyWritesTheState() {
        val result = NativeGlibc.apply(root, overlay(), false, NativeGlibc.PATCHED)
        assertEquals("stock $version", result.line)
        assertEquals("stock $version", state())
        for ((path, bytes) in stock) assertArrayEquals(bytes, file(path).readBytes())
    }

    @Test
    fun theVersionComesFromTheGlibcEntryAlone() {
        assertEquals(version, NativeGlibc.installedVersion(root))
        pacmanEntry("glibc", "2.44+r50+g1848099f063e-1")
        assertNull(NativeGlibc.installedVersion(root))
    }

    @Test
    fun manifestsWithUnsafeOrMalformedLinesAreRefused() {
        val good = manifest()
        assertNotNull(NativeGlibc.Manifest.parse(good))
        val line = good.lines().first { it.startsWith("file ") }
        for (bad in listOf(
            line.replace("usr/lib/libc.so.6", "../etc/passwd"),
            line.replace("usr/lib/libc.so.6", "/usr/lib/libc.so.6"),
            line.replace("usr/lib/libc.so.6", "usr//lib/libc.so.6"),
            line.replace(" 755 ", " 999 "),
            line.substringBeforeLast(' '),
            line.replaceFirst(Regex(" [0-9a-f]{64} "), " xyz "),
        )) {
            assertNull(bad, NativeGlibc.Manifest.parse(good.replace(line, bad)))
        }
        assertNull(NativeGlibc.Manifest.parse(good + line + "\n"))
        assertNull(NativeGlibc.Manifest.parse(good.replaceFirst("droiddeck-glibc-manifest 1", "droiddeck-glibc-manifest 2")))
    }

    @Test
    fun twoFailedStartsRevertToStockAndTurnTheToggleOff() {
        val context = RuntimeEnvironment.getApplication() as Context
        val logs = temp.newFolder("logs")
        SessionState.logDirectory = logs
        SessionState.eventsFile = File(logs, "events.jsonl")
        SessionPrefs.setPatchedGlibc(context, true)
        val overlay = overlay()

        NativeGlibc.stage(context, root, overlay)
        assertEquals("patched $version", state())
        NativeGlibc.event("session.ready")
        // A start that reaches READY clears the count; one that fails adds to it.
        NativeGlibc.stage(context, root, overlay)
        NativeGlibc.event("session.failed")
        assertEquals(1, NativeGlibc.failedStarts(context))
        // A stop before READY that is not a failure counts neither way.
        NativeGlibc.stage(context, root, overlay)
        NativeGlibc.event("session.stopped")
        assertEquals(1, NativeGlibc.failedStarts(context))
        // The app dies before READY: the next start counts it, and that is the second.
        NativeGlibc.stage(context, root, overlay)
        assertTrue(SessionPrefs.patchedGlibc(context))
        NativeGlibc.stage(context, root, overlay)
        assertFalse(SessionPrefs.patchedGlibc(context))
        assertEquals(0, NativeGlibc.failedStarts(context))
        assertEquals("stock $version", state())
        for ((path, bytes) in stock) assertArrayEquals(path, bytes, file(path).readBytes())
        val events = File(logs, "events.jsonl").readLines()
        assertTrue(events.any { "\"event\":\"glibc.reverted\"" in it && "\"failedStarts\":2" in it })
        assertTrue(events.last().contains("\"event\":\"glibc.state\"") && events.last().contains("\"state\":\"stock\""))
    }
}
