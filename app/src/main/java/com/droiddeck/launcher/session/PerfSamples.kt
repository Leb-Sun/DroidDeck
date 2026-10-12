package com.droiddeck.launcher.session

/**
 * Frame times from the compositor thread to the report's (SessionPerf): the frame tap stores each
 * presented frame's System.nanoTime() here, and the report's thread drains them about once a second
 * into [FrameStats]. One writer and one reader, no lock and no allocation per frame: the writer
 * stores the stamp, then publishes the count through a volatile write. A reader more than
 * [capacity] stamps behind has lost the oldest; it skips them and starts afresh, so no interval
 * spans the gap. 4096 stamps are 30 s at 120 fps, against a drain each second.
 */
class FrameRing(val capacity: Int = 4096) {
    private val stamps = LongArray(capacity)
    @Volatile private var written = 0L
    // The reader's alone.
    private var read = 0L
    private var previous = 0L

    /** The compositor thread: one frame. */
    fun add(nanos: Long) {
        val n = written
        stamps[(n % capacity).toInt()] = nanos
        written = n + 1
    }

    /** The compositor thread: the window counted changed, so no interval spans the change. */
    fun cut() = add(CUT)

    /**
     * The reader: every stamp up to [until] into [into], and each interval between two of them
     * ([FrameStats.interval]). A null [into] drops them: the screen was off, or the session
     * suspended, and the next interval starts after them.
     */
    fun drain(until: Long, into: FrameStats?, stutterNs: Long) {
        val end = written
        if (end - read > capacity) {
            read = end - capacity
            previous = 0
        }
        while (read < end) {
            val t = stamps[(read % capacity).toInt()]
            if (t != CUT && t > until) break
            read++
            when {
                t == CUT -> previous = 0
                into == null -> {}
                else -> {
                    into.count++
                    // A stamp out of order was overwritten while it was read: start afresh.
                    if (previous != 0L && t > previous) into.interval(t - previous, stutterNs)
                    previous = if (t > previous || previous == 0L) t else 0
                }
            }
        }
        if (into == null) previous = 0
    }

    /** The reader: the next interval is not counted (it would span a milestone). */
    fun restart() {
        previous = 0
    }

    private companion object {
        const val CUT = Long.MIN_VALUE
    }
}

/**
 * Presented frames and the intervals between them: what one stretch of a session showed. Intervals
 * are kept in a histogram, 0.1 ms wide below 100 ms and 10 ms wide up to 2 s, which is what the
 * percentiles are read from; longer ones share the last bin.
 */
class FrameStats {
    var count = 0L
    var intervals = 0L
        private set
    var totalNs = 0L
        private set
    var stutters = 0L
        private set
    private var bins: IntArray? = null

    /** One interval of [ns]; over [stutterNs] (twice the frame time aimed at) it is a stutter. */
    fun interval(ns: Long, stutterNs: Long) {
        intervals++
        totalNs += ns
        if (stutterNs > 0 && ns > stutterNs) stutters++
        val b = bins ?: IntArray(BINS).also { bins = it }
        b[bin(ns)]++
    }

    fun add(other: FrameStats) {
        count += other.count
        intervals += other.intervals
        totalNs += other.totalNs
        stutters += other.stutters
        val theirs = other.bins ?: return
        val b = bins ?: IntArray(BINS).also { bins = it }
        for (i in b.indices) b[i] += theirs[i]
    }

    /** Frames a second over the intervals counted, which leave out the gaps that were not frames. */
    val fps: Double? get() = if (intervals > 0 && totalNs > 0) intervals * 1e9 / totalNs else null

    /** The interval [q] (0..1) of them are no longer than, in ms; null before any. */
    fun percentileMs(q: Double): Double? {
        val b = bins ?: return null
        if (intervals == 0L) return null
        val rank = Math.ceil(q * intervals).toLong().coerceIn(1, intervals)
        var seen = 0L
        for (i in b.indices) {
            seen += b[i]
            if (seen >= rank) return binMs(i)
        }
        return binMs(b.size - 1)
    }

    private companion object {
        const val FINE = 1000 // 0.1 ms bins, to 100 ms
        const val BINS = FINE + 191 // then 10 ms bins to 2 s, and one for longer

        fun bin(ns: Long): Int {
            val tenths = Math.round(ns / 100_000.0)
            if (tenths < FINE) return tenths.toInt().coerceAtLeast(0)
            return (FINE + (ns / 1_000_000 - 100) / 10).toInt().coerceAtMost(BINS - 1)
        }

        fun binMs(i: Int): Double = if (i < FINE) i / 10.0 else 100.0 + (i - FINE) * 10 + 5
    }
}

/**
 * What the once-a-second scan (SessionPerf) and the frame tap saw between two milestones: frames,
 * battery power, memory and the uid's processes. A phase sums the stretches it spans ([add]):
 * means over every sample, peaks over all of them.
 */
class PerfSamples {
    val frames = FrameStats()

    var powerSamples = 0
        private set
    private var powerMwSum = 0.0
    var powerMwPeak = 0.0
        private set
    /** Plugged in at any sample: the battery then shows its net flow, not the device's draw. */
    var plugged = false
        private set
    private var currentRawSum = 0.0
    private var voltageRawSum = 0.0

    var memorySamples = 0
        private set
    private var mainKbSum = 0L
    var mainKbPeak = 0L
        private set
    private var treeKbSum = 0L
    var treeKbPeak = 0L
        private set

    var processSamples = 0
        private set
    /** Peaks, each on its own: the app's processes, tracers, traced by tracer role, the untraced rest. */
    var apps = 0
        private set
    var tracers = 0
        private set
    val traced = sortedMapOf<String, Int>()
    var untraced = 0
        private set
    var phantoms = 0
        private set

    val powerMwMean: Double? get() = if (powerSamples > 0) powerMwSum / powerSamples else null
    val currentRawMean: Double? get() = if (powerSamples > 0) currentRawSum / powerSamples else null
    val voltageRawMean: Double? get() = if (powerSamples > 0) voltageRawSum / powerSamples else null
    val mainKbMean: Double? get() = if (memorySamples > 0) mainKbSum.toDouble() / memorySamples else null
    val treeKbMean: Double? get() = if (memorySamples > 0) treeKbSum.toDouble() / memorySamples else null

    fun power(mw: Double, plugged: Boolean, currentRaw: Long, voltageRaw: Int) {
        powerSamples++
        powerMwSum += mw
        powerMwPeak = maxOf(powerMwPeak, mw)
        this.plugged = this.plugged || plugged
        currentRawSum += currentRaw
        voltageRawSum += voltageRaw
    }

    fun memory(mainKb: Long, treeKb: Long) {
        memorySamples++
        mainKbSum += mainKb
        mainKbPeak = maxOf(mainKbPeak, mainKb)
        treeKbSum += treeKb
        treeKbPeak = maxOf(treeKbPeak, treeKb)
    }

    fun processes(apps: Int, tracers: Int, traced: Map<String, Int>, untraced: Int, phantoms: Int) {
        processSamples++
        this.apps = maxOf(this.apps, apps)
        this.tracers = maxOf(this.tracers, tracers)
        traced.forEach { (role, n) -> this.traced[role] = maxOf(this.traced[role] ?: 0, n) }
        this.untraced = maxOf(this.untraced, untraced)
        this.phantoms = maxOf(this.phantoms, phantoms)
    }

    fun add(other: PerfSamples) {
        frames.add(other.frames)
        powerSamples += other.powerSamples
        powerMwSum += other.powerMwSum
        powerMwPeak = maxOf(powerMwPeak, other.powerMwPeak)
        plugged = plugged || other.plugged
        currentRawSum += other.currentRawSum
        voltageRawSum += other.voltageRawSum
        memorySamples += other.memorySamples
        mainKbSum += other.mainKbSum
        mainKbPeak = maxOf(mainKbPeak, other.mainKbPeak)
        treeKbSum += other.treeKbSum
        treeKbPeak = maxOf(treeKbPeak, other.treeKbPeak)
        if (other.processSamples > 0) {
            processes(other.apps, other.tracers, other.traced, other.untraced, other.phantoms)
            processSamples += other.processSamples - 1
        }
    }
}
