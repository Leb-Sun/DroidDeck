package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.Caps
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.gpu.SocNames
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.DataInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The Performance report (Performance > Performance report, off by default; off, nothing here runs):
 * the baseline the native-environment work is measured against (docs/development/native-env/harness.md).
 *
 * On, proot keeps its stop counters in cache/perf/proot.stats (PROOT_STATS,
 * tools/proot/patches/0016-stop-counters.patch)
 * and every guest process its fast-path hits and misses in cache/perf/fastpath/ (PROOT_FP_STATS_DIR).
 * Both are files their writers map MAP_SHARED, so they are read here whenever a milestone passes,
 * with nothing asked of proot: the session created, proot started, the first frame, READY, Big
 * Picture focused (app 769), each game's launch, focus and exit, and the session's end. The tracer's
 * CPU time and the whole tree's are read with them. perf-report.json and perf-report.txt
 * ([PerfReport]) are rewritten in the session's log folder at each milestone, so Share logs carries
 * them and a session that dies leaves the last one. The raw counter files - one per exec, thousands
 * a session - stay in the cache, out of the logs, and are emptied when the next session starts.
 *
 * Each second, besides, the frames the compositor presented (a frame tap, registered only while a
 * report runs), the battery's power, and every process of the app's uid: who traces it, its memory,
 * and whether it is a phantom (03-design.md §6.1).
 */
object SessionPerf {
    private const val TAG = "SessionPerf"
    const val REPORT_JSON = "perf-report.json"
    const val REPORT_TEXT = "perf-report.txt"

    @Volatile private var current: Sampler? = null
    /** The session [current] replaced: its session.teardown_done may come after the next one began. */
    @Volatile private var previous: Sampler? = null

    /**
     * The native environment's toggles (03-design.md §6.2), by the key `droiddeckctl prefs set
     * session` takes: each is in the report once something has set it, so a later milestone's
     * toggle shows up without the report changing, and one never set is left out.
     */
    val TOGGLE_KEYS = listOf(
        "prootExitKill", "auditScope", "fastPathFolded", "fastPathOpenat2", "fpSetid", "fpFexSyscall",
        "gameStateHints", "adpfHints", "uclampTrial", "gameProot", "patchedGlibc", "nativeClient", "nativeCef",
    )

    /**
     * The report for the session whose folder is [sessionDir], when it is on; null otherwise. On or
     * off, the counter files an earlier session left are deleted first: they must never be summed
     * into a new report, and an earlier session with the report on can leave thousands. [caps], the
     * capability probe's answer (CapProbe), goes into the report as device.caps.
     */
    fun begin(context: Context, sessionDir: File, caps: Caps? = null): Sampler? {
        current?.let {
            it.awaitEnd(REPLACED_WAIT_MS)
            it.stop()
            previous = it
        }
        current = null
        File(context.cacheDir, COUNTER_DIR).deleteRecursively()
        if (!SessionPrefs.perfReport(context)) return null
        return try {
            Sampler(context.applicationContext, sessionDir, caps).also { current = it }
        } catch (e: Exception) {
            Log.w(TAG, "performance report not started", e)
            null
        }
    }

    /** Every session event (SessionEvents.record): the milestones are read off them. */
    fun event(event: String, fields: Map<String, Any?>) {
        // After the stop has finished, into the ended session's folder, which it names: by then a
        // new session may have begun (SessionService.recordTeardownDone).
        if (event == TEARDOWN_DONE) {
            val id = fields["sessionId"]?.toString()
            listOfNotNull(current, previous).firstOrNull { it.sessionDir.name == id }?.event(event, fields)
            return
        }
        current?.event(event, fields)
    }

    /**
     * Before the folder of a session that is ending is finished (SessionArtifacts): the last report,
     * read at the end milestone, must be written before its files are scrubbed.
     */
    fun awaitEnd(sessionDir: File, timeoutMs: Long) {
        current?.takeIf { it.sessionDir == sessionDir }?.awaitEnd(timeoutMs)
    }

    /** The summary of the newest session that wrote one, for the clipboard. */
    fun latestSummary(context: Context): String? =
        SessionPaths.sessionFolders(context).asReversed()
            .firstNotNullOfOrNull { dir -> File(dir, REPORT_TEXT).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } }

    class Sampler internal constructor(private val context: Context, val sessionDir: File, private val caps: Caps?) {
        private val counterDir = File(context.cacheDir, COUNTER_DIR)
        /** PROOT_STATS, in proot's own environment. */
        val prootStats = File(counterDir, "proot.stats")
        /** PROOT_FP_STATS_DIR, in the guest's: the cache is bound at its own path. */
        val fastPathDir = File(counterDir, "fastpath")
        /** Whether this session's proot was given the fast path (ProotFastPath.key). */
        @Volatile var fastPathActive = false

        private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "session-perf").apply { isDaemon = true } }
        private val ended = CountDownLatch(1)
        private val ticksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).takeIf { it > 0 } ?: 100L
        private val createdMono: Long
        private val build: Map<String, Any?>
        private val device: Map<String, Any?>
        private val toggles: Map<String, Any?>
        private val powerManager = context.getSystemService(PowerManager::class.java)
        private val batteryManager = context.getSystemService(BatteryManager::class.java)
        /** The presented frames' times (03-design.md §2.2), drained each second and at each milestone. */
        private val frames = FrameRing()
        private val tap = object : WaylandCompositor.FrameTap {
            override fun onFrame(nanos: Long) = frames.add(nanos)
            override fun onWindowChanged() = frames.cut()
        }
        /** The frame time the guest is paced to ([frameTarget]); 60 Hz until it is known. */
        @Volatile private var frameTargetNs = NS_PER_SECOND / 60
        /** The end milestone has passed: nothing more is recorded, only what comes after the end. */
        @Volatile private var over = false

        // The executor's alone.
        private val snapshots = ArrayList<PerfSnapshot>()
        private var prootPid = -1
        private val seen = HashSet<String>()
        private val games = HashMap<Long, String>()
        private val reapers = HashMap<Int, Long?>()
        /** What the scans and the frame tap saw since the last milestone. */
        private var segment = PerfSamples()
        /** The screen was on and the session running at the last scan: frames since then count. */
        private var live = false
        /** Each tracer counter file's pid, once its header is filled in. */
        private val tracerPids = HashMap<String, Int>()
        private var scanning: ScheduledFuture<*>? = null
        private var lateEnd: ScheduledFuture<*>? = null
        private var powerUnits: Map<String, Any?>? = null
        private var teardownMs: Long? = null
        private var gameStates: MutableList<Map<String, Any?>>? = null
        private var glibc: Map<String, Any?>? = null

        init {
            check(fastPathDir.mkdirs()) { "cannot create ${fastPathDir.path}" }
            val nowWall = System.currentTimeMillis()
            val created = createdAt(nowWall) ?: nowWall
            createdMono = SystemClock.elapsedRealtime() - (nowWall - created)
            build = linkedMapOf(
                "label" to BuildConfig.BUILD_LABEL, "commit" to BuildConfig.SOURCE_COMMIT,
                "package" to context.packageName,
                "version" to runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull(),
                "mode" to SessionState.mode,
            )
            device = linkedMapOf(
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "soc" to runCatching { SocNames.label() }.getOrDefault(""),
                "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
                "kernel" to System.getProperty("os.version"),
                "pageSize" to runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull(),
                "cpus" to CpuCores.all.size,
            )
            // Named as `droiddeckctl prefs set session <key>` sets them: these always, as the app reads
            // them, then every TOGGLE_KEYS one that is set.
            toggles = PerfReport.toggles(linkedMapOf(
                "prootFastPath" to SessionPrefs.prootFastPath(context),
                "prootNoSeccomp" to SessionPrefs.prootNoSeccomp(context),
                "sync" to SessionPrefs.syncBackend(context),
                "gamescopeRealtime" to SessionPrefs.gamescopeRealtime(context),
                "gpuClockPin" to SessionPrefs.gpuClockPin(context),
                "clientCpusOverride" to SessionPrefs.clientCpusOverride(context),
                "mangoapp" to SessionPrefs.mangoapp(context),
            ), SessionPrefs.stored(context, TOGGLE_KEYS).mapValues { (key, value) ->
                when (key) {
                    // GameStateHints acts on it from Android 13 only.
                    "gameStateHints" -> value == true && Build.VERSION.SDK_INT >= 33
                    else -> value
                }
            })
            post {
                snapshots += PerfSnapshot(PerfReport.CREATED, null, 0, created, 0, 0, 0, null, emptyMap())
                write()
            }
            scanning = executor.scheduleWithFixedDelay({ runCatching { scan() }.onFailure { Log.w(TAG, "scan", it) } },
                SCAN_MS, SCAN_MS, TimeUnit.MILLISECONDS)
            // Only while a report runs: off, the compositor has no tap to call.
            try {
                WaylandCompositor.addFrameTap(tap)
            } catch (t: Throwable) {
                Log.w(TAG, "no frame times", t)
            }
        }

        /**
         * BL_FPS and BL_REFRESH, as SessionService gives the guest them: frames aim at the limit when
         * one is set, else at the refresh rate, and an interval over twice that is a stutter.
         */
        fun frameTarget(fpsLimit: Int, refreshHz: Int) {
            val fps = if (fpsLimit > 0) fpsLimit else refreshHz
            if (fps > 0) frameTargetNs = NS_PER_SECOND / fps
        }

        fun event(event: String, fields: Map<String, Any?>) {
            val mono = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val nanos = System.nanoTime()
            when (event) {
                "guest.started" -> post {
                    prootPid = (fields["pid"] as? Number)?.toInt() ?: -1
                    once(PerfReport.PROOT, mono, wall, nanos)
                }
                "frame.first" -> post { once(PerfReport.FIRST_FRAME, mono, wall, nanos) }
                "session.ready" -> post { once(PerfReport.READY, mono, wall, nanos) }
                "focus.changed" -> post { focus((fields["focusedApp"] as? Number)?.toLong(), mono, wall, nanos) }
                // Launches the app asks for; one from the client's own Play button is found by scan().
                "steam.game_launch_requested" -> post { launch(fields["gameId"]?.toString()?.toLongOrNull(), mono, wall, nanos) }
                "agent.launch_requested" -> post { launch(fields["appId"]?.toString()?.toLongOrNull(), mono, wall, nanos) }
                "session.stopping" -> post {
                    try {
                        record(PerfReport.END, null, mono, wall, nanos)
                    } finally {
                        end()
                    }
                }
                // Taken by name, both: the stop's own measure, after the end, and GameStateHints' changes.
                TEARDOWN_DONE -> post {
                    teardownMs = (fields["elapsedMs"] as? Number)?.toLong()
                    write()
                    if (over) {
                        lateEnd?.cancel(false)
                        executor.shutdown()
                    }
                }
                GAME_STATE -> post {
                    val change = linkedMapOf<String, Any?>("atMs" to mono - createdMono)
                    fields.forEach { (k, v) -> if (k != "sessionId") change[k] = v }
                    (gameStates ?: ArrayList<Map<String, Any?>>().also { gameStates = it }).add(change)
                    // The end's own change comes after the end.
                    if (over) write()
                }
            }
        }

        fun awaitEnd(timeoutMs: Long) {
            runCatching { ended.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }

        /**
         * A session replaced. One that ended keeps its thread until its session.teardown_done, or
         * [LATE_MS]; for one that did not, what is queued still runs, then the thread ends.
         */
        fun stop() {
            untap()
            if (!over) executor.shutdown()
        }

        /** The end milestone is written: no more scans or frames, and the thread waits only for what comes after the end. */
        private fun end() {
            over = true
            scanning?.cancel(false)
            untap()
            ended.countDown()
            lateEnd = try {
                executor.schedule({ executor.shutdown() }, LATE_MS, TimeUnit.MILLISECONDS)
            } catch (e: RejectedExecutionException) {
                null
            }
        }

        private fun untap() {
            try {
                WaylandCompositor.removeFrameTap(tap)
            } catch (t: Throwable) {
                // Never added.
            }
        }

        private fun post(task: () -> Unit) {
            try {
                executor.execute { runCatching(task).onFailure { Log.w(TAG, "performance report", it) } }
            } catch (e: RejectedExecutionException) {
                // The session has ended.
            }
        }

        private fun once(label: String, mono: Long, wall: Long, nanos: Long) {
            if (seen.add(label)) record(label, null, mono, wall, nanos)
        }

        private fun focus(app: Long?, mono: Long, wall: Long, nanos: Long) {
            when {
                app == STEAM_CLIENT_APP -> once(PerfReport.BIG_PICTURE, mono, wall, nanos)
                app != null && app > 0 && games[app] == LAUNCHED -> {
                    games[app] = FOCUSED
                    record(PerfReport.FOCUS, app, mono, wall, nanos)
                }
            }
        }

        private fun launch(app: Long?, mono: Long, wall: Long, nanos: Long) {
            if (app == null || app <= 0 || app == STEAM_CLIENT_APP || games[app] == LAUNCHED || games[app] == FOCUSED) return
            games[app] = LAUNCHED
            record(PerfReport.LAUNCH, app, mono, wall, nanos)
        }

        /**
         * Every second: the Steam client starts each game under `reaper SteamLaunch AppId=<n>`, which
         * lives as long as the game. Each new process's command line is read once.
         */
        private fun scan() {
            val pids = File("/proc").list()?.mapNotNullTo(HashSet()) { it.toIntOrNull() } ?: return
            val mono = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val nanos = System.nanoTime()
            val gone = reapers.keys.filter { it !in pids }
            for (pid in gone) {
                val app = reapers.remove(pid) ?: continue
                if (app !in reapers.values && games[app] == FOCUSED) {
                    games[app] = EXITED
                    record(PerfReport.EXIT, app, mono, wall, nanos)
                } else if (app !in reapers.values && games[app] == LAUNCHED) {
                    games.remove(app)
                }
            }
            for (pid in pids) {
                if (pid in reapers) continue
                val app = runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull()?.let(PerfCounters::steamLaunchAppId)
                reapers[pid] = app
                if (app != null) launch(app, mono, wall, nanos)
            }
            sample(pids)
        }

        /**
         * Each second: the frames since the last scan, the battery, and every process of the uid. A
         * frame counts only between two scans that both found the screen on and the session running,
         * so no gap while it was off or suspended passes for a frame time.
         */
        private fun sample(pids: Set<Int>) {
            val on = powerManager?.isInteractive != false && !SessionState.suspended
            frames.drain(System.nanoTime(), if (on && live) segment.frames else null, 2 * frameTargetNs)
            live = on
            runCatching { battery() }.onFailure { Log.w(TAG, "battery", it) }
            processes(pids)
        }

        /** Battery power (PerfCounters.powerMw), with the raw readings it came from. */
        private fun battery() {
            val current = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: return
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val voltage = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val mw = PerfCounters.powerMw(current, voltage) ?: return
            segment.power(mw, plugged, current, voltage)
            powerUnits = linkedMapOf(
                "current" to PerfCounters.currentUnit(current), "voltage" to PerfCounters.voltageUnit(voltage),
                // The raw current's sign while the battery only discharges: -1 is AOSP's convention.
                "dischargeSign" to if (!plugged && current != 0L) java.lang.Long.signum(current) else powerUnits?.get("dischargeSign"),
            )
        }

        /**
         * Every process of the app's uid by its tracer's role (untraced: none), how many are
         * phantoms - anything but the app's own processes, which zygote started - and the memory of
         * the app and of the session: the tracers, what they trace, and every child of either.
         */
        private fun processes(pids: Set<Int>) {
            val uid = Process.myUid()
            val status = HashMap<Int, ProcStatus>()
            for (pid in pids) {
                runCatching { File("/proc/$pid/status").readText() }.getOrNull()?.let(PerfCounters::parseStatus)
                    ?.takeIf { it.uid == uid }?.let { status[pid] = it }
            }
            if (status.isEmpty()) return
            val roles = tracerRoles()
            // A child forked from the app keeps the app's name until it execs, but its parent is ours.
            val apps = status.filter { (pid, s) -> s.ppid !in status && isApp(pid) }.keys
            val traced = HashMap<String, Int>()
            var tracers = 0
            var untraced = 0
            status.forEach { (pid, s) ->
                when {
                    pid in apps -> {}
                    pid in roles -> tracers++
                    s.tracerPid == 0 -> untraced++
                    else -> (roles[s.tracerPid] ?: OTHER_TRACER).let { traced[it] = (traced[it] ?: 0) + 1 }
                }
            }
            segment.processes(apps.size, tracers, traced, untraced, status.size - apps.size)
            val children = status.entries.groupBy({ it.value.ppid }, { it.key })
            // By tracer too: a guest process orphaned to init is still the session's.
            val queue = ArrayDeque(status.filter { (pid, s) -> pid in roles || s.tracerPid in roles }.keys)
            val visited = HashSet<Int>()
            var treeKb = 0L
            while (queue.isNotEmpty()) {
                val pid = queue.removeFirst()
                if (!visited.add(pid)) continue
                treeKb += status[pid]?.rssKb ?: 0L
                children[pid]?.let { queue.addAll(it) }
            }
            segment.memory(status[Process.myPid()]?.rssKb ?: 0L, treeKb)
        }

        private fun isApp(pid: Int): Boolean =
            runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull()?.let { PerfCounters.isAppProcess(it, context.packageName) } == true

        /** Every tracer's pid and role: from its counter file's header, and the session's from guest.started. */
        private fun tracerRoles(): Map<Int, String> {
            val roles = HashMap<Int, String>()
            if (prootPid > 1) roles[prootPid] = SESSION_TRACER
            counterDir.listFiles()?.forEach { f ->
                val role = PerfCounters.tracerRole(f.name) ?: return@forEach
                val pid = tracerPids[f.name] ?: runCatching {
                    DataInputStream(f.inputStream()).use { ByteArray(PerfCounters.STATS_PID_BYTES).apply(it::readFully) }
                }.getOrNull()?.let(PerfCounters::statsPid)?.also { tracerPids[f.name] = it } ?: return@forEach
                roles[pid] = role
            }
            return roles
        }

        private fun record(label: String, appId: Long?, mono: Long, wall: Long, nanos: Long) {
            if (over) return
            snapshots += snapshot(label, appId, mono, wall, nanos)
            write()
        }

        private fun snapshot(label: String, appId: Long?, mono: Long, wall: Long, nanos: Long): PerfSnapshot {
            // The frames up to the milestone close the stretch before it, and no interval spans it.
            frames.drain(nanos, if (live) segment.frames else null, 2 * frameTargetNs)
            frames.restart()
            val samples = segment
            segment = PerfSamples()
            val stats = HashMap<Int, ProcStat>()
            File("/proc").list()?.forEach { name ->
                val pid = name.toIntOrNull() ?: return@forEach
                runCatching { File("/proc/$pid/stat").readText() }.getOrNull()?.let(PerfCounters::parseStat)?.let { stats[pid] = it }
            }
            // Every tracer's counter file: the session's proot.stats, and any other's (PerfCounters.tracerRole).
            val tracers = counterDir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { f ->
                val role = PerfCounters.tracerRole(f.name) ?: return@mapNotNull null
                val stops = runCatching { PerfCounters.parseStops(f.readBytes()) }.getOrNull()
                val pid = stops?.pid?.takeIf { it > 1 } ?: if (role == SESSION_TRACER) prootPid else -1
                TracerRead(f.name, role, pid, stops, stats[pid]?.let { it.utime + it.stime })
            }
            val tracer = stats[prootPid]?.let { it.utime + it.stime }
            var tree: Long? = null
            var processes: Int? = null
            // The tree under every tracer, so a second tracer's processes count as well (03-design.md §6.1).
            val roots = (listOf(prootPid) + tracers.map { it.pid }).filter { it > 1 && stats.containsKey(it) }.distinct()
            if (roots.isNotEmpty()) {
                val children = stats.values.groupBy { it.ppid }
                val queue = ArrayDeque(roots)
                val visited = HashSet<Int>()
                var ticks = 0L
                while (queue.isNotEmpty()) {
                    val pid = queue.removeFirst()
                    if (!visited.add(pid)) continue
                    // A process's own time and that of the children it reaped, which are gone from /proc.
                    stats[pid]?.let { ticks += it.utime + it.stime + it.cutime + it.cstime }
                    children[pid]?.forEach { queue.add(it.pid) }
                }
                tree = ticks
                processes = visited.size
            }
            val stops = tracers.firstOrNull { it.file == prootStats.name }?.stops
            val fastPath = HashMap<String, FamilyCounts>()
            fastPathDir.listFiles()?.forEach { f ->
                runCatching { PerfCounters.parseFastPath(f.readBytes()) }.getOrNull()?.let {
                    fastPath.getOrPut(it.name) { FamilyCounts() }.add(FamilyCounts(it.hits, it.misses))
                }
            }
            // At milestones only: Android answers NaN when asked more than about once a second.
            val headroom = if (Build.VERSION.SDK_INT >= 30) {
                runCatching { powerManager?.getThermalHeadroom(THERMAL_FORECAST_S)?.toDouble() }.getOrNull()?.takeIf { !it.isNaN() }
            } else null
            glibc = PerfCounters.glibcState(runCatching {
                File(LinuxRuntime.rootDir(context), GLIBC_STATE).takeIf { it.isFile }?.readText()
            }.getOrNull())
            return PerfSnapshot(label, appId, mono - createdMono, wall, tracer, tree, processes, stops, fastPath,
                tracers, samples, headroom)
        }

        private fun write() {
            val info = PerfInfo(
                generated = utc("yyyy-MM-dd HH:mm 'UTC'"),
                build = build, device = device,
                toggles = linkedMapOf<String, Any?>("fastPathActive" to fastPathActive) + toggles,
                ticksPerSecond = ticksPerSecond, caps = caps,
                frameTargetNs = frameTargetNs, powerUnits = powerUnits, teardownMs = teardownMs,
                gameStates = gameStates?.toList(), glibc = glibc,
            )
            replace(File(sessionDir, REPORT_JSON), PerfReport.build(info, snapshots).toString(1))
            replace(File(sessionDir, REPORT_TEXT), PerfReport.summary(info, snapshots))
        }

        private fun replace(target: File, text: String) {
            try {
                val staged = File(target.parentFile, ".${target.name}.tmp")
                staged.writeText(text)
                if (!staged.renameTo(target)) staged.delete()
            } catch (e: Exception) {
                Log.w(TAG, "writing ${target.name}", e)
            }
        }

        /** When the session was created: the first event in its folder (SessionEvents.begin), if recent. */
        private fun createdAt(now: Long): Long? = runCatching {
            File(sessionDir, "events.jsonl").bufferedReader().use { it.readLine() }
                ?.let { org.json.JSONObject(it).optLong("t", 0L) }
        }.getOrNull()?.takeIf { it in (now - CREATED_WINDOW_MS)..now }

        private fun utc(pattern: String): String =
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }

    /** Under the cache, which the guest sees at the same path; never in the logs. */
    private const val COUNTER_DIR = "perf"
    private const val TEARDOWN_DONE = "session.teardown_done"
    private const val GAME_STATE = "hints.game_state"
    /** How long a session's thread outlives its end for session.teardown_done, which the stop sends once it has finished. */
    private const val LATE_MS = 60_000L
    private const val NS_PER_SECOND = 1_000_000_000L
    private const val THERMAL_FORECAST_S = 10
    /** The rootfs's glibc, as the patched glibc's staging leaves it (03-design.md §4.2). */
    private const val GLIBC_STATE = "etc/droiddeck/glibc-state"
    private const val SESSION_TRACER = "session"
    /** A tracer with no counter file: a terminal's proot, or a debugger. */
    private const val OTHER_TRACER = "other"
    /** A session replaced while it ends (the desktop's Steam launchers): its last read comes first. */
    private const val REPLACED_WAIT_MS = 2_000L
    private const val STEAM_CLIENT_APP = 769L
    private const val SCAN_MS = 1000L
    /** A first event older than this belongs to a folder claimed long before the session started. */
    private const val CREATED_WINDOW_MS = 30 * 60 * 1000L
    private const val LAUNCHED = "launched"
    private const val FOCUSED = "focused"
    private const val EXITED = "exited"
}
