package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.CapProbe
import com.droiddeck.launcher.core.Caps
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Fast-path hits and misses for one program (or a sum of them), by [PerfCounters.FAMILIES]. */
class FamilyCounts(
    val hits: LongArray = LongArray(PerfCounters.FAMILIES.size),
    val misses: LongArray = LongArray(PerfCounters.FAMILIES.size),
) {
    val calls: Long get() = hits.sum() + misses.sum()

    fun add(other: FamilyCounts) {
        for (i in hits.indices) {
            hits[i] += other.hits[i]
            misses[i] += other.misses[i]
        }
    }

    fun minus(before: FamilyCounts?): FamilyCounts = FamilyCounts(
        LongArray(hits.size) { hits[it] - (before?.hits?.get(it) ?: 0L) },
        LongArray(misses.size) { misses[it] - (before?.misses?.get(it) ?: 0L) },
    )
}

/**
 * One tracer at a milestone: its counter file (cache/perf/proot*.stats), the role the file's name
 * gives it (PerfCounters.tracerRole), its pid, its stop counters and its CPU time in clock ticks.
 */
class TracerRead(val file: String, val role: String, val pid: Int, val stops: ProotStops?, val ticks: Long?)

/**
 * The session at one milestone: when (ms since the session was created), the session tracer's and
 * the whole tree's CPU time in clock ticks, proot's stop counters and the fast path's counts by
 * program. A value that could not be read is null; the "created" milestone, before proot exists, is
 * all zero. [tracers] is every tracer's counters (the session's [stops] among them), [samples] what
 * the scan and the frame tap saw since the milestone before, and [thermalHeadroom]
 * PowerManager.getThermalHeadroom(10) (null before Android 11, or when it had no answer).
 */
class PerfSnapshot(
    val label: String,
    val appId: Long?,
    val atMs: Long,
    val wallMs: Long,
    val tracerTicks: Long?,
    val treeTicks: Long?,
    val treeProcesses: Int?,
    val stops: ProotStops?,
    val fastPath: Map<String, FamilyCounts>,
    val tracers: List<TracerRead> = emptyList(),
    val samples: PerfSamples? = null,
    val thermalHeadroom: Double? = null,
)

/**
 * What the report says about the build, the device and the settings, besides the milestones.
 * [caps] is the capability probe's answer (CapProbe), reported as device.caps. [frameTargetNs] is
 * the frame time the guest is paced to (a stutter is an interval over twice it), [powerUnits] how
 * the battery's readings were taken (PerfCounters.powerMw), [teardownMs] the stop until every
 * session process was gone (session.teardown_done), [gameStates] each hints.game_state change, and
 * [glibc] the rootfs's glibc-state. Null where the session gave none.
 */
class PerfInfo(
    val generated: String,
    val build: Map<String, Any?>,
    val device: Map<String, Any?>,
    val toggles: Map<String, Any?>,
    val ticksPerSecond: Long,
    val caps: Caps? = null,
    val frameTargetNs: Long? = null,
    val powerUnits: Map<String, Any?>? = null,
    val teardownMs: Long? = null,
    val gameStates: List<Map<String, Any?>>? = null,
    val glibc: Map<String, Any?>? = null,
)

/**
 * The Performance report (SessionPerf) from its milestones: `perf-report.json`, everything, and
 * `perf-report.txt`, the dozen numbers a comparison needs, short enough to paste into a chat. Phases
 * are the spans between milestones: startup (created to READY), READY to Big Picture (app 769 has
 * gamescope's focus), idle in Big Picture (to the first game launch, or the end), and for each game
 * launch to focus and in game (to its exit, or the end).
 */
object PerfReport {
    const val SCHEMA = "droiddeck.perf/2"

    const val CREATED = "created"
    const val PROOT = "proot"
    const val FIRST_FRAME = "first_frame"
    const val READY = "ready"
    const val BIG_PICTURE = "big_picture"
    const val LAUNCH = "launch"
    const val FOCUS = "focus"
    const val EXIT = "exit"
    const val END = "end"

    private const val TOP_PHASE_SYSCALLS = 10
    private const val TOP_PROGRAMS = 40
    private val GROUPS = listOf("steam", "steamwebhelper", "wine", "wineserver", "python", "shell", "gamescope", "fex", "other")
    /** Tracer roles (PerfCounters.tracerRole). */
    private const val SESSION = "session"
    private const val GAME = "game"
    private val NO_STOPS = ProotStops(0, 0, LongArray(PerfCounters.STOP_KINDS.size), LongArray(PerfCounters.STOP_KINDS.size), emptyMap())

    class Phase(val name: String, val from: PerfSnapshot, val to: PerfSnapshot) {
        val seconds: Double get() = (to.atMs - from.atMs) / 1000.0
        val appId: Long? get() = to.appId ?: from.appId
    }

    fun phases(snapshots: List<PerfSnapshot>): List<Phase> {
        val out = ArrayList<Phase>()
        val created = snapshots.firstOrNull { it.label == CREATED } ?: return out
        val end = snapshots.lastOrNull { it.label == END }
        val ready = snapshots.firstOrNull { it.label == READY }
        val bigPicture = snapshots.firstOrNull { it.label == BIG_PICTURE }
        ready?.let { out += Phase("startup", created, it) }
        if (ready != null && bigPicture != null && bigPicture.atMs >= ready.atMs) out += Phase("ready_to_big_picture", ready, bigPicture)
        if (bigPicture != null) {
            (snapshots.firstOrNull { it.label == LAUNCH && it.atMs >= bigPicture.atMs } ?: end)
                ?.let { out += Phase("idle_big_picture", bigPicture, it) }
        }
        for (launch in snapshots.filter { it.label == LAUNCH }) {
            val focus = snapshots.firstOrNull { it.label == FOCUS && it.appId == launch.appId && it.atMs >= launch.atMs } ?: continue
            out += Phase("launch_to_focus", launch, focus)
            (snapshots.firstOrNull { it.label == EXIT && it.appId == launch.appId && it.atMs >= focus.atMs } ?: end)
                ?.let { out += Phase("in_game", focus, it) }
        }
        return out
    }

    fun build(info: PerfInfo, snapshots: List<PerfSnapshot>): JSONObject {
        val json = JSONObject()
            .put("schema", SCHEMA)
            .put("generated", info.generated)
            .put("build", JSONObject(info.build))
            .put("device", JSONObject(info.device).apply { info.caps?.let { put("caps", CapProbe.toJson(it)) } })
            .put("toggles", JSONObject(info.toggles))
            .put("clockTicksPerSecond", info.ticksPerSecond)
            .put("frameTargetMs", num(info.frameTargetNs?.let { it / 1e6 }))
            .put("powerUnits", info.powerUnits?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("teardownMs", info.teardownMs ?: JSONObject.NULL)
            .put("hints", JSONObject().put("gameState", info.gameStates?.let { states -> JSONArray().apply { states.forEach { put(JSONObject(it)) } } }
                ?: JSONObject.NULL))
            .put("glibc", JSONObject(info.glibc ?: PerfCounters.glibcState(null)))
        json.put("milestones", JSONArray().apply {
            snapshots.forEach { s ->
                put(JSONObject().put("label", s.label).put("appId", s.appId ?: JSONObject.NULL)
                    .put("atMs", s.atMs).put("wallMs", s.wallMs)
                    .put("tracerTicks", s.tracerTicks ?: JSONObject.NULL).put("treeTicks", s.treeTicks ?: JSONObject.NULL)
                    .put("treeProcesses", s.treeProcesses ?: JSONObject.NULL)
                    .put("stops", s.stops?.let { stopsJson(it) } ?: JSONObject.NULL)
                    .put("fastPathCalls", s.fastPath.values.sumOf { it.calls })
                    .put("tracers", JSONArray().apply {
                        s.tracers.forEach { t ->
                            put(JSONObject().put("file", t.file).put("role", t.role).put("pid", t.pid)
                                .put("stops", t.stops?.stops ?: JSONObject.NULL).put("tracerTicks", t.ticks ?: JSONObject.NULL))
                        }
                    })
                    .put("thermalHeadroom", num2(s.thermalHeadroom)))
            }
        })
        json.put("times", times(snapshots))
        json.put("phases", JSONArray().apply { phases(snapshots).forEach { put(phaseJson(it, snapshots, info.ticksPerSecond)) } })
        val last = snapshots.lastOrNull()
        if (last != null) {
            val session = JSONObject().put("seconds", num(last.atMs / 1000.0))
            samplesJson(session, sessionSamples(snapshots))
            last.stops?.let { stops ->
                session.put("stops", stopsJson(stops))
                session.put("syscalls", JSONObject().apply {
                    stops.syscalls.entries.sortedByDescending { it.value.stops }.forEach { (name, row) ->
                        put(name, JSONObject().put("enter", row.enter).put("exit", row.exit)
                            .put("emulated", row.emulated).put("ms", num(row.ns / 1e6)))
                    }
                })
            }
            session.put("fastPath", fastPathJson(last.fastPath, emptyMap(), TOP_PROGRAMS))
            json.put("session", session)
        }
        return json
    }

    fun summary(info: PerfInfo, snapshots: List<PerfSnapshot>): String {
        val b = StringBuilder()
        fun line(text: String = "") { b.append(text.trimEnd()).append('\n') }
        line("DroidDeck perf report ($SCHEMA), ${info.generated}")
        line("build    " + listOf(info.build["label"], info.build["package"]).filterNotNull().joinToString(" · "))
        line("device   " + listOf(
            info.device["soc"]?.toString()?.ifEmpty { null } ?: info.device["model"],
            info.device["android"]?.let { "Android $it (API ${info.device["sdk"]})" },
            info.device["kernel"]?.let { "kernel " + it.toString().take(40) },
            info.device["cpus"]?.let { "$it CPUs" },
            info.device["pageSize"]?.let { "${(it as Number).toLong() / 1024} KB pages" },
        ).filterNotNull().joinToString(" · "))
        info.caps?.let { caps ->
            CapProbe.summary(caps).forEachIndexed { i, text -> line((if (i == 0) "caps     " else "         ") + text) }
        }
        line("toggles  " + info.toggles.entries.joinToString(" · ") { (k, v) -> "$k ${onOff(v)}" })
        val times = times(snapshots)
        line("times    " + listOfNotNull(
            times.takeIf { it.has("readyS") }?.let { "READY ${fmt1(it.getDouble("readyS"))} s" },
            times.takeIf { it.has("bigPictureS") }?.let { "Big Picture ${fmt1(it.getDouble("bigPictureS"))} s" },
        ).plus(times.optJSONArray("games")?.let { games ->
            (0 until minOf(games.length(), 3)).map { i ->
                val g = games.getJSONObject(i)
                "launch to focus ${fmt1(g.getDouble("launchToFocusS"))} s (app ${g.getLong("appId")})"
            }
        }.orEmpty()).plus(listOfNotNull(info.teardownMs?.let { "teardown ${fmt1(it / 1000.0)} s" }))
            .joinToString(" · ").ifEmpty { "none reached" })
        line()
        val phases = phases(snapshots)
        if (phases.isNotEmpty()) {
            // The game's own tracer gets a column once there is one (03-design.md M10).
            val game = snapshots.any { s -> s.tracers.any { it.role == GAME } }
            val columns = "%-20s %7s %8s %8s %7s %6s %6s %7s %6s %6s" + if (game) " %8s" else ""
            line(String.format(Locale.US, columns,
                "phase", "secs", "traps/s", "tracer%", "tree%", "execs", "sigsys", "fp hit%", "fps", "p99 ms", "game t/s"))
            phases.forEach { p ->
                val d = Delta(p, info.ticksPerSecond)
                val frames = samples(snapshots, p).frames
                line(String.format(Locale.US, columns,
                    PHASE_NAMES[p.name] ?: p.name, fmt1(p.seconds), d.stops?.let { count(rate(it, p.seconds)) } ?: "-",
                    d.tracerPct?.let { fmt1(it) } ?: "-", d.treePct?.let { fmt1(it) } ?: "-",
                    d.kind("exec")?.toString() ?: "-", d.kind("sigsys")?.toString() ?: "-",
                    d.fastPath?.let { hitRate(it) }?.let { fmt1(it) } ?: "-",
                    frames.fps?.let { fmt1(it) } ?: "-", frames.percentileMs(0.99)?.let { fmt1(it) } ?: "-",
                    tracerDeltas(p).filter { it.role == GAME }.mapNotNull { it.stops }.takeIf { it.isNotEmpty() }
                        ?.let { count(rate(it.sum(), p.seconds)) } ?: "-"))
            }
            line()
        }
        line("power    " + powerLine(phases, snapshots))
        line("session  " + sessionLine(info, snapshots))
        val last = snapshots.lastOrNull()
        val stops = last?.stops
        if (stops != null) {
            line("top trapped syscalls, whole session: stops (entry+exit+emulated), tracer ms")
            stops.syscalls.entries.sortedByDescending { it.value.stops }.take(10).forEach { (name, row) ->
                line(String.format(Locale.US, "  %-20s %8s %9s", name, count(row.stops.toDouble()), fmt0(row.ns / 1e6)))
            }
            line("stops by kind: " + PerfCounters.STOP_KINDS.filter { stops.kind(it) > 0 }
                .joinToString(" · ") { "$it ${count(stops.kind(it).toDouble())}" } +
                " · total ${count(stops.stops.toDouble())}, ${fmt1(stops.ns / 1e9)} s handling")
        } else {
            line("proot stop counters: not read (no PROOT_STATS file)")
        }
        val fastPath = last?.fastPath.orEmpty()
        if (fastPath.isNotEmpty()) {
            val groups = byGroup(fastPath)
            line("fast path hits, whole session: " + GROUPS.mapNotNull { g ->
                groups[g]?.takeIf { it.calls > 0 }?.let { "$g ${fmt1(hitRate(it) ?: 0.0)}% of ${count(it.calls.toDouble())}" }
            }.joinToString(" · "))
            val all = FamilyCounts().apply { fastPath.values.forEach { add(it) } }
            line("fast path misses by family: " + PerfCounters.FAMILIES.indices.filter { all.misses[it] > 0 }
                .joinToString(" · ") { "${PerfCounters.FAMILIES[it]} ${count(all.misses[it].toDouble())}" }.ifEmpty { "none" })
        } else {
            line("fast path counters: none (fast path off, or nothing ran)")
        }
        return b.toString()
    }

    private val PHASE_NAMES = mapOf(
        "startup" to "startup", "ready_to_big_picture" to "READY to Big Picture",
        "idle_big_picture" to "idle in Big Picture", "launch_to_focus" to "launch to focus", "in_game" to "in game",
    )

    /** What changed between a phase's two milestones; null where either side could not be read. */
    private class Delta(p: Phase, ticksPerSecond: Long) {
        private val before = p.from.stops ?: if (p.from.label == CREATED) NO_STOPS else null
        private val after = p.to.stops
        val stops: Long? = if (before != null && after != null) after.stops - before.stops else null
        val handleNs: Long? = if (before != null && after != null) after.ns - before.ns else null
        fun kind(name: String): Long? = if (before != null && after != null) after.kind(name) - before.kind(name) else null
        val tracerCpuS: Double? = cpu(p.from.tracerTicks, p.to.tracerTicks, ticksPerSecond)
        val treeCpuS: Double? = cpu(p.from.treeTicks, p.to.treeTicks, ticksPerSecond)
        val tracerPct: Double? = tracerCpuS?.let { pct(it, p.seconds) }
        val treePct: Double? = treeCpuS?.let { pct(it, p.seconds) }
        val fastPath: FamilyCounts? = FamilyCounts().apply {
            p.to.fastPath.forEach { (name, c) -> add(c.minus(p.from.fastPath[name])) }
        }.takeIf { p.to.fastPath.isNotEmpty() }

        fun topSyscalls(limit: Int): List<Pair<String, SyscallStops>> {
            if (before == null || after == null) return emptyList()
            return after.syscalls.map { (name, row) ->
                val was = before.syscalls[name]
                name to SyscallStops(row.enter - (was?.enter ?: 0), row.exit - (was?.exit ?: 0),
                    row.emulated - (was?.emulated ?: 0), row.ns - (was?.ns ?: 0))
            }.filter { it.second.stops > 0 }.sortedByDescending { it.second.stops }.take(limit)
        }

        private fun cpu(a: Long?, b: Long?, tps: Long): Double? =
            if (a == null || b == null || b < a || tps <= 0) null else (b - a).toDouble() / tps

        private fun pct(cpuS: Double, seconds: Double): Double? = if (seconds > 0) cpuS / seconds * 100 else null
    }

    private fun phaseJson(p: Phase, snapshots: List<PerfSnapshot>, ticksPerSecond: Long): JSONObject {
        val d = Delta(p, ticksPerSecond)
        val json = JSONObject().put("name", p.name).put("from", p.from.label).put("to", p.to.label)
            .put("appId", p.appId ?: JSONObject.NULL).put("seconds", num(p.seconds))
            .put("stops", d.stops ?: JSONObject.NULL)
            .put("stopsPerSecond", d.stops?.let { num(rate(it, p.seconds)) } ?: JSONObject.NULL)
            .put("handleMs", d.handleNs?.let { num(it / 1e6) } ?: JSONObject.NULL)
            .put("tracerCpuS", num(d.tracerCpuS)).put("tracerPct", num(d.tracerPct))
            .put("treeCpuS", num(d.treeCpuS)).put("treePct", num(d.treePct))
        json.put("tracers", JSONArray().apply {
            tracerDeltas(p).forEach { t ->
                val cpuS = t.ticks?.takeIf { ticksPerSecond > 0 }?.let { it.toDouble() / ticksPerSecond }
                put(JSONObject().put("role", t.role).put("pid", t.pid).put("stops", t.stops ?: JSONObject.NULL)
                    .put("stopsPerSecond", t.stops?.let { num(rate(it, p.seconds)) } ?: JSONObject.NULL)
                    .put("tracerPct", num(cpuS?.takeIf { p.seconds > 0 }?.let { it / p.seconds * 100 })))
            }
        })
        samplesJson(json, samples(snapshots, p))
        json.put("thermalHeadroom", JSONObject().put("start", num2(p.from.thermalHeadroom)).put("end", num2(p.to.thermalHeadroom)))
        if (d.stops != null) {
            json.put("kinds", JSONObject().apply {
                PerfCounters.STOP_KINDS.forEach { k -> d.kind(k)?.takeIf { it != 0L }?.let { put(k, it) } }
            })
            json.put("topSyscalls", JSONArray().apply {
                d.topSyscalls(TOP_PHASE_SYSCALLS).forEach { (name, row) ->
                    put(JSONObject().put("name", name).put("stops", row.stops).put("enter", row.enter)
                        .put("exit", row.exit).put("emulated", row.emulated).put("ms", num(row.ns / 1e6)))
                }
            })
        }
        if (p.to.fastPath.isNotEmpty()) json.put("fastPath", fastPathJson(p.to.fastPath, p.from.fastPath, TOP_PROGRAMS))
        return json
    }

    /** One tracer's counts through a phase: a file that was not there at the phase's start counts from zero. */
    private class TracerDelta(val role: String, val pid: Int, val stops: Long?, val ticks: Long?)

    private fun tracerDeltas(p: Phase): List<TracerDelta> = p.to.tracers.map { t ->
        val was = p.from.tracers.firstOrNull { it.file == t.file }
        val stops = t.stops?.let { now -> if (was == null) now.stops else was.stops?.let { now.stops - it.stops } }
        val ticks = t.ticks?.let { now -> if (was == null) now else was.ticks?.takeIf { was.pid == t.pid && now >= it }?.let { now - it } }
        TracerDelta(t.role, t.pid, stops, ticks)
    }.sortedWith(compareBy({ it.role != SESSION }, { it.role }, { it.pid }))

    /** What the scan and the frame tap saw through [p]: every stretch after its first milestone, to its last. */
    private fun samples(snapshots: List<PerfSnapshot>, p: Phase): PerfSamples {
        val from = snapshots.indexOfFirst { it === p.from }
        val to = snapshots.indexOfFirst { it === p.to }
        return PerfSamples().apply {
            if (from >= 0) for (i in from + 1..to) snapshots[i].samples?.let { add(it) }
        }
    }

    /** Frames, power, memory and processes, into [json] (a phase's, or the whole session's). */
    private fun samplesJson(json: JSONObject, s: PerfSamples) {
        val f = s.frames
        json.put("frames", if (f.count == 0L) JSONObject.NULL else JSONObject().put("count", f.count).put("fps", num(f.fps))
            .put("p50Ms", num(f.percentileMs(0.5))).put("p95Ms", num(f.percentileMs(0.95)))
            .put("p99Ms", num(f.percentileMs(0.99))).put("stutters", f.stutters))
        json.put("power", if (s.powerSamples == 0) JSONObject.NULL else JSONObject()
            .put("meanMw", num(s.powerMwMean)).put("peakMw", num(s.powerMwPeak)).put("plugged", s.plugged)
            .put("samples", s.powerSamples)
            .put("raw", JSONObject().put("currentNowMean", num(s.currentRawMean)).put("voltageMean", num(s.voltageRawMean))))
        fun mb(kb: Double?) = num(kb?.let { it / 1024 })
        json.put("rssMb", if (s.memorySamples == 0) JSONObject.NULL else JSONObject()
            .put("main", JSONObject().put("mean", mb(s.mainKbMean)).put("peak", mb(s.mainKbPeak.toDouble())))
            .put("sessionTree", JSONObject().put("mean", mb(s.treeKbMean)).put("peak", mb(s.treeKbPeak.toDouble())))
            .put("game", JSONObject.NULL))
        json.put("processes", if (s.processSamples == 0) JSONObject.NULL else JSONObject()
            .put("app", s.apps).put("tracers", s.tracers).put("traced", JSONObject(s.traced))
            .put("untraced", s.untraced).put("peakPhantoms", s.phantoms))
    }

    private fun sessionSamples(snapshots: List<PerfSnapshot>) = PerfSamples().apply { snapshots.forEach { s -> s.samples?.let { add(it) } } }

    /** Mean battery power where the session settles (idle in Big Picture, in game), the plug, thermal headroom. */
    private fun powerLine(phases: List<Phase>, snapshots: List<PerfSnapshot>): String {
        val all = sessionSamples(snapshots)
        if (all.powerSamples == 0) return "no battery reading"
        val parts = ArrayList<String>()
        parts += "session ${watts(all.powerMwMean)}"
        phases.filter { it.name == "idle_big_picture" || it.name == "in_game" }.forEach { p ->
            val s = samples(snapshots, p)
            if (s.powerSamples > 0) parts += "${PHASE_NAMES[p.name]} ${watts(s.powerMwMean)}, peak ${watts(s.powerMwPeak)}"
        }
        parts += if (all.plugged) "plugged in (the battery's net flow, not the draw)" else "unplugged"
        val headroom = snapshots.mapNotNull { it.thermalHeadroom }
        if (headroom.isNotEmpty()) parts += "thermal headroom ${fmt2(headroom.first())} to ${fmt2(headroom.last())}"
        return parts.joinToString(" · ")
    }

    /** Peaks over the session (phantom processes, who traces what, memory), then glibc and the game state hints. */
    private fun sessionLine(info: PerfInfo, snapshots: List<PerfSnapshot>): String {
        val all = sessionSamples(snapshots)
        val parts = ArrayList<String>()
        if (all.processSamples > 0) {
            parts += "peak phantoms ${all.phantoms}"
            parts += "traced " + all.traced.entries.joinToString(", ") { (role, n) -> "$n $role" }.ifEmpty { "0" } +
                ", untraced ${all.untraced}"
        }
        if (all.memorySamples > 0) {
            parts += "rss peak: app ${fmt0(all.mainKbPeak / 1024.0)} MB, session tree ${fmt0(all.treeKbPeak / 1024.0)} MB"
        }
        val glibc = info.glibc ?: PerfCounters.glibcState(null)
        parts += "glibc " + listOfNotNull(glibc["state"], glibc["pkgver"]).joinToString(" ")
        info.gameStates?.let { parts += "${it.size} game state hints" }
        return parts.joinToString(" · ")
    }

    private fun times(snapshots: List<PerfSnapshot>): JSONObject {
        val json = JSONObject()
        snapshots.firstOrNull { it.label == READY }?.let { json.put("readyS", num(it.atMs / 1000.0)) }
        snapshots.firstOrNull { it.label == BIG_PICTURE }?.let { json.put("bigPictureS", num(it.atMs / 1000.0)) }
        val games = JSONArray()
        phases(snapshots).filter { it.name == "launch_to_focus" }.forEach {
            games.put(JSONObject().put("appId", it.appId).put("launchToFocusS", num(it.seconds)))
        }
        if (games.length() > 0) json.put("games", games)
        return json
    }

    private fun stopsJson(s: ProotStops): JSONObject = JSONObject().put("stops", s.stops).put("ms", num(s.ns / 1e6))
        .put("kinds", JSONObject().apply { PerfCounters.STOP_KINDS.forEachIndexed { i, k -> put(k, s.kinds[i]) } })
        .put("kindMs", JSONObject().apply { PerfCounters.STOP_KINDS.forEachIndexed { i, k -> put(k, num(s.kindNs[i] / 1e6)) } })

    private fun byGroup(counts: Map<String, FamilyCounts>): Map<String, FamilyCounts> {
        val out = LinkedHashMap<String, FamilyCounts>()
        counts.forEach { (name, c) -> out.getOrPut(PerfCounters.programGroup(name)) { FamilyCounts() }.add(c) }
        return out
    }

    private fun fastPathJson(after: Map<String, FamilyCounts>, before: Map<String, FamilyCounts>, topPrograms: Int): JSONObject {
        val deltas = after.mapValues { (name, c) -> c.minus(before[name]) }.filterValues { it.calls > 0 }
        fun countsJson(c: FamilyCounts) = JSONObject().put("hits", c.hits.sum()).put("misses", c.misses.sum())
            .put("hitPct", num(hitRate(c)))
            .put("byFamily", JSONObject().apply {
                PerfCounters.FAMILIES.forEachIndexed { i, f ->
                    if (c.hits[i] + c.misses[i] > 0) put(f, JSONObject().put("hits", c.hits[i]).put("misses", c.misses[i]))
                }
            })
        val all = FamilyCounts().apply { deltas.values.forEach { add(it) } }
        return JSONObject().put("all", countsJson(all))
            .put("byGroup", JSONObject().apply { byGroup(deltas).forEach { (g, c) -> put(g, countsJson(c)) } })
            .put("byProgram", JSONObject().apply {
                deltas.entries.sortedByDescending { it.value.calls }.take(topPrograms).forEach { (n, c) -> put(n, countsJson(c)) }
            })
    }

    /**
     * The report's toggles: [effective], the values the app works out (its defaults, the one sync the
     * three switches pick), then whatever else [stored] holds, as stored. A key never set is in
     * neither, and left out.
     */
    fun toggles(effective: Map<String, Any?>, stored: Map<String, Any?>): Map<String, Any?> =
        LinkedHashMap(effective).apply { stored.forEach { (k, v) -> if (k !in this) put(k, v) } }

    private fun hitRate(c: FamilyCounts): Double? = if (c.calls > 0) c.hits.sum() * 100.0 / c.calls else null

    private fun rate(n: Long, seconds: Double): Double = if (seconds > 0) n / seconds else 0.0

    private fun num(x: Double?): Any = if (x == null || x.isNaN() || x.isInfinite()) JSONObject.NULL else Math.round(x * 10) / 10.0

    /** Thermal headroom: 1.0 is throttling, so two places. */
    private fun num2(x: Double?): Any = if (x == null || x.isNaN() || x.isInfinite()) JSONObject.NULL else Math.round(x * 100) / 100.0

    private fun fmt0(x: Double) = String.format(Locale.US, "%.0f", x)
    private fun fmt1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun fmt2(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun watts(mw: Double?) = mw?.let { "${fmt1(it / 1000)} W" } ?: "-"

    /** 950, 12.3k, 4.5M: counts in a column. */
    private fun count(x: Double): String = when {
        x >= 1e6 -> String.format(Locale.US, "%.1fM", x / 1e6)
        x >= 1e4 -> String.format(Locale.US, "%.1fk", x / 1e3)
        else -> String.format(Locale.US, "%.0f", x)
    }

    private fun onOff(v: Any?): String = when (v) {
        true -> "on"
        false -> "off"
        null -> "-"
        else -> v.toString()
    }
}
