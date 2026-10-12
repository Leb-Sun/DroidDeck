/*
 * S1 userland battery for the native-mode spike: exercises the glibc paths
 * most likely to issue an Android-blocked syscall -- thread creation
 * (clone3 / set_robust_list / rseq), posix_spawn/system/popen, faccessat with
 * flags (faccessat2), and the set*id family (mapped to setres*id) -- so that
 * running it untraced under seccomp-launch proves the patched glibc never
 * trips the filter.  Each sub-test prints a line; exit 0 iff all pass.
 *
 * Build against any glibc; run inside the patched rootfs so it binds to the
 * patched libc.  Spike code.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <pthread.h>
#include <spawn.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/fsuid.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
static atomic_long counter;
static int fails;

static void check(const char *name, int ok) {
  printf("%-22s %s\n", name, ok ? "ok" : "FAIL");
  if (!ok) fails++;
}

static void *worker(void *arg) {
  long n = (long)arg;
  for (long i = 0; i < 100000; i++) atomic_fetch_add(&counter, 1);
  /* touch errno/TLS and a small malloc to use the thread's arena */
  void *p = malloc(4096);
  memset(p, (int)n, 4096);
  free(p);
  return NULL;
}

int main(void) {
  /* 1. pthread-heavy: many threads => per-thread clone(3) + the TLS setup that
        would otherwise issue set_robust_list/rseq. */
  enum { NT = 16 };
  pthread_t th[NT];
  int spawned = 0;
  for (int i = 0; i < NT; i++)
    if (pthread_create(&th[i], NULL, worker, (void *)(long)i) == 0) spawned++;
  for (int i = 0; i < spawned; i++) pthread_join(th[i], NULL);
  check("pthreads", spawned == NT && atomic_load(&counter) == (long)NT * 100000);

  /* 2. posix_spawn */
  {
    pid_t pid; char *av[] = { "/bin/true", NULL };
    int rc = posix_spawn(&pid, "/bin/true", NULL, NULL, av, environ);
    int st = -1; if (rc == 0) waitpid(pid, &st, 0);
    check("posix_spawn", rc == 0 && WIFEXITED(st) && WEXITSTATUS(st) == 0);
  }

  /* 3. system() */
  check("system", system("exit 0") == 0);

  /* 4. popen()/pclose() with a pipe */
  {
    FILE *f = popen("echo hello | tr a-z A-Z", "r");
    char buf[32] = {0};
    int ok = f && fgets(buf, sizeof buf, f) && strncmp(buf, "HELLO", 5) == 0;
    if (f) pclose(f);
    check("popen", ok);
  }

  /* 5. faccessat with flags (AT_EACCESS, AT_SYMLINK_NOFOLLOW) -> faccessat2 */
  {
    int a = faccessat(AT_FDCWD, "/bin/true", R_OK, AT_EACCESS);
    int b = faccessat(AT_FDCWD, "/bin/true", F_OK, AT_SYMLINK_NOFOLLOW);
    int c = faccessat(AT_FDCWD, "/nonexistent-xyz", F_OK, 0);
    check("faccessat flags", a == 0 && b == 0 && (c == -1 && errno == ENOENT));
  }

  /* 6. set*id family -> setres*id / no-op.  Setting to the current id must
        succeed; this is the Xwayland case.  (Run as whatever uid; we only set
        the id we already have, which never needs privilege.) */
  {
    uid_t u = getuid(); gid_t g = getgid();
    int ok = 1;
    if (setgid(g) != 0) ok = 0;
    if (setuid(u) != 0) ok = 0;
    if (setreuid(u, u) != 0) ok = 0;
    if (setregid(g, g) != 0) ok = 0;
    (void)setfsuid(u);            /* returns previous; never fails */
    (void)setfsgid(g);
    if (setgroups(0, NULL) != 0) ok = 0;   /* no-op success in the spike */
    check("set*id family", ok && getuid() == u && getgid() == g);
  }

  printf(fails ? "\nBATTERY: %d FAIL\n" : "\nBATTERY: ALL OK\n", fails);
  return fails ? 1 : 0;
}
