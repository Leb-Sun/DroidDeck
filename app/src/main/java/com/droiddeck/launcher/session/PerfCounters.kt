package com.droiddeck.launcher.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One syscall's row in proot's stop counters: entry and exit stops, emulations, and their time. */
data class SyscallStops(val enter: Long, val exit: Long, val emulated: Long, val ns: Long) {
    val stops: Long get() = enter + exit + emulated
}

/** One read of proot's stop counter file. [kinds] and [kindNs] follow [PerfCounters.STOP_KINDS]. */
class ProotStops(
    val stops: Long,
    val ns: Long,
    val kinds: LongArray,
    val kindNs: LongArray,
    val syscalls: Map<String, SyscallStops>,
) {
    fun kind(name: String): Long = kinds.getOrElse(PerfCounters.STOP_KINDS.indexOf(name)) { 0L }
}

/** One process's fast-path counter file: hits and misses by [PerfCounters.FAMILIES]. */
class FastPathFile(val name: String, val pid: Int, val hits: LongArray, val misses: LongArray)

/** The fields of /proc/<pid>/stat the report uses; times in clock ticks. */
data class ProcStat(
    val pid: Int, val comm: String, val ppid: Int,
    val utime: Long, val stime: Long, val cutime: Long, val cstime: Long,
)

/**
 * Readers for the counter files the Performance report samples (SessionPerf): proot's stop counters
 * (PROOT_STATS, tools/proot/patches/0015 - the layout is in tools/proot/PATCHES.md) and the fast
 * path's per-process files (PROOT_FP_STATS_DIR, live_counters in tools/proot/fastpath/fastpath.c).
 * Both are written in the device's byte order, little-endian on every arch the app ships. A file
 * whose magic is not there yet - mapped but not filled in - reads as null.
 */
object PerfCounters {
    /** proot's stop kinds, in its file's order. */
    val STOP_KINDS = listOf(
        "seccomp", "sysenter", "sysexit", "sigsys", "clone", "fork", "vfork", "vforkDone",
        "exec", "exit", "signal", "gone",
    )

    /** The fast path's call families, in its file's order. */
    val FAMILIES = listOf("open", "stat", "access", "path", "write", "socket", "other")

    private const val KINDS_MAX = 16
    private const val FAST_PATH_HEADER = 56

    fun parseStops(bytes: ByteArray): ProotStops? {
        if (bytes.size < 72 + 2 * 8 * KINDS_MAX || !bytes.startsWith("PRSTATS\u0000")) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(8) != 1) return null
        val headerSize = b.getInt(12)
        val nbKinds = b.getInt(16).coerceIn(0, KINDS_MAX)
        val nbSysnums = b.getInt(20)
        val nameSize = b.getInt(24)
        val namesAt = headerSize.toLong() + 32L * nbSysnums
        if (headerSize < 72 + 2 * 8 * KINDS_MAX || nbSysnums < 0 || nameSize <= 0 ||
            namesAt + nameSize.toLong() * nbSysnums > bytes.size) return null
        val kinds = LongArray(STOP_KINDS.size) { if (it < nbKinds) b.getLong(72 + 8 * it) else 0L }
        val kindNs = LongArray(STOP_KINDS.size) { if (it < nbKinds) b.getLong(72 + 8 * KINDS_MAX + 8 * it) else 0L }
        val syscalls = LinkedHashMap<String, SyscallStops>()
        for (i in 0 until nbSysnums) {
            val at = headerSize + 32 * i
            val row = SyscallStops(b.getLong(at), b.getLong(at + 8), b.getLong(at + 16), b.getLong(at + 24))
            if (row.stops == 0L) continue
            val name = cString(bytes, (namesAt + nameSize.toLong() * i).toInt(), nameSize).ifEmpty { "#$i" }
            syscalls[name] = row
        }
        return ProotStops(b.getLong(56), b.getLong(64), kinds, kindNs, syscalls)
    }

    fun parseFastPath(bytes: ByteArray): FastPathFile? {
        if (bytes.size < FAST_PATH_HEADER || !bytes.startsWith("FPSTATS\u0000")) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(8) != 1) return null
        val families = b.getInt(12)
        if (families <= 0 || bytes.size < FAST_PATH_HEADER + 16 * families) return null
        fun counts(from: Int) = LongArray(FAMILIES.size) {
            if (it < families) b.getLong(FAST_PATH_HEADER + 8 * (from + it)) else 0L
        }
        return FastPathFile(cString(bytes, 24, 32).ifEmpty { "unknown" }, b.getInt(16), counts(0), counts(families))
    }

    /** /proc/<pid>/stat: the name sits in parentheses and may hold spaces or more parentheses. */
    fun parseStat(line: String): ProcStat? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 1 || close < open) return null
        val f = line.substring(close + 1).trim().split(' ')
        if (f.size < 15) return null
        return ProcStat(
            pid = line.substring(0, open).trim().toIntOrNull() ?: return null,
            comm = line.substring(open + 1, close),
            ppid = f[1].toIntOrNull() ?: return null,
            utime = f[11].toLongOrNull() ?: return null,
            stime = f[12].toLongOrNull() ?: return null,
            cutime = f[13].toLongOrNull() ?: return null,
            cstime = f[14].toLongOrNull() ?: return null,
        )
    }

    /** "SteamLaunch AppId=<n>" in a NUL-separated command line: the Steam client's reaper for a game. */
    fun steamLaunchAppId(cmdline: ByteArray): Long? {
        val args = String(cmdline, Charsets.UTF_8).split('\u0000')
        if ("SteamLaunch" !in args) return null
        return args.firstOrNull { it.startsWith("AppId=") }?.substringAfter('=')?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** The group a fast-path program is summed under in the summary. */
    fun programGroup(name: String): String = when {
        name == "steam" -> "steam"
        name.startsWith("steamwebhelper") -> "steamwebhelper"
        name == "wineserver" -> "wineserver"
        name.startsWith("wine") || name.endsWith(".exe", ignoreCase = true) -> "wine"
        name.startsWith("python") -> "python"
        name in setOf("bash", "sh", "dash") -> "shell"
        name.startsWith("gamescope") || name.startsWith("Xwayland") || name == "mangoapp" -> "gamescope"
        name.startsWith("FEX") -> "fex"
        else -> "other"
    }

    private fun ByteArray.startsWith(magic: String): Boolean =
        size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

    private fun cString(bytes: ByteArray, at: Int, size: Int): String {
        var end = at
        while (end < at + size && end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, at, end - at, Charsets.UTF_8)
    }
}
