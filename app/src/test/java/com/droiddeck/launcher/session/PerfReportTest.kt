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

    private val ms = 1_000_000L

    /** What the scan saw in one stretch: [intervals] frames [frameMs] apart, [slow] of them 50 ms, at [mw]. */
    private fun stretch(intervals: Int, frameMs: Double, slow: Int = 0, mw: Double = 2000.0, phantoms: Int = 30) = PerfSamples().apply {
        frames.count = (intervals + slow).toLong()
        repeat(intervals) { frames.interval((frameMs * ms).toLong(), 2 * 16_666_667L) }
        repeat(slow) { frames.interval(50 * ms, 2 * 16_666_667L) }
        power(mw, false, -(mw * 250).toLong(), 4000)
        power(mw + 1000, false, -((mw + 1000) * 250).toLong(), 4000)
        memory(400 * 1024, 3000 * 1024)
        processes(1, if (phantoms > 30) 2 else 1, if (phantoms > 30) mapOf("session" to 25, "game" to 8) else mapOf("session" to 27),
            2, phantoms)
    }

    /** The session above, with a game tracer from the game's focus on and what the scan saw. */
    private val sampled = session.mapIndexed { i, s ->
        val game = if (s.label in setOf(PerfReport.FOCUS, PerfReport.EXIT, PerfReport.END)) {
            listOf(TracerRead("proot-game-1.stats", "game", 7000, stops(s.atMs / 1000 - 120), 4 * (s.atMs / 1000 - 120)))
        } else emptyList()
        val tracers = if (s.stops == null) emptyList() else listOf(TracerRead("proot.stats", "session", 4000, s.stops, s.tracerTicks)) + game
        val samples = when (s.label) {
            PerfReport.CREATED -> null
            PerfReport.LAUNCH -> stretch(1000, 33.3)
            PerfReport.EXIT -> stretch(7000, 16.67, slow = 100, mw = 5000.0, phantoms = 40)
            else -> stretch(100, 16.67)
        }
        PerfSnapshot(s.label, s.appId, s.atMs, s.wallMs, s.tracerTicks, s.treeTicks, s.treeProcesses, s.stops, s.fastPath,
            tracers, samples, thermalHeadroom = 0.4 + i * 0.05)
    }

    private val full = PerfInfo(info.generated, info.build, info.device, info.toggles, info.ticksPerSecond,
        frameTargetNs = 16_666_667L, powerUnits = mapOf("current" to "uA", "voltage" to "mV", "dischargeSign" to -1),
        teardownMs = 1460L, gameStates = listOf(
            mapOf("atMs" to 0L, "loading" to true, "mode" to "content", "after" to "session.created"),
            mapOf("atMs" to 28_000L, "loading" to false, "mode" to "content", "after" to "focus.changed"),
        ), glibc = mapOf("state" to "patched", "pkgver" to "2.43+r5-1"))

    @Test fun framesPowerMemoryAndProcessesArePerPhase() {
        val json = PerfReport.build(full, sampled)
        assertEquals("droiddeck.perf/2", json.getString("schema"))
        assertEquals(16.7, json.getDouble("frameTargetMs"), 0.0)
        val phases = json.getJSONArray("phases")
        val inGame = phases.getJSONObject(4)
        assertEquals("in_game", inGame.getString("name"))
        // Only the stretch from focus to exit: 7000 frame times of 16.7 ms and 100 of 50.
        val frames = inGame.getJSONObject("frames")
        assertEquals(7100L, frames.getLong("count"))
        assertEquals(16.7, frames.getDouble("p50Ms"), 0.0)
        assertEquals(16.7, frames.getDouble("p95Ms"), 0.0)
        assertEquals(50.0, frames.getDouble("p99Ms"), 0.0)
        assertEquals(100L, frames.getLong("stutters"))
        assertEquals(7100 / (7000 * 0.01667 + 100 * 0.05), frames.getDouble("fps"), 0.1)
        val power = inGame.getJSONObject("power")
        assertEquals(5500.0, power.getDouble("meanMw"), 0.0)
        assertEquals(6000.0, power.getDouble("peakMw"), 0.0)
        assertFalse(power.getBoolean("plugged"))
        assertEquals(-1_375_000.0, power.getJSONObject("raw").getDouble("currentNowMean"), 0.0)
        assertEquals(3000.0, inGame.getJSONObject("rssMb").getJSONObject("sessionTree").getDouble("peak"), 0.0)
        assertTrue(inGame.getJSONObject("rssMb").isNull("game"))
        val processes = inGame.getJSONObject("processes")
        assertEquals(40, processes.getInt("peakPhantoms"))
        assertEquals(8, processes.getJSONObject("traced").getInt("game"))
        assertEquals(0.7, inGame.getJSONObject("thermalHeadroom").getDouble("start"), 0.0)
        assertEquals(0.75, inGame.getJSONObject("thermalHeadroom").getDouble("end"), 0.0)
        // Startup spans three stretches: proot, the first frame and READY.
        assertEquals(300L, phases.getJSONObject(0).getJSONObject("frames").getLong("count"))
        assertEquals(6, phases.getJSONObject(0).getJSONObject("power").getInt("samples"))
        // The session sums every stretch.
        val all = json.getJSONObject("session")
        assertEquals(40, all.getJSONObject("processes").getInt("peakPhantoms"))
        assertEquals(7100L + 1000 + 6 * 100, all.getJSONObject("frames").getLong("count"))
        assertEquals(0.4, json.getJSONArray("milestones").getJSONObject(0).getDouble("thermalHeadroom"), 0.0)
    }

    @Test fun everyTracerIsCountedAndAGameTracerCountsFromItsStart() {
        val phases = PerfReport.build(full, sampled).getJSONArray("phases")
        val launch = phases.getJSONObject(3)
        assertEquals("launch_to_focus", launch.getString("name"))
        val tracers = launch.getJSONArray("tracers")
        assertEquals(listOf("session", "game"), (0 until tracers.length()).map { tracers.getJSONObject(it).getString("role") })
        // The game's file was not there at the launch: its stops count from zero.
        val game = tracers.getJSONObject(1)
        assertEquals(7000, game.getInt("pid"))
        assertEquals(stops(10).stops, game.getLong("stops"))
        assertEquals(stops(10).stops / 40.0, game.getDouble("stopsPerSecond"), 0.1)
        assertEquals(1.0, game.getDouble("tracerPct"), 0.0)
        val session = tracers.getJSONObject(0)
        assertEquals(launch.getLong("stops"), session.getLong("stops"))
        assertEquals(launch.getDouble("tracerPct"), session.getDouble("tracerPct"), 0.0)
        // Before any game tracer, the session's alone.
        assertEquals(1, phases.getJSONObject(0).getJSONArray("tracers").length())
        val milestone = PerfReport.build(full, sampled).getJSONArray("milestones").getJSONObject(6)
        assertEquals("proot-game-1.stats", milestone.getJSONArray("tracers").getJSONObject(1).getString("file"))
    }

    @Test fun teardownGameStatesAndGlibcAreReportedOrNull() {
        val json = PerfReport.build(full, sampled)
        assertEquals(1460L, json.getLong("teardownMs"))
        val states = json.getJSONObject("hints").getJSONArray("gameState")
        assertEquals(2, states.length())
        assertEquals("focus.changed", states.getJSONObject(1).getString("after"))
        assertEquals("patched", json.getJSONObject("glibc").getString("state"))
        assertEquals(-1, json.getJSONObject("powerUnits").getInt("dischargeSign"))
        // A session without them: null, and a stock glibc.
        val bare = PerfReport.build(info, session)
        assertTrue(bare.isNull("teardownMs"))
        assertTrue(bare.getJSONObject("hints").isNull("gameState"))
        assertEquals("stock", bare.getJSONObject("glibc").getString("state"))
        assertFalse(bare.getJSONObject("glibc").has("pkgver"))
        assertTrue(bare.getJSONArray("phases").getJSONObject(0).isNull("frames"))
        assertTrue(bare.getJSONArray("phases").getJSONObject(0).isNull("power"))
    }

    @Test fun theSummaryGainsFramesAndAGameTracersColumnAndStaysShort() {
        val text = PerfReport.summary(full, sampled)
        val lines = text.trimEnd().lines()
        assertTrue("${lines.size} lines\n$text", lines.size <= 34)
        val header = lines.first { it.startsWith("phase") }
        assertTrue(header, header.contains("fps") && header.contains("p99 ms") && header.endsWith("game t/s"))
        val inGame = lines.first { it.startsWith("in game") }.split(Regex(" +"))
        assertEquals("50.0", inGame[inGame.size - 2])
        assertTrue(text, text.contains("teardown 1.5 s"))
        assertTrue(text, lines.any { it.startsWith("power    session") && it.contains("in game 5.5 W, peak 6.0 W") && it.contains("unplugged") })
        assertTrue(text, lines.any { it.startsWith("session  peak phantoms 40") && it.contains("glibc patched 2.43+r5-1") })
        lines.forEach { assertEquals(it, LogRedactor.redactForShare(it)) }
        // No game tracer, no column.
        assertFalse(PerfReport.summary(info, session).contains("game t/s"))
    }

    @Test fun togglesAreTheAppsThenWhateverElseIsSet() {
        val toggles = PerfReport.toggles(
            linkedMapOf("prootFastPath" to true, "sync" to "fsync"),
            linkedMapOf("prootExitKill" to true, "auditScope" to "client", "prootFastPath" to false),
        )
        assertEquals(listOf("prootFastPath", "sync", "prootExitKill", "auditScope"), toggles.keys.toList())
        // What the app works out wins over what is stored.
        assertEquals(true, toggles["prootFastPath"])
        assertEquals("client", toggles["auditScope"])
        assertFalse(toggles.containsKey("gameProot"))
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
