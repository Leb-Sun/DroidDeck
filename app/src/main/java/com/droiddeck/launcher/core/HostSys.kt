package com.droiddeck.launcher.core

import android.util.Log
import java.io.File

/**
 * The app's own calls into the kernel (native/hostsys), for what Java cannot ask: today the
 * capability probe, [CapProbe]'s, which forks this process to try calls Android's seccomp filter
 * may kill (hostsys/capprobe.c says how a trapped call ends only its own child).
 */
object HostSys {
    private const val TAG = "HostSys"

    @JvmStatic private external fun nativeProbe(dir: String, timeoutMs: Int): String?

    /**
     * The probe's report (capprobe.h), or null when the library could not be loaded. [dir] holds
     * the files two of its tests need. Blocking: a fork of this process and a few milliseconds of
     * tests, bounded by [timeoutMs] once the fork is made.
     */
    fun probe(dir: File, timeoutMs: Int): String? = try {
        System.loadLibrary("hostsys")
        nativeProbe(dir.path, timeoutMs)
    } catch (t: Throwable) {
        Log.w(TAG, "capability probe unavailable", t)
        null
    }
}
