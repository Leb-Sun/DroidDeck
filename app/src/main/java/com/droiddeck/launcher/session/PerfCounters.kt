package com.droiddeck.launcher.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One syscall's row in proot's stop counters: entry and exit stops, emulations, and their time. */
data class SyscallStops(val enter: Long, val exit: Long, val emulated: Long, val ns: Long) {
    val stops: Long get() = enter + exit + emulated
}

/**
 * One read of proot's stop counter file. [kinds] and [kindNs] follow [PerfCounters.STOP_KINDS];
 * [pid] is the tracer's, from the file's header.
 */
class ProotStops(
    val stops: Long,
    val ns: Long,
    val kinds: LongArray,
    val kindNs: LongArray,
    val syscalls: Map<String, SyscallStops>,
    val pid: Int = 0,
) {
    fun kind(name: String): Long = kinds.getOrElse(PerfCounters.STOP_KINDS.indexOf(name)) { 0L }
}

/** One process's fast-path counter file: hits and misses by [PerfCounters.FAMILIES]. */
class FastPathFile(val name: String, val pid: Int, val hits: LongArray, val misses: LongArray)

/** The fields of /proc/<pid>/status the report uses: the real uid, the parent, the tracer (0: none), VmRSS. */
data class ProcStatus(val uid: Int, val ppid: Int, val tracerPid: Int, val rssKb: Long)

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
    /** Enough of a stop counter file's header for its tracer's pid. */
    const val STATS_PID_BYTES = 40
    private val TRACER_FILE = Regex("""proot-([a-z]+)-[A-Za-z0-9_.-]+\.stats""")

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
        return ProotStops(b.getLong(56), b.getLong(64), kinds, kindNs, syscalls, b.getLong(32).toInt())
    }

    /** The tracer's pid from the first [STATS_PID_BYTES] of a stop counter file; null until it is filled in. */
    fun statsPid(header: ByteArray): Int? {
        if (header.size < STATS_PID_BYTES || !header.startsWith("PRSTATS\u0000")) return null
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(8) != 1) return null
        return b.getLong(32).toInt().takeIf { it > 0 }
    }

    /**
     * A tracer's role from its counter file's name: `proot.stats` is the session's proot, and
     * `proot-<role>-<n>.stats` another's (03-design.md §6.1: game, net). Null: not a counter file.
     */
    fun tracerRole(fileName: String): String? = when (fileName) {
        "proot.stats" -> "session"
        else -> TRACER_FILE.matchEntire(fileName)?.groupValues?.get(1)
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

    /** /proc/<pid>/status; null without the lines every process has. A kernel thread has no VmRSS: 0. */
    fun parseStatus(text: String): ProcStatus? {
        var uid: Int? = null
        var ppid: Int? = null
        var tracer: Int? = null
        var rss = 0L
        for (line in text.lineSequence()) {
            val value = line.substringAfter(':', "").trim()
            when (line.substringBefore(':')) {
                "Uid" -> uid = value.substringBefore('\t').substringBefore(' ').toIntOrNull()
                "PPid" -> ppid = value.toIntOrNull()
                "TracerPid" -> tracer = value.toIntOrNull()
                "VmRSS" -> rss = value.substringBefore(' ').toLongOrNull() ?: 0L
            }
        }
        return ProcStatus(uid ?: return null, ppid ?: return null, tracer ?: return null, rss)
    }

    /**
     * Whether a command line is one of the app's own processes: zygote names them after the package,
     * or `<package>:<name>`. Every other process of the uid is a phantom, the processes Android's
     * phantom process limit counts (PhantomProcessList); the caller also makes sure the parent is
     * not one of the uid's, since a child forked from the app keeps its name until it execs.
     */
    fun isAppProcess(cmdline: ByteArray, packageName: String): Boolean {
        val name = String(cmdline, Charsets.UTF_8).substringBefore('\u0000')
        return name == packageName || name.startsWith("$packageName:")
    }

    /**
     * /etc/droiddeck/glibc-state in the rootfs, `<state> <pkgver>` (03-design.md §4.2: patched, stock
     * or mismatch); without the file, the rootfs's own glibc: stock.
     */
    fun glibcState(text: String?): Map<String, Any?> {
        val words = text?.trim()?.lineSequence()?.firstOrNull()?.trim()?.split(Regex("\\s+")).orEmpty().filter { it.isNotEmpty() }
        if (words.isEmpty()) return linkedMapOf("state" to "stock")
        return linkedMapOf("state" to words[0], "pkgver" to words.getOrNull(1))
    }

    /**
     * Battery power in mW from BatteryManager's CURRENT_NOW and the sticky ACTION_BATTERY_CHANGED's
     * EXTRA_VOLTAGE, read as OEMs report them rather than as documented:
     * - current is µA by the documentation and mA on some devices: a magnitude under 10,000 is read
     *   as mA, since a running session draws far more than 10 mA and no phone 10 A;
     * - voltage is mV, and V or µV on some: under 100 is V, over 100,000 µV;
     * - the sign is dropped: negative while discharging is AOSP's convention, which some OEMs invert.
     * Unplugged, that is the device's draw; plugged in, the battery's net flow. Null: no reading
     * (Long.MIN_VALUE is BatteryManager's "unsupported").
     */
    fun powerMw(currentRaw: Long, voltageRaw: Int): Double? {
        if (currentRaw == Long.MIN_VALUE || voltageRaw <= 0) return null
        val milliamps = Math.abs(currentRaw.toDouble()).let { if (currentUnit(currentRaw) == "mA") it else it / 1000 }
        val volts = when (voltageUnit(voltageRaw)) {
            "V" -> voltageRaw.toDouble()
            "uV" -> voltageRaw / 1e6
            else -> voltageRaw / 1000.0
        }
        return milliamps * volts
    }

    /** The unit [powerMw] reads a CURRENT_NOW value in. */
    fun currentUnit(currentRaw: Long): String = if (Math.abs(currentRaw) < 10_000) "mA" else "uA"

    /** The unit [powerMw] reads an EXTRA_VOLTAGE value in. */
    fun voltageUnit(voltageRaw: Int): String = when {
        voltageRaw < 100 -> "V"
        voltageRaw > 100_000 -> "uV"
        else -> "mV"
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
