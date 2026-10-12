package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PerfCountersTest {
    /** proot's stop counter file as patch 0015 lays it out (tools/proot/PATCHES.md). */
    private fun stopsFile(rows: List<Pair<String, LongArray>>, kinds: LongArray, magic: String = "PRSTATS\u0000", version: Int = 1): ByteArray {
        val header = 328
        val nameSize = 32
        val b = ByteBuffer.allocate(header + rows.size * (32 + nameSize)).order(ByteOrder.LITTLE_ENDIAN)
        b.put(magic.toByteArray(Charsets.US_ASCII))
        b.putInt(8, version).putInt(12, header).putInt(16, 12).putInt(20, rows.size).putInt(24, nameSize)
        b.putLong(32, 4242).putLong(56, kinds.sum()).putLong(64, 9_000_000)
        kinds.forEachIndexed { i, n -> b.putLong(72 + 8 * i, n).putLong(200 + 8 * i, n * 1000) }
        rows.forEachIndexed { i, (name, row) ->
            row.forEachIndexed { j, n -> b.putLong(header + 32 * i + 8 * j, n) }
            val at = header + 32 * rows.size + nameSize * i
            name.toByteArray().forEachIndexed { j, c -> b.put(at + j, c) }
        }
        return b.array()
    }

    private fun fastPathFile(name: String, pid: Int, hits: LongArray, misses: LongArray, magic: String = "FPSTATS\u0000"): ByteArray {
        val b = ByteBuffer.allocate(56 + 16 * hits.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(magic.toByteArray(Charsets.US_ASCII))
        b.putInt(8, 1).putInt(12, hits.size).putInt(16, pid)
        name.toByteArray().forEachIndexed { j, c -> b.put(24 + j, c) }
        (hits + misses).forEachIndexed { i, n -> b.putLong(56 + 8 * i, n) }
        return b.array()
    }

    @Test fun prootsStopCountersAreReadByKindAndSyscall() {
        val kinds = longArrayOf(500, 3, 120, 7, 9, 2, 1, 1, 4, 4, 2, 4)
        val stops = PerfCounters.parseStops(stopsFile(listOf(
            "void" to longArrayOf(0, 0, 0, 0),
            "openat" to longArrayOf(300, 0, 0, 7_000_000),
            "set_robust_list" to longArrayOf(0, 0, 7, 140_000),
            "execve" to longArrayOf(4, 4, 0, 2_000_000),
        ), kinds))!!
        assertEquals(kinds.sum(), stops.stops)
        assertEquals(9_000_000L, stops.ns)
        assertEquals(4L, stops.kind("exec"))
        assertEquals(7L, stops.kind("sigsys"))
        assertEquals(120_000L, stops.kindNs[PerfCounters.STOP_KINDS.indexOf("sysexit")])
        // Rows nothing stopped on are left out.
        assertEquals(listOf("openat", "set_robust_list", "execve"), stops.syscalls.keys.toList())
        assertEquals(SyscallStops(0, 0, 7, 140_000), stops.syscalls["set_robust_list"])
        assertEquals(8L, stops.syscalls.getValue("execve").stops)
        // The tracer's own pid is in the header, and its first bytes are enough for it.
        assertEquals(4242, stops.pid)
        val file = stopsFile(emptyList(), kinds)
        assertEquals(4242, PerfCounters.statsPid(file.copyOf(PerfCounters.STATS_PID_BYTES)))
        assertNull(PerfCounters.statsPid(stopsFile(emptyList(), kinds, magic = "\u0000".repeat(8)).copyOf(40)))
        assertNull(PerfCounters.statsPid(file.copyOf(32)))
    }

    @Test fun everyTracersCounterFileHasARoleByItsName() {
        assertEquals("session", PerfCounters.tracerRole("proot.stats"))
        assertEquals("game", PerfCounters.tracerRole("proot-game-1.stats"))
        assertEquals("net", PerfCounters.tracerRole("proot-net-12345.stats"))
        assertNull(PerfCounters.tracerRole("fastpath"))
        assertNull(PerfCounters.tracerRole("proot.stats.tmp"))
        assertNull(PerfCounters.tracerRole("proot-.stats"))
    }

    @Test fun procStatusGivesUidParentTracerAndMemory() {
        val status = "Name:\tproot\nUmask:\t0077\nState:\tS (sleeping)\nTgid:\t4321\nPid:\t4321\nPPid:\t4000\n" +
            "TracerPid:\t3999\nUid:\t10234\t10234\t10234\t10234\nGid:\t10234\t10234\t10234\t10234\n" +
            "VmPeak:\t  900000 kB\nVmRSS:\t  123456 kB\nThreads:\t3\n"
        assertEquals(ProcStatus(uid = 10234, ppid = 4000, tracerPid = 3999, rssKb = 123456), PerfCounters.parseStatus(status))
        // A kernel thread has no VmRSS.
        assertEquals(0L, PerfCounters.parseStatus("PPid:\t2\nTracerPid:\t0\nUid:\t0\t0\t0\t0\n")!!.rssKb)
        assertNull(PerfCounters.parseStatus("Name:\tx\n"))
    }

    @Test fun appProcessesAreThePackageAndItsNamedProcesses() {
        fun cmdline(vararg args: String) = args.joinToString("\u0000", postfix = "\u0000").toByteArray()
        assertTrue(PerfCounters.isAppProcess(cmdline("com.droiddeck.env"), "com.droiddeck.env"))
        assertTrue(PerfCounters.isAppProcess(cmdline("com.droiddeck.env:game"), "com.droiddeck.env"))
        assertFalse(PerfCounters.isAppProcess(cmdline("com.droiddeck.envx"), "com.droiddeck.env"))
        assertFalse(PerfCounters.isAppProcess(cmdline("/data/app/x/lib/arm64/libproot.so", "com.droiddeck.env"), "com.droiddeck.env"))
        assertFalse(PerfCounters.isAppProcess(ByteArray(0), "com.droiddeck.env"))
    }

    @Test fun glibcStateIsStateThenPackageVersion() {
        assertEquals(mapOf("state" to "stock"), PerfCounters.glibcState(null))
        assertEquals(mapOf("state" to "stock"), PerfCounters.glibcState("  \n"))
        assertEquals(mapOf("state" to "patched", "pkgver" to "2.43+r5+g1234-1"), PerfCounters.glibcState("patched 2.43+r5+g1234-1\n"))
        assertEquals(mapOf("state" to "mismatch", "pkgver" to null), PerfCounters.glibcState("mismatch"))
    }

    @Test fun batteryPowerIsReadInTheUnitsOemsUse() {
        // Documented units: µA, mV; discharging is negative.
        assertEquals(4000.0, PerfCounters.powerMw(-1_000_000, 4000)!!, 1e-9)
        // The same draw in mA, and with the sign the other way round.
        assertEquals(4000.0, PerfCounters.powerMw(1000, 4000)!!, 1e-9)
        assertEquals("mA", PerfCounters.currentUnit(1000))
        assertEquals("uA", PerfCounters.currentUnit(-1_000_000))
        // Voltage in µV or in whole volts.
        assertEquals(4000.0, PerfCounters.powerMw(-1_000_000, 4_000_000)!!, 1e-9)
        assertEquals(4000.0, PerfCounters.powerMw(-1_000_000, 4)!!, 1e-9)
        assertEquals("uV", PerfCounters.voltageUnit(4_000_000))
        assertEquals("V", PerfCounters.voltageUnit(4))
        assertEquals("mV", PerfCounters.voltageUnit(4012))
        // BatteryManager's "unsupported", and no voltage.
        assertNull(PerfCounters.powerMw(Long.MIN_VALUE, 4000))
        assertNull(PerfCounters.powerMw(-1_000_000, -1))
    }

    @Test fun aFileNotYetFilledInOrOfAnotherVersionReadsAsNothing() {
        val kinds = LongArray(12)
        assertNull(PerfCounters.parseStops(stopsFile(emptyList(), kinds, magic = "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000")))
        assertNull(PerfCounters.parseStops(stopsFile(emptyList(), kinds, version = 2)))
        assertNull(PerfCounters.parseStops(ByteArray(10)))
        assertNull(PerfCounters.parseFastPath(fastPathFile("steam", 1, LongArray(7), LongArray(7), magic = "\u0000".repeat(8))))
    }

    @Test fun aTruncatedStopsFileIsRefusedNotOverread() {
        val whole = stopsFile(listOf("openat" to longArrayOf(1, 0, 0, 1)), LongArray(12))
        assertNull(PerfCounters.parseStops(whole.copyOf(whole.size - 1)))
    }

    @Test fun fastPathFilesAreReadByFamily() {
        val hits = longArrayOf(10, 20, 30, 40, 50, 60, 70)
        val misses = longArrayOf(1, 2, 3, 4, 5, 6, 7)
        val file = PerfCounters.parseFastPath(fastPathFile("steamwebhelper", 4321, hits, misses))!!
        assertEquals("steamwebhelper", file.name)
        assertEquals(4321, file.pid)
        assertArrayEquals(hits, file.hits)
        assertArrayEquals(misses, file.misses)
    }

    @Test fun procStatToleratesSpacesAndParenthesesInTheName() {
        val line = "1234 (Web Content (x)) S 77 1234 1234 0 -1 4194560 100 0 0 0 250 50 9 3 20 0 12 0 5000 0 0"
        val stat = PerfCounters.parseStat(line)!!
        assertEquals(1234, stat.pid)
        assertEquals("Web Content (x)", stat.comm)
        assertEquals(77, stat.ppid)
        assertEquals(250L, stat.utime)
        assertEquals(50L, stat.stime)
        assertEquals(9L, stat.cutime)
        assertEquals(3L, stat.cstime)
        assertNull(PerfCounters.parseStat("garbage"))
    }

    @Test fun theClientsReaperNamesTheGame() {
        fun cmdline(vararg args: String) = args.joinToString("\u0000", postfix = "\u0000").toByteArray()
        assertEquals(1145360L, PerfCounters.steamLaunchAppId(cmdline(
            "/root/.local/share/Steam/linuxarm64/reaper", "SteamLaunch", "AppId=1145360", "--", "/x/proton", "run")))
        assertNull(PerfCounters.steamLaunchAppId(cmdline("reaper", "SteamLaunch", "AppId=0")))
        assertNull(PerfCounters.steamLaunchAppId(cmdline("bash", "-c", "AppId=7")))
    }

    @Test fun programsAreGroupedForTheSummary() {
        assertEquals("steam", PerfCounters.programGroup("steam"))
        assertEquals("steamwebhelper", PerfCounters.programGroup("steamwebhelper"))
        assertEquals("wineserver", PerfCounters.programGroup("wineserver"))
        assertEquals("wine", PerfCounters.programGroup("wine64-preloader"))
        assertEquals("wine", PerfCounters.programGroup("Game.EXE"))
        assertEquals("python", PerfCounters.programGroup("python3.12"))
        assertEquals("shell", PerfCounters.programGroup("bash"))
        assertEquals("other", PerfCounters.programGroup("grep"))
    }
}
