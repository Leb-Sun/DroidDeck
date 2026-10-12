/*
 * The capability probe (capprobe.c): what this device's app seccomp filter and kernel allow, asked
 * from inside the app's own process, the only place the app filter applies (adb is not under it).
 * Used by core/HostSys.kt through hostsys.c, and built on the host by
 * tools/tests/test_native_capprobe.py.
 */
#ifndef DROIDDECK_CAPPROBE_H
#define DROIDDECK_CAPPROBE_H

#include <stddef.h>

/* Enough for every test's line. */
#define CAPPROBE_OUT_MAX 4096

/*
 * Runs every test and writes the report into out, one line each:
 *
 *   capprobe 1 ok                       (or "timeout", or "failed errno=N" / "signal=N" / "exit=N")
 *   <test> allowed ret=<n> errno=<n> step=<n>
 *   <test> blocked step=<n>             (the filter killed the test's child with SIGSYS)
 *   <test> error errno=<n> step=<n>     (its set-up failed: nothing was learned)
 *   <test> error signal=<n> step=<n>    (or exit=<n>; "unfinished" or "not-run" after a timeout)
 *
 * dir holds the files two tests need: "file" (a regular file) and "root/" with "root/inside" and a
 * symlink "root/link" -> "/inside". timeout_ms bounds the wait once the helper is running. Returns 0
 * when every test ran; otherwise the report says why not.
 */
int capprobe_run(const char *dir, int timeout_ms, char *out, size_t len);

#endif
