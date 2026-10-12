/*
 * trap-trace: a diagnostic for the native-mode spike.  It runs a command under
 * the same seccomp blocklist as seccomp-launch, but ptraces it so that each
 * SIGSYS (a blocked syscall that escaped to the kernel) is reported with its
 * syscall number and instruction pointer, and the child is then killed.
 *
 * This is NOT the runtime: the point of the spike is that nothing traces the
 * process.  trap-trace exists only to *find* which raw syscalls still escape a
 * patched glibc (a SIGSYS handler cannot: it resets across execve, 02 S5.3, so
 * it never sees ld.so's earliest calls).  Use seccomp-launch (no tracer) for
 * the real pass/fail; use this to explain a failure.
 *
 * Build: cc -O2 -o trap-trace trap-trace.c
 * Usage: trap-trace [--profile P] [--chroot DIR] [--chdir DIR]
 *                   [--allow NAMES] [--also-block NAMES] [--continue] -- CMD...
 *   --continue: after reporting, let the syscall return -ENOSYS and resume,
 *               to enumerate *all* distinct escapes in one run instead of
 *               stopping at the first.
 *
 * Reuses the policy table by compiling seccomp-launch.c with TRAPTRACE_REUSE.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <stddef.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

/* Pull in the shared policy table and filter builder. */
#include "policy.inc.h"

int main(int argc, char **argv)
{
  int prof = P14, cont = 0;
  const char *chroot_dir = NULL, *chdir_dir = NULL, *allow = NULL, *extra = NULL;
  int i = 1;
  for (; i < argc; i++) {
    if (!strcmp(argv[i], "--")) { i++; break; }
    else if (!strcmp(argv[i], "--continue")) cont = 1;
    else if (!strcmp(argv[i], "--profile") && i + 1 < argc) {
      const char *p = argv[++i];
      prof = !strcmp(p, "android15") ? P15 : !strcmp(p, "android16q") ? P16Q
           : !strcmp(p, "android12") ? P12 : P14;
    }
    else if (!strcmp(argv[i], "--chroot") && i + 1 < argc) chroot_dir = argv[++i];
    else if (!strcmp(argv[i], "--chdir") && i + 1 < argc) chdir_dir = argv[++i];
    else if (!strcmp(argv[i], "--allow") && i + 1 < argc) allow = argv[++i];
    else if (!strcmp(argv[i], "--also-block") && i + 1 < argc) extra = argv[++i];
    else { fprintf(stderr, "unknown arg %s\n", argv[i]); return 2; }
  }
  if (i >= argc) { fprintf(stderr, "no command (use -- CMD ...)\n"); return 2; }

  pid_t pid = fork();
  if (pid == 0) {
    ptrace(PTRACE_TRACEME, 0, 0, 0);
    if (chroot_dir) { if (chroot(chroot_dir) || chdir("/")) { perror("chroot"); _exit(3); } }
    if (chdir_dir && chdir(chdir_dir)) { perror("chdir"); _exit(3); }
    if (install_filter(prof, allow, extra) < 0) _exit(4);
    raise(SIGSTOP);
    execvp(argv[i], &argv[i]);
    perror("execvp");
    _exit(127);
  }

  int status, traps = 0, nalive = 1, rootrc = -1;
  waitpid(pid, &status, 0);
  /* Follow every fork/vfork/clone so we see traps in children and threads
     (the native-mode process trees fan out heavily).  EXITKILL tears the tree
     down if we die. */
  ptrace(PTRACE_SETOPTIONS, pid, 0,
         (void *)(long)(PTRACE_O_EXITKILL | PTRACE_O_TRACEFORK
                        | PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE
                        | PTRACE_O_TRACEEXEC));
  ptrace(PTRACE_CONT, pid, 0, 0);
  for (;;) {
    pid_t w = waitpid(-1, &status, __WALL);
    if (w < 0) break;
    if (WIFEXITED(status) || WIFSIGNALED(status)) {
      if (w == pid) rootrc = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
      if (--nalive <= 0) break;
      continue;
    }
    if (WIFSTOPPED(status)) {
      int sig = WSTOPSIG(status);
      int event = status >> 16;
      if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK
          || event == PTRACE_EVENT_CLONE) {
        nalive++;                       /* a new tracee appeared */
        ptrace(PTRACE_CONT, w, 0, 0);
        continue;
      }
      if (event == PTRACE_EVENT_EXEC) { ptrace(PTRACE_CONT, w, 0, 0); continue; }
      if (sig == SIGSYS) {
        traps++;
        struct user_regs_struct regs;
        ptrace(PTRACE_GETREGS, w, 0, &regs);
#if defined(__x86_64__)
        long nr = regs.orig_rax; unsigned long pc = regs.rip;
#elif defined(__aarch64__)
        long nr = regs.regs[8]; unsigned long pc = regs.pc;
#endif
        const char *nm = "?";
        for (int j = 0; j < NTAB; j++) if (TABLE[j].x86_64 == nr) { nm = TABLE[j].name; break; }
        fprintf(stderr, "[trap-trace] pid %d SIGSYS: syscall %ld (%s) at pc=0x%lx\n",
                (int)w, nr, nm, pc);
        if (cont) {
#if defined(__x86_64__)
          regs.orig_rax = -1; regs.rax = (unsigned long)-ENOSYS;
#elif defined(__aarch64__)
          regs.regs[8] = -1; regs.regs[0] = (unsigned long)-ENOSYS;
#endif
          ptrace(PTRACE_SETREGS, w, 0, &regs);
          ptrace(PTRACE_CONT, w, 0, 0);
          continue;
        }
        ptrace(PTRACE_CONT, w, 0, SIGSYS);
        continue;
      }
      /* group-stop / other signals: forward (but not our synthetic SIGTRAP) */
      ptrace(PTRACE_CONT, w, 0, sig == SIGTRAP ? 0 : sig);
    }
  }
  fprintf(stderr, "[trap-trace] done: root rc=%d, %d trap(s) across the tree\n", rootrc, traps);
  return traps ? 100 : (rootrc > 0 ? rootrc : 0);
}
