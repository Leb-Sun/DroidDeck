package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerfSamplesTest {
    private val ms = 1_000_000L
    /** 60 Hz: twice 16.7 ms is a stutter. */
    private val stutter = 2 * 16_666_667L

    @Test fun framesUpToAMilestoneAreItsAndTheRestWait() {
        val ring = FrameRing()
        listOf(100, 116, 133, 150, 300, 316).forEach { ring.add(it * ms) }
        val before = FrameStats()
        ring.drain(150 * ms, before, stutter)
        assertEquals(4L, before.count)
        assertEquals(3L, before.intervals)
        assertEquals(0L, before.stutters)
        // The stretch after: 150 -> 300 is a 150 ms stutter, unless a milestone came between.
        val after = FrameStats()
        ring.drain(Long.MAX_VALUE, after, stutter)
        assertEquals(2L, after.count)
        assertEquals(2L, after.intervals)
        assertEquals(1L, after.stutters)
    }

    @Test fun aMilestoneAWindowChangeOrAPauseSpanNoInterval() {
        val ring = FrameRing()
        ring.add(100 * ms)
        ring.drain(Long.MAX_VALUE, FrameStats(), stutter)
        ring.restart()
        ring.add(900 * ms)
        ring.add(916 * ms)
        ring.cut()
        ring.add(2_000 * ms)
        ring.add(2_016 * ms)
        val stats = FrameStats()
        ring.drain(Long.MAX_VALUE, stats, stutter)
        assertEquals(4L, stats.count)
        assertEquals(2L, stats.intervals)
        assertEquals(0L, stats.stutters)
        // Dropped while the screen was off: the next frame starts afresh.
        ring.add(9_000 * ms)
        ring.drain(Long.MAX_VALUE, null, stutter)
        ring.add(20_000 * ms)
        ring.add(20_016 * ms)
        val next = FrameStats()
        ring.drain(Long.MAX_VALUE, next, stutter)
        assertEquals(1L, next.intervals)
        assertEquals(16.0, next.percentileMs(0.5)!!, 0.0)
    }

    @Test fun aReaderLappedByTheWriterSkipsWhatItLost() {
        val ring = FrameRing(capacity = 8)
        for (i in 0 until 20) ring.add((1000 + 10 * i) * ms)
        val stats = FrameStats()
        ring.drain(Long.MAX_VALUE, stats, stutter)
        // The newest eight survive; no interval reaches back over the lost twelve.
        assertEquals(8L, stats.count)
        assertEquals(7L, stats.intervals)
        assertEquals(10.0, stats.percentileMs(0.99)!!, 0.0)
    }

    @Test fun percentilesFpsAndStuttersComeFromTheIntervals() {
        val stats = FrameStats()
        repeat(97) { stats.interval(16_666_667L, stutter) }
        stats.interval(25 * ms, stutter)
        stats.interval(50 * ms, stutter)
        stats.interval(250 * ms, stutter)
        assertEquals(16.7, stats.percentileMs(0.5)!!, 0.0)
        assertEquals(16.7, stats.percentileMs(0.95)!!, 0.0)
        assertEquals(50.0, stats.percentileMs(0.99)!!, 0.0)
        // Over 100 ms the bins are 10 ms wide, read at their middle.
        assertEquals(255.0, stats.percentileMs(1.0)!!, 0.0)
        assertEquals(2L, stats.stutters)
        assertEquals(100 / (97 * 0.016666667 + 0.025 + 0.05 + 0.25), stats.fps!!, 0.01)
        assertNull(FrameStats().percentileMs(0.5))
        assertNull(FrameStats().fps)
    }

    @Test fun stretchesAddUpMeansOverSamplesAndPeaksOverAll() {
        val a = PerfSamples().apply {
            power(3000.0, false, -750_000, 4000)
            power(5000.0, false, -1_250_000, 4000)
            memory(400 * 1024, 2000 * 1024)
            processes(1, 1, mapOf("session" to 30), 2, 33)
            frames.interval(16 * ms, stutter)
        }
        val b = PerfSamples().apply {
            power(1000.0, true, 250_000, 4100)
            memory(420 * 1024, 1000 * 1024)
            processes(1, 2, mapOf("session" to 20, "game" to 12), 1, 36)
            frames.interval(40 * ms, stutter)
        }
        val all = PerfSamples().apply { add(a); add(b) }
        assertEquals(3, all.powerSamples)
        assertEquals(3000.0, all.powerMwMean!!, 0.001)
        assertEquals(5000.0, all.powerMwPeak, 0.0)
        assertEquals(true, all.plugged)
        assertEquals(410.0 * 1024, all.mainKbMean!!, 0.001)
        assertEquals(2000L * 1024, all.treeKbPeak)
        assertEquals(mapOf("game" to 12, "session" to 30), all.traced.toMap())
        assertEquals(36, all.phantoms)
        assertEquals(2, all.tracers)
        assertEquals(2, all.processSamples)
        assertEquals(2L, all.frames.intervals)
        assertEquals(1L, all.frames.stutters)
    }
}
