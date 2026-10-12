/*
 * execshim: an LD_PRELOAD exec shim for the native-mode spike (S2).
 *
 * On a non-rooted Android above targetSdk 28, the kernel refuses to execve a
 * rootfs ELF directly (02 S3); the way through is to exec the rootfs's ld.so
 * and let *it* map the program (02 S2/S6.3 -- the same thing proot's loader
 * does).  This shim rewrites every exec of a rootfs ELF into an exec of
 *    $BL_LOADER --argv0 <orig argv0> <program> <args...>
 * and resolves shebangs (#!/bin/bash, #!/usr/bin/env python3) inside the
 * rootfs first.  It covers the whole exec family and posix_spawn, because
 * glibc's execvp/posix_spawn reach the kernel through the internal __execve,
 * which a plain `execve` interposer would miss (02 S5.1, 01 P7).
 *
 * It emulates /proc/self/exe (readlink/open/execve) so a program sees the
 * guest program, not ld.so -- Chromium/CEF and Wine re-exec through it (01 P5).
 * The real exe is carried to children in $BL_EXEC_REAL.
 *
 * Env:
 *   BL_LOADER      path to the rootfs ld.so (default /lib64/ld-linux-x86-64.so.2)
 *   BL_EXEC_REAL   set by the shim; the guest path of this process's program
 *   BL_EXECSHIM_LOG=1  trace rewrites to stderr
 *
 * Notes recorded for the doc:
 *   - AT_EXECFN (getauxval) and /proc/self/comm reflect the LOADER, not the
 *     program, because the kernel really exec'd ld.so.  comm shows "ld-linux"
 *     (truncated).  A program that reads either for its own identity needs the
 *     same emulation as /proc/self/exe; glibc's __progname comes from argv[0],
 *     which --argv0 fixes.
 *   - Better placed inside the patched glibc (execve.c): a preload misses raw
 *     execve syscalls (static binaries, Go) and must re-list every entry point.
 *
 * Spike code.  Build: cc -shared -fPIC -o libexecshim.so execshim.c -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>

static int (*real_execve)(const char *, char *const[], char *const[]);
static int (*real_posix_spawn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                               const posix_spawnattr_t *, char *const[], char *const[]);
static ssize_t (*real_readlink)(const char *, char *, size_t);
static ssize_t (*real_readlinkat)(int, const char *, char *, size_t);
static int (*real_open)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);

/* Fix /proc/self/comm: run as `ld.so prog`, the kernel sets comm to
   "ld-linux-x86-64".  On startup, if we know the real program, set comm to its
   basename so a program that reads /proc/self/comm for its identity is right.
   (AT_EXECFN is already correct -- ld.so fixes the auxv.  /proc/self/exe is
   handled by the readlink/open/execve hooks.)  */
static void __attribute__((constructor)) fix_comm(void)
{
  const char *real = getenv("BL_EXEC_REAL");
  if (real && *real) {
    const char *base = strrchr(real, '/');
    prctl(PR_SET_NAME, (unsigned long)(base ? base + 1 : real), 0, 0, 0);
  }
}

static void init(void)
{
  if (real_execve) return;
  real_execve = dlsym(RTLD_NEXT, "execve");
  real_posix_spawn = dlsym(RTLD_NEXT, "posix_spawn");
  real_readlink = dlsym(RTLD_NEXT, "readlink");
  real_readlinkat = dlsym(RTLD_NEXT, "readlinkat");
  real_open = dlsym(RTLD_NEXT, "open");
  real_openat = dlsym(RTLD_NEXT, "openat");
}

static const char *loader(void)
{
  const char *l = getenv("BL_LOADER");
  return (l && *l) ? l : "/lib64/ld-linux-x86-64.so.2";
}

static int logging(void) { static int v = -1; if (v < 0) v = getenv("BL_EXECSHIM_LOG") ? 1 : 0; return v; }

/* Is this path the loader itself (avoid rewriting our own re-exec)? */
static int is_loader(const char *p) { return strcmp(p, loader()) == 0; }

/* Classify a file: 'E' ELF, '#' shebang (fills interp[]/arg[]), 0 other/err. */
static int classify(const char *path, char *interp, size_t icap, char *arg, size_t acap)
{
  int fd = real_open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 0;
  char buf[256];
  ssize_t n = read(fd, buf, sizeof buf - 1);
  close(fd);
  if (n < 4) return 0;
  buf[n] = 0;
  if (memcmp(buf, "\177ELF", 4) == 0) return 'E';
  if (buf[0] == '#' && buf[1] == '!') {
    char *p = buf + 2, *end = memchr(buf, '\n', n);
    if (end) *end = 0;
    while (*p == ' ' || *p == '\t') p++;
    char *sp = p;
    while (*sp && *sp != ' ' && *sp != '\t') sp++;
    size_t ilen = sp - p;
    if (ilen == 0 || ilen >= icap) return 0;
    memcpy(interp, p, ilen); interp[ilen] = 0;
    /* one optional argument (the rest of the line, trimmed) */
    while (*sp == ' ' || *sp == '\t') sp++;
    arg[0] = 0;
    if (*sp) { size_t al = strlen(sp); while (al && (sp[al-1]==' '||sp[al-1]=='\t')) al--;
               if (al < acap) { memcpy(arg, sp, al); arg[al] = 0; } }
    return '#';
  }
  return 0;
}

/* Build envp' with BL_EXEC_REAL=real set (replacing any existing). */
static char **with_exec_real(char *const envp[], const char *real)
{
  int n = 0; while (envp && envp[n]) n++;
  char **out = malloc((n + 2) * sizeof *out);
  int k = 0;
  for (int i = 0; i < n; i++)
    if (strncmp(envp[i], "BL_EXEC_REAL=", 13) != 0) out[k++] = envp[i];
  static char line[PATH_MAX + 16];
  snprintf(line, sizeof line, "BL_EXEC_REAL=%s", real);
  out[k++] = line;
  out[k] = NULL;
  return out;
}

/* Resolve a program + argv to the (loader, argv') that actually runs it,
   following a shebang chain.  Returns a malloc'd argv'; *prog set to the ELF
   path (for BL_EXEC_REAL).  depth guards against loops. */
static char **wrap(const char *path, char *const argv[], const char **prog, int depth)
{
  if (depth > 8) return NULL;
  char interp[PATH_MAX], arg[PATH_MAX];
  int kind = classify(path, interp, sizeof interp, arg, sizeof arg);
  if (kind == '#') {
    /* [interp, (arg?), path, argv[1..]] then recurse on interp */
    int ac = 0; while (argv && argv[ac]) ac++;
    char **na = malloc((ac + 3) * sizeof *na);
    int k = 0; na[k++] = interp; if (arg[0]) na[k++] = arg; na[k++] = (char *)path;
    for (int i = 1; i < ac; i++) na[k++] = argv[i];
    na[k] = NULL;
    char **r = wrap(interp, na, prog, depth + 1);
    return r;
  }
  if (kind == 'E') {
    int ac = 0; while (argv && argv[ac]) ac++;
    char **la = malloc((ac + 4) * sizeof *la);
    int k = 0;
    la[k++] = (char *)loader();
    la[k++] = "--argv0";
    la[k++] = (ac > 0 && argv[0]) ? argv[0] : (char *)path;
    la[k++] = (char *)path;
    for (int i = 1; i < ac; i++) la[k++] = argv[i];
    la[k] = NULL;
    *prog = path;
    return la;
  }
  return NULL; /* not ours */
}

/* Map "/proc/self/exe" (and /proc/<pid>/exe for our own pid) to BL_EXEC_REAL. */
static const char *exe_redirect(const char *path)
{
  const char *real = getenv("BL_EXEC_REAL");
  if (!real || !*real) return NULL;
  if (strcmp(path, "/proc/self/exe") == 0) return real;
  char self[64]; snprintf(self, sizeof self, "/proc/%d/exe", getpid());
  if (strcmp(path, self) == 0) return real;
  return NULL;
}

int execve(const char *path, char *const argv[], char *const envp[])
{
  init();
  const char *red = exe_redirect(path);
  if (red) path = red;
  if (is_loader(path)) return real_execve(path, argv, envp);
  const char *prog = NULL;
  char **wa = wrap(path, argv, &prog, 0);
  if (!wa) return real_execve(path, argv, envp); /* let kernel try */
  char **ne = with_exec_real(envp, prog);
  if (logging()) fprintf(stderr, "[execshim] %s -> %s --argv0 %s %s\n", path, loader(),
                         argv && argv[0] ? argv[0] : "?", prog);
  real_execve(loader(), wa, ne);
  return -1; /* only on failure */
}

int execveat(int dirfd, const char *path, char *const argv[], char *const envp[], int flags)
{
  /* For the spike we handle the common AT_FDCWD / absolute case by delegating
     to execve; a true dirfd-relative execveat would translate via the dirfd. */
  (void)flags;
  if (dirfd == AT_FDCWD || path[0] == '/') return execve(path, argv, envp);
  return syscall(SYS_execveat, dirfd, path, argv, envp, flags);
}

int execv(const char *path, char *const argv[]) { extern char **environ; return execve(path, argv, environ); }

static int path_search(const char *file, char *out, size_t cap)
{
  if (strchr(file, '/')) { snprintf(out, cap, "%s", file); return 0; }
  const char *path = getenv("PATH"); if (!path) path = "/usr/local/bin:/usr/bin:/bin";
  const char *p = path;
  while (*p) {
    const char *e = strchrnul(p, ':');
    size_t dl = e - p;
    snprintf(out, cap, "%.*s/%s", (int)dl, dl ? p : ".", file);
    if (access(out, X_OK) == 0) return 0;
    p = *e ? e + 1 : e;
  }
  return -1;
}

int execvp(const char *file, char *const argv[])
{
  extern char **environ;
  char full[PATH_MAX];
  if (path_search(file, full, sizeof full) != 0) { errno = ENOENT; return -1; }
  return execve(full, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[])
{
  char full[PATH_MAX];
  if (path_search(file, full, sizeof full) != 0) { errno = ENOENT; return -1; }
  return execve(full, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
  init();
  const char *red = exe_redirect(path);
  if (red) path = red;
  if (is_loader(path)) return real_posix_spawn(pid, path, fa, attr, argv, envp);
  const char *prog = NULL;
  char **wa = wrap(path, argv, &prog, 0);
  if (!wa) return real_posix_spawn(pid, path, fa, attr, argv, envp);
  char **ne = with_exec_real(envp, prog);
  if (logging()) fprintf(stderr, "[execshim] spawn %s -> %s %s\n", path, loader(), prog);
  return real_posix_spawn(pid, loader(), fa, attr, wa, ne);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
  char full[PATH_MAX];
  if (path_search(file, full, sizeof full) != 0) { errno = ENOENT; return ENOENT; }
  return posix_spawn(pid, full, fa, attr, argv, envp);
}

ssize_t readlink(const char *path, char *buf, size_t bufsiz)
{
  init();
  const char *red = exe_redirect(path);
  if (red) { size_t n = strlen(red); if (n > bufsiz) n = bufsiz; memcpy(buf, red, n); return (ssize_t)n; }
  return real_readlink(path, buf, bufsiz);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t bufsiz)
{
  init();
  const char *red = (dirfd == AT_FDCWD || path[0] == '/') ? exe_redirect(path) : NULL;
  if (red) { size_t n = strlen(red); if (n > bufsiz) n = bufsiz; memcpy(buf, red, n); return (ssize_t)n; }
  return real_readlinkat(dirfd, path, buf, bufsiz);
}

int open(const char *path, int flags, ...)
{
  init();
  mode_t m = 0;
  if (flags & O_CREAT) { va_list ap; va_start(ap, flags); m = va_arg(ap, int); va_end(ap); }
  const char *red = exe_redirect(path);
  return real_open(red ? red : path, flags, m);
}

int openat(int dirfd, const char *path, int flags, ...)
{
  init();
  mode_t m = 0;
  if (flags & O_CREAT) { va_list ap; va_start(ap, flags); m = va_arg(ap, int); va_end(ap); }
  const char *red = (dirfd == AT_FDCWD || path[0] == '/') ? exe_redirect(path) : NULL;
  return real_openat(dirfd, red ? red : path, flags, m);
}
