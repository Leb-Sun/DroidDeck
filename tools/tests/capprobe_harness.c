// Runs the capability probe of app/src/main/cpp/hostsys/capprobe.c on the host, so its
// blocked/allowed classification can be checked under tools/native/launcher/seccomp-launch's
// simulation of Android's app filter. Built and run by tools/tests/test_native_capprobe.py.
//
//   capprobe_harness DIR            the probe's report, as the app gets it
//   capprobe_harness --hang DIR     a test that never returns, with a 200 ms timeout
//   capprobe_harness --numbers      the probe's syscall numbers: name x86_64 aarch64
#include "capprobe.c"

#include <stdio.h>
#include <string.h>

static void t_hang(volatile struct cp_slot *s, const struct cp_ctx *c)
{
    (void)c;
    s->step = 1;
    for (;;) pause();
}

static const struct cp_test HANG[] = {
    { "setresuid", t_setresuid },
    { "hang", t_hang },
    { "clone3", t_clone3 },
};

int main(int argc, char **argv)
{
    char out[CAPPROBE_OUT_MAX];
    if (argc == 2 && !strcmp(argv[1], "--numbers")) {
#define X(name, x86_64, aarch64) printf("%s %d %d\n", #name, x86_64, aarch64);
        CAPPROBE_NRS(X)
#undef X
        return 0;
    }
    if (argc == 3 && !strcmp(argv[1], "--hang")) {
        run_tests(HANG, (int)(sizeof HANG / sizeof HANG[0]), argv[2], 200, out, sizeof out);
        fputs(out, stdout);
        return 0;
    }
    if (argc != 2) return 2;
    capprobe_run(argv[1], 2000, out, sizeof out);
    fputs(out, stdout);
    return 0;
}
