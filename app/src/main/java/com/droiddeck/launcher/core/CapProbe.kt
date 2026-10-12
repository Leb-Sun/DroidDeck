package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One test of the capability probe, as its line in the report says (hostsys/capprobe.h). */
data class CapResult(
    val name: String,
    /** [CapProbe.ALLOWED], [CapProbe.BLOCKED] (the filter's SIGSYS killed it) or [CapProbe.ERROR]. */
    val state: String,
    /** What the call returned, or -1 with [errno]; capprobe.c says what a few tests make of theirs. */
    val ret: Long = 0,
    val errno: Int = 0,
    /** The call the test was on: 0 its set-up, 1 the call under test, 2.. what follows it. */
    val step: Int = 0,
    /** The signal an errored test died of; 0 for none. */
    val signal: Int = 0,
)

/**
 * What one probe found. [status] is "ok", or why it could not tell, and then nothing it reports is
 * acted on. [device] is what it ran on; [report] is the native text it was read from, which the
 * cache keeps.
 */
class Caps(
    val status: String,
    val results: List<CapResult>,
    val device: Map<String, Any?>,
    val probeMs: Long,
    val report: String?,
    val cached: Boolean = false,
) {
    val ok: Boolean get() = status == CapProbe.OK

    /** BL_NATIVE_BLOCK; null (unset, the glibc's safe worst case) when the probe did not succeed. */
    val nativeBlock: Int? get() = if (ok) CapProbe.blockMask(results) else null

    /** BL_CAPS: every test that came back usable, in the probe's order. */
    val extras: List<String> get() = if (ok) results.filter(CapProbe::usable).map { it.name } else emptyList()

    fun result(name: String): CapResult? = results.firstOrNull { it.name == name }
}

/**
 * The capability probe (docs/development/native-env/03-design.md S3.3): which of the calls
 * Android's releases differ on this device's app seccomp filter lets through, and what the kernel
 * then does with them. Only this process can ask: adb does not run under the app's filter. The
 * answers reach
 *  - the guest, as BL_NATIVE_BLOCK (the patched glibc's gate, tools/native/glibc-patches/0001;
 *    stock glibc ignores it) and BL_CAPS;
 *  - the session's app.log, one line, and the Performance report, as device.caps;
 *  - `droiddeckctl state`, as caps.
 * The probe forks this process (HostSys), so it runs once a boot and build: the result is kept in
 * files/caps.json, keyed on the boot id, the system's build fingerprint and the version code, and
 * every later session start reads it from there.
 */
object CapProbe {
    private const val TAG = "CapProbe"
    const val SCHEMA = "droiddeck.caps/1"
    const val OK = "ok"
    const val ALLOWED = "allowed"
    const val BLOCKED = "blocked"
    const val ERROR = "error"

    private const val CACHE = "caps.json"
    /** Under the cache: the files two of the tests use (prepare). */
    private const val PROBE_DIR = "capprobe"
    /** Counted once the helper is forked; the tests take a few milliseconds between them. */
    private const val TIMEOUT_MS = 100
    /** What the uclamp test asks for (capprobe.c, CP_UCLAMP_MIN). */
    private const val UCLAMP_MIN = 512L

    /**
     * BL_NATIVE_BLOCK's bits, as bl-seccomp.h defines them: a set bit means "never issue it, the
     * filter traps it". 0x8 is close_range (blocked up to Android 12), for the planned glibc patch 0007
     * (03-design.md S4.1).
     */
    val BLOCK_BITS: Map<String, Int> = linkedMapOf("clone3" to 0x1, "faccessat2" to 0x2, "fchmodat2" to 0x4, "close_range" to 0x8)

    /** The summary's first line: the calls Android's releases differ on, then uclamp. */
    private val HEADLINE = listOf("openat2", "faccessat2", "fchmodat2", "clone3", "close_range", "landlock")
    private const val UCLAMP = "uclamp"

    // Linux's numbers, the same on arm64 and x86_64 (asm-generic).
    private const val EINVAL = 22
    private const val ENOSYS = 38
    private const val EOPNOTSUPP = 95
    private val ERRNO_NAMES = mapOf(
        1 to "EPERM", 2 to "ENOENT", 3 to "ESRCH", 7 to "E2BIG", 9 to "EBADF", 11 to "EAGAIN",
        12 to "ENOMEM", 13 to "EACCES", 14 to "EFAULT", 17 to "EEXIST", 18 to "EXDEV", EINVAL to "EINVAL",
        ENOSYS to "ENOSYS", 40 to "ELOOP", EOPNOTSUPP to "EOPNOTSUPP",
    )
    private val DEVICE_KEYS = listOf("pageSize", "kernel", "android", "sdk", "securityPatch", "fingerprint", "versionCode")

    /**
     * This boot's answers: the kept ones when they were taken in this boot, by this build, on this
     * system; otherwise a new probe, which costs a fork of this process and about twenty
     * short-lived children. Blocking, so off the main thread. Never throws: a probe that could not
     * run says why in its status.
     */
    @Synchronized
    fun get(context: Context): Caps {
        val cache = File(context.filesDir, CACHE)
        val bootId = bootId()
        runCatching { read(cache, bootId, Build.FINGERPRINT, BuildConfig.VERSION_CODE) }
            .onFailure { Log.w(TAG, "reading ${cache.name}", it) }
            .getOrNull()?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val dir = File(context.cacheDir, PROBE_DIR)
        runCatching { prepare(dir) }.onFailure { Log.w(TAG, "preparing ${dir.path}; the tests that use it will say so", it) }
        val report = HostSys.probe(dir, TIMEOUT_MS)
        val caps = fromReport(report, deviceFacts(), SystemClock.elapsedRealtime() - started)
        // Kept whatever it found, so a probe that fails costs one session start a boot, not each one.
        if (report != null && bootId != null) write(cache, caps, bootId)
        return caps
    }

    /** What this boot's probe found, without probing; null before the first session since boot. */
    fun cached(context: Context): Caps? =
        runCatching { read(File(context.filesDir, CACHE), bootId(), Build.FINGERPRINT, BuildConfig.VERSION_CODE) }.getOrNull()

    /** The guest's BL_NATIVE_BLOCK and BL_CAPS; nothing when the probe failed, which the glibc reads as its worst case. */
    fun guestEnv(caps: Caps): List<String> {
        val block = caps.nativeBlock ?: return emptyList()
        return listOf("BL_NATIVE_BLOCK=" + Integer.toHexString(block), "BL_CAPS=" + caps.extras.joinToString(","))
    }

    /** A probe's [report] (null: the library did not load, nothing ran) as its results. */
    fun fromReport(report: String?, device: Map<String, Any?>, probeMs: Long): Caps {
        if (report == null) return Caps("not run: no native library", emptyList(), device, probeMs, null)
        val (status, results) = parse(report)
        // Every Android allows setresuid: a probe that reads it any other way is not reading the filter.
        val trusted = status != OK || results.any { it.name == "setresuid" && it.state == ALLOWED }
        return Caps(if (trusted) status else "untrusted: setresuid not allowed", results, device, probeMs, report)
    }

    /** The native report (capprobe.h): its status line, then one result per test. */
    fun parse(report: String): Pair<String, List<CapResult>> {
        val lines = report.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val head = lines.firstOrNull()?.split(' ', limit = 3).orEmpty()
        val status = if (head.size == 3 && head[0] == "capprobe" && head[1] == "1") head[2] else "unreadable report"
        val results = lines.drop(1).mapNotNull { line ->
            val words = line.split(' ')
            if (words.size < 2 || words[1] !in setOf(ALLOWED, BLOCKED, ERROR)) return@mapNotNull null
            val fields = words.drop(2).mapNotNull { w -> w.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { it[0] to it[1] }
            CapResult(
                words[0], words[1], fields["ret"]?.toLongOrNull() ?: 0, fields["errno"]?.toIntOrNull() ?: 0,
                fields["step"]?.toIntOrNull() ?: 0, fields["signal"]?.toIntOrNull() ?: 0,
            )
        }
        return status to results
    }

    /** Whether a test's answer means the thing can be used here, not only that the filter let the call through. */
    fun usable(r: CapResult): Boolean {
        if (r.state != ALLOWED) return false
        return when (r.name) {
            "openat2" -> r.ret >= 0
            "resolve_in_root", "fp_stub" -> r.ret == 1L
            "faccessat2", "setresuid", "execmem", "exec_data" -> r.ret == 0L
            "fchmodat2" -> r.ret == 0L || r.errno == EOPNOTSUPP
            "close_range" -> r.ret == 0L || r.errno == EINVAL
            "landlock" -> r.ret >= 1
            UCLAMP -> r.ret == UCLAMP_MIN
            // The rest are made to fail (a NULL, an empty argument): reaching the kernel is the answer.
            else -> r.errno != ENOSYS
        }
    }

    /** BL_NATIVE_BLOCK from [results]: a bit for each gated call the filter did not plainly let through. */
    fun blockMask(results: List<CapResult>): Int = BLOCK_BITS.entries.fold(0) { mask, (name, bit) ->
        if (results.any { it.name == name && it.state == ALLOWED }) mask else mask or bit
    }

    /**
     * Two lines for perf-report.txt, the first also the session log's:
     *
     *     seccomp: openat2 ok, faccessat2 ok, ..., landlock ok; uclamp ok · BL_NATIVE_BLOCK=0 · probed in 21 ms
     *     resolve_in_root ok, fp_stub ok, ... · blocked: rseq, set_robust_list, ...
     *
     * "ok" is usable, "blocked" the filter's SIGSYS, an errno name a call it let through that failed
     * (ENOSYS: the kernel has no such call), "error" a test that could not tell.
     */
    fun summary(caps: Caps): List<String> {
        if (!caps.ok) return listOf("probe failed (${caps.status}): BL_NATIVE_BLOCK unset")
        val head = "seccomp: " + HEADLINE.mapNotNull { n -> caps.result(n)?.let { "$n ${word(it)}" } }.joinToString(", ") +
            (caps.result(UCLAMP)?.let { "; uclamp ${word(it)}" } ?: "") +
            " · BL_NATIVE_BLOCK=" + Integer.toHexString(caps.nativeBlock ?: 0) +
            " · " + (if (caps.cached) "cached" else "probed in ${caps.probeMs} ms")
        val rest = caps.results.filter { it.name !in HEADLINE && it.name != UCLAMP }
        val blocked = rest.filter { it.state == BLOCKED }.map { it.name }
        val tail = listOfNotNull(
            rest.filter { it.state != BLOCKED }.joinToString(", ") { "${it.name} ${word(it)}" }.ifEmpty { null },
            blocked.takeIf { it.isNotEmpty() }?.let { "blocked: " + it.joinToString(", ") },
        ).joinToString(" · ")
        return listOf(head, tail)
    }

    /** device.caps in the report, and caps in `droiddeckctl state`. */
    fun toJson(caps: Caps): JSONObject {
        val json = JSONObject().put("schema", SCHEMA).put("probe", caps.status)
            .put("probeMs", caps.probeMs).put("cached", caps.cached)
        caps.device.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
        json.put("nativeBlock", caps.nativeBlock?.let { Integer.toHexString(it) } ?: JSONObject.NULL)
        json.put("extras", JSONArray(caps.extras))
        json.put("tests", JSONObject().apply { caps.results.forEach { put(it.name, resultJson(it)) } })
        return json
    }

    /** The probe kept in [file], if it was taken in boot [bootId] by build [versionCode] on system [fingerprint]. */
    internal fun read(file: File, bootId: String?, fingerprint: String, versionCode: Int): Caps? {
        if (bootId == null || !file.isFile) return null
        val json = JSONObject(file.readText())
        if (json.optString("schema") != SCHEMA || json.optString("bootId") != bootId ||
            json.optString("fingerprint") != fingerprint || json.optInt("versionCode", -1) != versionCode
        ) return null
        val report = json.opt("report") as? String ?: return null
        val device = DEVICE_KEYS.associateWithTo(LinkedHashMap<String, Any?>()) { k -> json.opt(k)?.takeIf { it != JSONObject.NULL } }
        return Caps(json.getString("probe"), parse(report).second, device, json.optLong("probeMs"), report, cached = true)
    }

    internal fun write(file: File, caps: Caps, bootId: String) {
        try {
            val json = toJson(caps).put("bootId", bootId).put("report", caps.report ?: JSONObject.NULL)
            val staged = File(file.parentFile, ".${file.name}.tmp")
            staged.writeText(json.toString(1))
            if (!staged.renameTo(file)) staged.delete()
        } catch (e: Exception) {
            Log.w(TAG, "writing ${file.name}", e)
        }
    }

    private fun resultJson(r: CapResult): JSONObject {
        val json = JSONObject().put("state", r.state)
        if (r.state == ALLOWED) json.put("ret", r.ret).put("usable", usable(r))
        if (r.errno != 0) json.put("errno", errnoName(r.errno))
        if (r.signal != 0) json.put("signal", r.signal)
        return json.put("step", r.step)
    }

    private fun word(r: CapResult): String = when {
        usable(r) -> "ok"
        r.state == BLOCKED -> "blocked"
        r.state == ERROR -> "error"
        r.ret < 0 -> errnoName(r.errno)
        else -> "returned ${r.ret}"
    }

    private fun errnoName(n: Int): String = ERRNO_NAMES[n] ?: "errno $n"

    /** cache/capprobe: "file", and "root" holding "inside" and the symlink "link" -> "/inside". */
    private fun prepare(dir: File) {
        val root = File(dir, "root")
        root.mkdirs()
        File(dir, "file").takeIf { !it.isFile }?.writeText("capprobe\n")
        File(root, "inside").takeIf { !it.isFile }?.writeText("")
        val link = File(root, "link")
        if (runCatching { Os.readlink(link.path) }.getOrNull() != "/inside") {
            link.delete()
            Os.symlink("/inside", link.path)
        }
    }

    /** This boot's id; null where it cannot be read, and then nothing is kept. */
    private fun bootId(): String? =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun deviceFacts(): Map<String, Any?> = linkedMapOf(
        "pageSize" to runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull(),
        "kernel" to runCatching { Os.uname().release }.getOrNull(),
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
        "securityPatch" to Build.VERSION.SECURITY_PATCH,
        "fingerprint" to Build.FINGERPRINT,
        "versionCode" to BuildConfig.VERSION_CODE,
    )
}
