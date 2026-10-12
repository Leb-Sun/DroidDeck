package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.gpu.SocNames
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The Performance report (Performance > Performance report, off by default; off, nothing here runs):
 * the baseline the native-environment work is measured against (docs/development/native-env/harness.md).
 *
 * On, proot keeps its stop counters in cache/perf/proot.stats (PROOT_STATS, tools/proot/patches/0015)
 * and every guest process its fast-path hits and misses in cache/perf/fastpath/ (PROOT_FP_STATS_DIR).
 * Both are files their writers map MAP_SHARED, so they are read here whenever a milestone passes,
 * with nothing asked of proot: the session created, proot started, the first frame, READY, Big
 * Picture focused (app 769), each game's launch, focus and exit, and the session's end. The tracer's
 * CPU time and the whole tree's are read with them. perf-report.json and perf-report.txt
 * ([PerfReport]) are rewritten in the session's log folder at each milestone, so Share logs carries
 * them and a session that dies leaves the last one. The raw counter files - one per exec, thousands
 * a session - stay in the cache, out of the logs, and are emptied when the next session starts.
 */
object SessionPerf {
    private const val TAG = "SessionPerf"
    const val REPORT_JSON = "perf-report.json"
    const val REPORT_TEXT = "perf-report.txt"

    @Volatile private var current: Sampler? = null

    /**
     * The report for the session whose folder is [sessionDir], when it is on; null otherwise. On or
     * off, the counter files an earlier session left are deleted first: they must never be summed
     * into a new report, and an earlier session with the report on can leave thousands.
     */
    fun begin(context: Context, sessionDir: File): Sampler? {
        current?.let {
            it.awaitEnd(REPLACED_WAIT_MS)
            it.stop()
        }
        current = null
        File(context.cacheDir, COUNTER_DIR).deleteRecursively()
        if (!SessionPrefs.perfReport(context)) return null
        return try {
            Sampler(context.applicationContext, sessionDir).also { current = it }
        } catch (e: Exception) {
            Log.w(TAG, "performance report not started", e)
            null
        }
    }

    /** Every session event (SessionEvents.record): the milestones are read off them. */
    fun event(event: String, fields: Map<String, Any?>) {
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

    class Sampler internal constructor(private val context: Context, val sessionDir: File) {
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

        // The executor's alone.
        private val snapshots = ArrayList<PerfSnapshot>()
        private var prootPid = -1
        private val seen = HashSet<String>()
        private val games = HashMap<Long, String>()
        private val reapers = HashMap<Int, Long?>()

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
            // Named as `droiddeckctl prefs set session <key>` sets them.
            toggles = linkedMapOf(
                "prootFastPath" to SessionPrefs.prootFastPath(context),
                "prootNoSeccomp" to SessionPrefs.prootNoSeccomp(context),
                "sync" to SessionPrefs.syncBackend(context),
                "gamescopeRealtime" to SessionPrefs.gamescopeRealtime(context),
                "gpuClockPin" to SessionPrefs.gpuClockPin(context),
                "clientCpusOverride" to SessionPrefs.clientCpusOverride(context),
                "mangoapp" to SessionPrefs.mangoapp(context),
            )
            post {
                snapshots += PerfSnapshot(PerfReport.CREATED, null, 0, created, 0, 0, 0, null, emptyMap())
                write()
            }
            executor.scheduleWithFixedDelay({ runCatching { scan() }.onFailure { Log.w(TAG, "scan", it) } },
                SCAN_MS, SCAN_MS, TimeUnit.MILLISECONDS)
        }

        fun event(event: String, fields: Map<String, Any?>) {
            val mono = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            when (event) {
                "guest.started" -> post {
                    prootPid = (fields["pid"] as? Number)?.toInt() ?: -1
                    once(PerfReport.PROOT, mono, wall)
                }
                "frame.first" -> post { once(PerfReport.FIRST_FRAME, mono, wall) }
                "session.ready" -> post { once(PerfReport.READY, mono, wall) }
                "focus.changed" -> post { focus((fields["focusedApp"] as? Number)?.toLong(), mono, wall) }
                // Launches the app asks for; one from the client's own Play button is found by scan().
                "steam.game_launch_requested" -> post { launch(fields["gameId"]?.toString()?.toLongOrNull(), mono, wall) }
                "agent.launch_requested" -> post { launch(fields["appId"]?.toString()?.toLongOrNull(), mono, wall) }
                "session.stopping" -> post {
                    try {
                        record(PerfReport.END, null, mono, wall)
                    } finally {
                        ended.countDown()
                        executor.shutdown()
                    }
                }
            }
        }

        fun awaitEnd(timeoutMs: Long) {
            runCatching { ended.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }

        /** A session replaced before it ended: what is queued still runs, then the thread ends. */
        fun stop() {
            executor.shutdown()
        }

        private fun post(task: () -> Unit) {
            try {
                executor.execute { runCatching(task).onFailure { Log.w(TAG, "performance report", it) } }
            } catch (e: RejectedExecutionException) {
                // The session has ended.
            }
        }

        private fun once(label: String, mono: Long, wall: Long) {
            if (seen.add(label)) record(label, null, mono, wall)
        }

        private fun focus(app: Long?, mono: Long, wall: Long) {
            when {
                app == STEAM_CLIENT_APP -> once(PerfReport.BIG_PICTURE, mono, wall)
                app != null && app > 0 && games[app] == LAUNCHED -> {
                    games[app] = FOCUSED
                    record(PerfReport.FOCUS, app, mono, wall)
                }
            }
        }

        private fun launch(app: Long?, mono: Long, wall: Long) {
            if (app == null || app <= 0 || app == STEAM_CLIENT_APP || games[app] == LAUNCHED || games[app] == FOCUSED) return
            games[app] = LAUNCHED
            record(PerfReport.LAUNCH, app, mono, wall)
        }

        /**
         * Every second: the Steam client starts each game under `reaper SteamLaunch AppId=<n>`, which
         * lives as long as the game. Each new process's command line is read once.
         */
        private fun scan() {
            val pids = File("/proc").list()?.mapNotNullTo(HashSet()) { it.toIntOrNull() } ?: return
            val mono = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val gone = reapers.keys.filter { it !in pids }
            for (pid in gone) {
                val app = reapers.remove(pid) ?: continue
                if (app !in reapers.values && games[app] == FOCUSED) {
                    games[app] = EXITED
                    record(PerfReport.EXIT, app, mono, wall)
                } else if (app !in reapers.values && games[app] == LAUNCHED) {
                    games.remove(app)
                }
            }
            for (pid in pids) {
                if (pid in reapers) continue
                val app = runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull()?.let(PerfCounters::steamLaunchAppId)
                reapers[pid] = app
                if (app != null) launch(app, mono, wall)
            }
        }

        private fun record(label: String, appId: Long?, mono: Long, wall: Long) {
            snapshots += snapshot(label, appId, mono, wall)
            write()
        }

        private fun snapshot(label: String, appId: Long?, mono: Long, wall: Long): PerfSnapshot {
            val stats = HashMap<Int, ProcStat>()
            File("/proc").list()?.forEach { name ->
                val pid = name.toIntOrNull() ?: return@forEach
                runCatching { File("/proc/$pid/stat").readText() }.getOrNull()?.let(PerfCounters::parseStat)?.let { stats[pid] = it }
            }
            val tracer = stats[prootPid]?.let { it.utime + it.stime }
            var tree: Long? = null
            var processes: Int? = null
            if (prootPid > 1 && stats.containsKey(prootPid)) {
                val children = stats.values.groupBy { it.ppid }
                val queue = ArrayDeque(listOf(prootPid))
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
            val stops = if (prootStats.isFile) runCatching { PerfCounters.parseStops(prootStats.readBytes()) }.getOrNull() else null
            val fastPath = HashMap<String, FamilyCounts>()
            fastPathDir.listFiles()?.forEach { f ->
                runCatching { PerfCounters.parseFastPath(f.readBytes()) }.getOrNull()?.let {
                    fastPath.getOrPut(it.name) { FamilyCounts() }.add(FamilyCounts(it.hits, it.misses))
                }
            }
            return PerfSnapshot(label, appId, mono - createdMono, wall, tracer, tree, processes, stops, fastPath)
        }

        private fun write() {
            val info = PerfInfo(
                generated = utc("yyyy-MM-dd HH:mm 'UTC'"),
                build = build, device = device,
                toggles = linkedMapOf<String, Any?>("fastPathActive" to fastPathActive) + toggles,
                ticksPerSecond = ticksPerSecond,
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
