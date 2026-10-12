package com.droiddeck.launcher.session

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Performance > Patched glibc, layer 3 of the native environment (docs/development/native-env/
 * 03-design.md §4.3-4.4): the rootfs's own glibc rebuilt by tools/glibc, carried in the apk as
 * assets/linuxfs/glibc/<version>/<flavor>/ beside its manifest, and staged over the stock files at
 * session start.
 *
 *  - The rootfs's glibc version is its pacman entry's. Nothing is staged unless every file the
 *    overlay replaces still hashes to the stock package's own (the manifest's stock column).
 *  - A flavor's file is renamed over the stock one, which is kept beside it as
 *    `<name>.droiddeck-stock`: a copy, checked against the stock hash, with the original's mode and
 *    time. Renames only, so a process that still maps a file keeps the one it opened, the rule
 *    SessionFiles follows; the path is never missing.
 *  - Off, each kept copy is renamed back, so the stock files return byte for byte.
 *  - What was staged is recorded in [LEDGER] (the manifest it came from) before the first rename, so
 *    it can be undone whatever apk is installed later and whatever pacman did meanwhile.
 *  - [STATE] says what the rootfs runs: `patched <version>`, `rebuild <version>`, `stock <version>`,
 *    or `mismatch <version>`: nothing staged, because the files are not the stock package's or this
 *    apk has no build for that version. pacman's hook (droiddeck-glibc-state) writes `mismatch` when
 *    it replaces glibc mid-session. Native mode acts on `patched` only.
 *  - Two sessions in a row that fail before READY with the overlay staged put stock back and turn the
 *    toggle off (`glibc.reverted`).
 */
object NativeGlibc {
    private const val TAG = "NativeGlibc"
    const val ASSETS = "linuxfs/glibc"
    const val STATE = "etc/droiddeck/glibc-state"
    const val LEDGER = "etc/droiddeck/glibc-staged"
    const val STOCK_SUFFIX = ".droiddeck-stock"
    private const val NEW_SUFFIX = ".droiddeck-new"
    private const val PACMAN_DB = "var/lib/pacman/local"
    const val PATCHED = "patched"
    const val REBUILD = "rebuild"
    const val STOCK = "stock"
    const val MISMATCH = "mismatch"
    val FLAVORS = listOf(PATCHED, REBUILD)
    const val EVENT_STATE = "glibc.state"
    const val EVENT_REVERTED = "glibc.reverted"
    /** Sessions in a row that fail before READY with the overlay staged, after which it is turned off. */
    const val MAX_FAILED_STARTS = 2
    private const val PREFS = "native-glibc"
    private const val PENDING = "pendingStart"
    private const val FAILED = "failedStarts"
    /** Any execute bit (0111). */
    private const val EXEC_BITS = 0x49

    /** One file of the overlay: its path in the rootfs, the stock file's mode and size, and three sha256s. */
    class Entry(
        val path: String,
        val mode: Int,
        val stockSize: Long,
        val stock: String,
        val rebuild: String,
        val patched: String,
    ) {
        fun hash(flavor: String): String = if (flavor == PATCHED) patched else rebuild

        /** Whether [sha] is one of the flavors' files and not the stock one. */
        fun ours(sha: String?): Boolean = sha != null && sha != stock && (sha == rebuild || sha == patched)
    }

    /** tools/glibc/manifest.py's format; [text] is kept whole, as the ledger is that text. */
    class Manifest(val version: String, val entries: List<Entry>, val text: String) {
        companion object {
            const val MAGIC = "droiddeck-glibc-manifest 1"
            private val HEX64 = Regex("[0-9a-f]{64}")
            private val MODE = Regex("[0-7]{1,4}")

            /** The manifest in [text], or null when it is not one or any of its file lines is malformed. */
            fun parse(text: String): Manifest? {
                val lines = text.lines()
                if (lines.firstOrNull() != MAGIC) return null
                var version: String? = null
                val entries = ArrayList<Entry>()
                val seen = HashSet<String>()
                for (line in lines.drop(1)) {
                    val key = line.substringBefore(' ')
                    val rest = line.substringAfter(' ', "")
                    when (key) {
                        "version" -> version = rest.takeIf { it.isNotEmpty() && '/' !in it && it != "." && it != ".." }
                        "file" -> {
                            val w = rest.split(' ')
                            if (w.size != 6 || !MODE.matches(w[0]) || w[1].toLongOrNull()?.takeIf { it >= 0 } == null ||
                                !w.subList(2, 5).all { HEX64.matches(it) } || !safePath(w[5]) || !seen.add(w[5])
                            ) return null
                            entries += Entry(w[5], w[0].toInt(8), w[1].toLong(), w[2], w[3], w[4])
                        }
                    }
                }
                if (version == null || entries.isEmpty()) return null
                return Manifest(version, entries, text)
            }

            private fun safePath(path: String): Boolean =
                path.isNotEmpty() && !path.startsWith("/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
        }
    }

    /** Where the flavors come from: the apk's assets, or a test's directory. */
    interface Overlay {
        /** The manifest text for [version], or null when there is no build for it. */
        fun manifest(version: String): String?
        fun open(version: String, flavor: String, path: String): InputStream
    }

    /**
     * What staging left: the [state] word, the rootfs's [version], how many files were renamed into
     * place and how many stock files put back, and why when it is not what was asked for.
     */
    class Result(val state: String, val version: String?, val staged: Int, val restored: Int, val note: String?) {
        val line: String get() = "$state ${version ?: "unknown"}"
    }

    private class Undo(val restored: Int, val left: Int)

    private enum class Action { KEEP, INSTALL, SWITCH }

    @Volatile private var app: Context? = null

    /**
     * At session start (SessionFiles.stage), before the guest exists: the toggle's choice made true in
     * [root], the automatic rollback, and the events. Never throws.
     */
    fun stage(context: Context, root: File) = stage(context, root, AssetOverlay(context.applicationContext))

    internal fun stage(context: Context, root: File, overlay: Overlay) {
        try {
            val app = context.applicationContext ?: context
            this.app = app
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // A session left pending never reached READY, nor ended: the app died on the way.
            var failed = prefs.getInt(FAILED, 0) + if (prefs.getBoolean(PENDING, false)) 1 else 0
            var on = SessionPrefs.patchedGlibc(app)
            val flavor = SessionPrefs.glibcFlavor(app)
            val revert = on && failed >= MAX_FAILED_STARTS
            if (revert) {
                SessionPrefs.setPatchedGlibc(app, false)
                on = false
            }
            val started = SystemClock.elapsedRealtime()
            val result = apply(root, overlay, on, flavor)
            val ms = SystemClock.elapsedRealtime() - started
            val staged = result.state == PATCHED || result.state == REBUILD
            prefs.edit().putInt(FAILED, if (on) failed else 0).putBoolean(PENDING, staged).commit()
            if (revert) {
                Log.w(TAG, "$failed sessions in a row failed before READY with glibc's $flavor build: stock is back, the toggle is off")
                SessionEvents.record(EVENT_REVERTED, mapOf(
                    "failedStarts" to failed, "flavor" to flavor, "state" to result.state, "version" to result.version,
                ))
            }
            SessionEvents.record(EVENT_STATE, mapOf(
                "state" to result.state, "version" to result.version, "flavor" to if (on) flavor else null,
                "staged" to result.staged, "restored" to result.restored, "ms" to ms, "note" to result.note,
            ))
            Log.i(TAG, "glibc: ${result.line} (staged ${result.staged}, restored ${result.restored}, $ms ms)" +
                (result.note?.let { ": $it" } ?: ""))
        } catch (e: Exception) {
            Log.e(TAG, "glibc staging", e)
        }
    }

    /**
     * Every session event (SessionEvents.record). A session the overlay was staged for that reaches
     * READY clears the count of failed starts; one that fails first adds to it. A stop before READY
     * that is not a failure counts neither way.
     */
    fun event(event: String) {
        if (event != "session.ready" && event != "session.failed" && event != "session.stopped") return
        val context = app ?: return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PENDING, false)) return
            val edit = prefs.edit().putBoolean(PENDING, false)
            when (event) {
                "session.ready" -> edit.putInt(FAILED, 0)
                "session.failed" -> edit.putInt(FAILED, prefs.getInt(FAILED, 0) + 1)
            }
            edit.commit()
        } catch (e: Exception) {
            Log.w(TAG, "glibc start count after $event", e)
        }
    }

    /** Failed starts counted toward the rollback, for the device report. */
    fun failedStarts(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(FAILED, 0)

    /** The state file's line, or null before any session wrote one. */
    fun readState(root: File): String? = readText(File(root, STATE))?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The state machine, on [root] as it is now: stage [flavor] when [on], put stock back when not,
     * and write [STATE].
     */
    internal fun apply(root: File, overlay: Overlay, on: Boolean, flavor: String): Result {
        val version = installedVersion(root)
        val ledger = readText(File(root, LEDGER))?.let { Manifest.parse(it) }
        val manifest = if (on && version != null && flavor in FLAVORS)
            overlay.manifest(version)?.let { Manifest.parse(it) }?.takeIf { it.version == version } else null
        var restored = 0
        // What an earlier session staged from another manifest (another apk, another glibc) comes off
        // first: only that manifest knows the hashes it put there.
        if (ledger != null && (manifest == null || ledger.text != manifest.text)) {
            val undo = restore(root, ledger, version)
            restored += undo.restored
            if (undo.left > 0) return finish(root, Result(MISMATCH, version, 0, restored, "${undo.left} staged files could not be put back"))
        }
        if (version == null) return finish(root, Result(MISMATCH, null, 0, restored, "no single glibc package in the rootfs"))
        if (!on) return finish(root, Result(STOCK, version, 0, restored, null))
        if (flavor !in FLAVORS) return finish(root, Result(MISMATCH, version, 0, restored, "unknown flavor $flavor"))
        if (manifest == null) return finish(root, Result(MISMATCH, version, 0, restored, "this apk carries no glibc build for $version"))

        for (e in manifest.entries) {
            File(root, e.path + NEW_SUFFIX).delete()
            File(root, e.path + STOCK_SUFFIX + NEW_SUFFIX).delete()
        }
        val plan = ArrayList<Pair<Entry, Action>>(manifest.entries.size)
        for (e in manifest.entries) {
            val file = File(root, e.path)
            val target = e.hash(flavor)
            val current = regularSha(file)
            val action = when {
                current == null -> null
                current == target && target == e.stock -> Action.KEEP
                current == target -> if (kept(root, e)) Action.KEEP else null
                current == e.stock -> Action.INSTALL
                e.ours(current) -> if (kept(root, e)) Action.SWITCH else null
                else -> null
            }
            if (action == null) {
                val undo = if (ledger != null) restore(root, ledger, version) else Undo(0, 0)
                return finish(root, Result(MISMATCH, version, 0, restored + undo.restored,
                    "${e.path} is neither the stock package's nor this build's"))
            }
            plan += e to action
        }
        val work = plan.filter { it.second != Action.KEEP }
        if (work.isEmpty()) return finish(root, Result(flavor, version, 0, restored, null))
        // The ledger first: from here on, whatever is in place can be found and undone.
        if (ledger?.text != manifest.text && !writeAtomic(File(root, LEDGER), manifest.text)) {
            return finish(root, Result(if (ledger == null) STOCK else MISMATCH, version, 0, restored, "could not write the ledger"))
        }
        val failure = try {
            // Everything is written and checked beside its target before the first rename, so the
            // renames follow one another with nothing in between that can fail half-way.
            for ((e, action) in work) {
                val fresh = File(root, e.path + NEW_SUFFIX)
                val ok = overlay.open(manifest.version, flavor, e.path).use { copyChecked(it, fresh, e.hash(flavor)) }
                if (!ok || !setMode(fresh, e.mode)) throw IOException("${e.path}: the $flavor file did not come out as its manifest says")
                if (action == Action.INSTALL) keepStock(root, e)
            }
            for ((e, _) in work) {
                if (!File(root, e.path + NEW_SUFFIX).renameTo(File(root, e.path))) throw IOException("could not rename ${e.path} into place")
            }
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (failure != null) {
            for ((e, _) in work) File(root, e.path + NEW_SUFFIX).delete()
            val undo = restore(root, manifest, version)
            return finish(root, Result(if (undo.left == 0) STOCK else MISMATCH, version, 0, restored + undo.restored, failure))
        }
        return finish(root, Result(flavor, version, work.size, restored, null))
    }

    /**
     * Puts back every stock file [ledger] says was staged over, from its kept copy, and drops copies
     * no longer needed. [version] is the rootfs's glibc now: when pacman has replaced the package,
     * the copies are of the old one and go. A file that is neither stock nor ours keeps its copy, and
     * one of ours whose copy is gone stays on record: the ledger stays, so nothing is lost or forgotten.
     */
    private fun restore(root: File, ledger: Manifest, version: String?): Undo {
        var restored = 0
        var left = 0
        for (e in ledger.entries) {
            File(root, e.path + NEW_SUFFIX).delete()
            File(root, e.path + STOCK_SUFFIX + NEW_SUFFIX).delete()
            val file = File(root, e.path)
            val kept = File(root, e.path + STOCK_SUFFIX)
            val current = regularSha(file)
            if (!kept.exists() && !isLink(kept)) {
                if (e.ours(current)) left++
                continue
            }
            val copy = regularSha(kept)
            when {
                current == e.stock -> kept.delete()
                copy == e.stock && (current == null || e.ours(current)) -> if (kept.renameTo(file)) restored++ else left++
                copy == e.stock && version != null && version != ledger.version -> kept.delete()
                else -> left++
            }
        }
        if (left == 0) File(root, LEDGER).delete()
        return Undo(restored, left)
    }

    /** The stock copy of [e] is there, by its size; its hash is checked when it is put back. */
    private fun kept(root: File, e: Entry): Boolean {
        val kept = File(root, e.path + STOCK_SUFFIX)
        return kept.isFile && !isLink(kept) && kept.length() == e.stockSize
    }

    /** Copies the stock file of [e] to `<name>.droiddeck-stock`, checked, with its mode and time. */
    private fun keepStock(root: File, e: Entry) {
        val file = File(root, e.path)
        val kept = File(root, e.path + STOCK_SUFFIX)
        if (regularSha(kept) == e.stock) return
        val fresh = File(root, e.path + STOCK_SUFFIX + NEW_SUFFIX)
        val ok = file.inputStream().use { copyChecked(it, fresh, e.stock) }
        if (!ok || !setMode(fresh, e.mode) || !fresh.setLastModified(file.lastModified()) || !fresh.renameTo(kept)) {
            fresh.delete()
            throw IOException("could not keep the stock ${e.path}")
        }
    }

    private fun finish(root: File, result: Result): Result {
        val state = File(root, STATE)
        if (readText(state) != result.line + "\n" && !writeAtomic(state, result.line + "\n")) {
            Log.w(TAG, "could not write $STATE")
        }
        return result
    }

    /** The glibc package's version from the rootfs's pacman database, or null unless there is exactly one. */
    fun installedVersion(root: File): String? {
        val dirs = File(root, PACMAN_DB).listFiles { f -> f.isDirectory && f.name.startsWith("glibc-") && f.name.getOrNull(6)?.isDigit() == true }
        val versions = dirs.orEmpty().mapNotNull { dir ->
            val lines = readText(File(dir, "desc"))?.lines() ?: return@mapNotNull null
            fun field(name: String) = lines.indexOf("%$name%").takeIf { it >= 0 }?.let { lines.getOrNull(it + 1) }
            field("VERSION")?.takeIf { field("NAME") == "glibc" && it.isNotEmpty() }
        }.distinct()
        return versions.singleOrNull()
    }

    private fun isLink(file: File): Boolean = try { Files.isSymbolicLink(file.toPath()) } catch (e: Exception) { false }

    /** sha256 of a regular file that is not a symlink; null for anything else or when it cannot be read. */
    private fun regularSha(file: File): String? {
        if (isLink(file) || !file.isFile) return null
        return try { file.inputStream().use { sha256(it, null) } } catch (e: IOException) { null }
    }

    /** Writes [input] to [dest], synced to disk, and says whether its sha256 is [expect]. */
    private fun copyChecked(input: InputStream, dest: File, expect: String): Boolean {
        dest.parentFile?.mkdirs()
        val sha = FileOutputStream(dest).use { out ->
            val sha = sha256(input, out)
            out.fd.sync()
            sha
        }
        if (sha != expect) dest.delete()
        return sha == expect
    }

    private fun sha256(input: InputStream, copyTo: FileOutputStream?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            copyTo?.write(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** rwx for the owner, r-x or r-- for the rest: the glibc package's 0755 and 0644. */
    private fun setMode(file: File, mode: Int): Boolean =
        file.setReadable(true, false) && file.setWritable(true, true) && file.setExecutable(mode and EXEC_BITS != 0, false)

    private fun readText(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (e: IOException) {
        null
    }

    private fun writeAtomic(file: File, text: String): Boolean {
        val staged = File(file.parentFile, file.name + ".staged")
        return try {
            file.parentFile?.mkdirs()
            staged.writeText(text)
            staged.renameTo(file) || run { staged.delete(); false }
        } catch (e: IOException) {
            staged.delete()
            false
        }
    }

    private class AssetOverlay(private val context: Context) : Overlay {
        override fun manifest(version: String): String? = try {
            context.assets.open("$ASSETS/$version/manifest").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            null
        }

        override fun open(version: String, flavor: String, path: String): InputStream =
            context.assets.open("$ASSETS/$version/$flavor/$path")
    }
}
