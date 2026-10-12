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
 * The session at one milestone: when (ms since the session was created), the tracer's and the whole
 * tree's CPU time in clock ticks, proot's stop counters and the fast path's counts by program. A
 * value that could not be read is null; the "created" milestone, before proot exists, is all zero.
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
)

/**
 * What the report says about the build, the device and the settings, besides the measurements.
 * [caps] is the capability probe's answer (CapProbe), reported as device.caps.
 */
class PerfInfo(
    val generated: String,
    val build: Map<String, Any?>,
    val device: Map<String, Any?>,
    val toggles: Map<String, Any?>,
    val ticksPerSecond: Long,
    val caps: Caps? = null,
)

/**
 * The Performance report (SessionPerf) from its milestones: `perf-report.json`, everything, and
 * `perf-report.txt`, the dozen numbers a comparison needs, short enough to paste into a chat. Phases
 * are the spans between milestones: startup (created to READY), READY to Big Picture (app 769 has
 * gamescope's focus), idle in Big Picture (to the first game launch, or the end), and for each game
 * launch to focus and in game (to its exit, or the end).
 */
object PerfReport {
    const val SCHEMA = "droiddeck.perf/1"

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
        json.put("milestones", JSONArray().apply {
            snapshots.forEach { s ->
                put(JSONObject().put("label", s.label).put("appId", s.appId ?: JSONObject.NULL)
                    .put("atMs", s.atMs).put("wallMs", s.wallMs)
                    .put("tracerTicks", s.tracerTicks ?: JSONObject.NULL).put("treeTicks", s.treeTicks ?: JSONObject.NULL)
                    .put("treeProcesses", s.treeProcesses ?: JSONObject.NULL)
                    .put("stops", s.stops?.let { stopsJson(it) } ?: JSONObject.NULL)
                    .put("fastPathCalls", s.fastPath.values.sumOf { it.calls }))
            }
        })
        json.put("times", times(snapshots))
        json.put("phases", JSONArray().apply { phases(snapshots).forEach { put(phaseJson(it, info.ticksPerSecond)) } })
        val last = snapshots.lastOrNull()
        if (last != null) {
            val session = JSONObject().put("seconds", num(last.atMs / 1000.0))
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
        }.orEmpty()).joinToString(" · ").ifEmpty { "none reached" })
        line()
        val phases = phases(snapshots)
        if (phases.isNotEmpty()) {
            line(String.format(Locale.US, "%-20s %7s %8s %8s %7s %6s %6s %7s",
                "phase", "secs", "traps/s", "tracer%", "tree%", "execs", "sigsys", "fp hit%"))
            phases.forEach { p ->
                val d = Delta(p, info.ticksPerSecond)
                line(String.format(Locale.US, "%-20s %7s %8s %8s %7s %6s %6s %7s",
                    PHASE_NAMES[p.name] ?: p.name, fmt1(p.seconds), d.stops?.let { count(rate(it, p.seconds)) } ?: "-",
                    d.tracerPct?.let { fmt1(it) } ?: "-", d.treePct?.let { fmt1(it) } ?: "-",
                    d.kind("exec")?.toString() ?: "-", d.kind("sigsys")?.toString() ?: "-",
                    d.fastPath?.let { hitRate(it) }?.let { fmt1(it) } ?: "-"))
            }
            line()
        }
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

    private fun phaseJson(p: Phase, ticksPerSecond: Long): JSONObject {
        val d = Delta(p, ticksPerSecond)
        val json = JSONObject().put("name", p.name).put("from", p.from.label).put("to", p.to.label)
            .put("appId", p.appId ?: JSONObject.NULL).put("seconds", num(p.seconds))
            .put("stops", d.stops ?: JSONObject.NULL)
            .put("stopsPerSecond", d.stops?.let { num(rate(it, p.seconds)) } ?: JSONObject.NULL)
            .put("handleMs", d.handleNs?.let { num(it / 1e6) } ?: JSONObject.NULL)
            .put("tracerCpuS", num(d.tracerCpuS)).put("tracerPct", num(d.tracerPct))
            .put("treeCpuS", num(d.treeCpuS)).put("treePct", num(d.treePct))
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

    private fun hitRate(c: FamilyCounts): Double? = if (c.calls > 0) c.hits.sum() * 100.0 / c.calls else null

    private fun rate(n: Long, seconds: Double): Double = if (seconds > 0) n / seconds else 0.0

    private fun num(x: Double?): Any = if (x == null || x.isNaN() || x.isInfinite()) JSONObject.NULL else Math.round(x * 10) / 10.0

    private fun fmt0(x: Double) = String.format(Locale.US, "%.0f", x)
    private fun fmt1(x: Double) = String.format(Locale.US, "%.1f", x)

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
