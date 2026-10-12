/*
 * seccomp-launch: simulate Android's app seccomp policy on a Linux host, then
 * exec a target.  DroidDeck native-mode feasibility spike (04-spikes.md).
 *
 * It installs a classic-BPF seccomp filter that answers a *blocklist* of
 * syscalls with SECCOMP_RET_TRAP (so the kernel raises SIGSYS), exactly as
 * Android's zygote does for an app, and then execs the target with the filter
 * in force.  seccomp filters survive execve, which is the whole point: the new
 * image's ld.so runs under the policy before any handler could exist.
 *
 * Blocklist: docs/development/native-env/02 S3 (the exact arm64 app policy,
 * syscall-number only) plus the per-release additions.  Profiles:
 *   android14  : also clone3, faccessat2, fchmodat2, openat2 blocked
 *   android15  : clone3 allowed; faccessat2/fchmodat2/openat2 still blocked
 *   android16q : faccessat2/fchmodat2/openat2 allowed too (16 QPR1+/17)
 *   android12  : android14 plus close_range blocked (glibc patch 0007)
 *
 * A trapped syscall kills the process with SIGSYS, like Android with no tracer.
 * (Use trap-trace to learn *which* syscall; a SIGSYS handler cannot, because
 * it resets across execve -- 02 S5.3.)
 *
 * --chroot DIR chroots before exec (a host convenience for the S1 userland
 * test; the no-chroot relocated case is S3).  --chdir DIR sets the cwd.
 *
 * Build: cc -O2 -o seccomp-launch seccomp-launch.c
 * Usage: seccomp-launch [--profile P] [--chroot DIR] [--chdir DIR]
 *                       [--allow NAMES] [--also-block NAMES] [--quiet] -- CMD...
 *        seccomp-launch --dump {x86_64|aarch64}
 *        seccomp-launch --list
 *
 * Spike code.  x86_64 host testbed; arm64 numbers emitted for an on-device
 * probe.  Not wired into the app or CI.
 */
#include "policy.inc.h"

static void dump(const char *arch)
{
  int aarch64 = !strcmp(arch, "aarch64");
  printf("# DroidDeck app-seccomp blocklist, %s numbers\n", arch);
  printf("# a14/a15/a16q/a12 = blocked (1) or allowed (0) under that profile\n");
  printf("# %-20s %6s  a14 a15 a16q a12\n", "syscall", "nr");
  for (int i = 0; i < NTAB; i++) {
    int nr = aarch64 ? TABLE[i].aarch64 : TABLE[i].x86_64;
    printf("%-22s %6d   %d   %d    %d   %d\n", TABLE[i].name, nr,
           profile_blocks(P14, TABLE[i].name),
           profile_blocks(P15, TABLE[i].name),
           profile_blocks(P16Q, TABLE[i].name),
           profile_blocks(P12, TABLE[i].name));
  }
  printf("# allowed (never blocked):");
  for (int i = 0; ALWAYS_ALLOW[i]; i++) printf(" %s", ALWAYS_ALLOW[i]);
  printf("\n");
}

int main(int argc, char **argv)
{
  int prof = P14, quiet = 0;
  const char *chroot_dir = NULL, *chdir_dir = NULL, *allow = NULL, *extra = NULL;
  int i = 1;
  for (; i < argc; i++) {
    if (!strcmp(argv[i], "--")) { i++; break; }
    else if (!strcmp(argv[i], "--quiet")) quiet = 1;
    else if (!strcmp(argv[i], "--list")) { printf("profiles: android14 android15 android16q android12\n"); return 0; }
    else if (!strcmp(argv[i], "--dump") && i + 1 < argc) { dump(argv[++i]); return 0; }
    else if (!strcmp(argv[i], "--profile") && i + 1 < argc) {
      const char *p = argv[++i];
      if (!strcmp(p, "android14")) prof = P14;
      else if (!strcmp(p, "android15")) prof = P15;
      else if (!strcmp(p, "android16q")) prof = P16Q;
      else if (!strcmp(p, "android12")) prof = P12;
      else { fprintf(stderr, "unknown profile %s\n", p); return 2; }
    }
    else if (!strcmp(argv[i], "--chroot") && i + 1 < argc) chroot_dir = argv[++i];
    else if (!strcmp(argv[i], "--chdir") && i + 1 < argc) chdir_dir = argv[++i];
    else if (!strcmp(argv[i], "--allow") && i + 1 < argc) allow = argv[++i];
    else if (!strcmp(argv[i], "--also-block") && i + 1 < argc) extra = argv[++i];
    else { fprintf(stderr, "unknown arg %s\n", argv[i]); return 2; }
  }
  if (i >= argc) { fprintf(stderr, "no command given (use -- CMD ...)\n"); return 2; }

  if (chroot_dir) {
    if (chroot(chroot_dir) != 0) { perror("chroot"); return 3; }
    if (chdir("/") != 0) { perror("chdir /"); return 3; }
  }
  if (chdir_dir && chdir(chdir_dir) != 0) { perror("chdir"); return 3; }

  int nb = install_filter(prof, allow, extra);
  if (nb < 0) return 4;
  if (!quiet)
    fprintf(stderr, "[seccomp-launch] profile=%s blocked=%d -> exec %s\n",
            prof == P14 ? "android14" : prof == P15 ? "android15" : prof == P16Q ? "android16q" : "android12",
            nb, argv[i]);

  execvp(argv[i], &argv[i]);
  perror("execvp");
  return 127;
}
