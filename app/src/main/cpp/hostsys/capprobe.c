/*
 * The capability probe: what this device's app seccomp filter and kernel allow, asked from inside
 * the app (docs/development/native-env/03-design.md S3.3). Every test makes the call it is about
 * in a child process of its own, so a call the filter answers with SIGSYS kills that child and
 * nothing else, and the parent reads the child's death as "blocked".
 *
 * Why this shape. Android's app filter differs by release (02 S3): close_range from 13, clone3
 * from 14 QPR/15, Landlock from 16, openat2/faccessat2/fchmodat2 from 16 QPR1. adb runs outside
 * it, so only a process zygote forked tells the truth. The app already forks from JNI for its
 * terminal (termux/termux.c) and builds its native code as JNI libraries; an executable in the
 * native-library directory would need a packaging route the app does not have (proot's is a CI
 * prebuild), so this is a JNI library and the probe is a fork of the app.
 *
 * One fork of the app per test would be simplest, but a fork copies the page tables of a process
 * that holds hundreds of MB and thousands of mappings mid-session: 15-34 ms a fork for 300-600 MB
 * on a desktop [measured, host], so twenty tests would cost the session start a large part of a
 * second. So the app forks once, a helper, and the helper starts each test with
 * clone(CLONE_VM | CLONE_VFORK): a process of its own (its own pid, thread group, signal handlers,
 * fd table and credentials) sharing the helper's private copy of memory, never the app's, in tens
 * of microseconds. Two things make a SIGSYS end only that test on every kernel:
 *   - the helper is made non-dumpable first. SIGSYS dumps core, and before 5.16 a core dump also
 *     killed every other process sharing the mm (zap_threads); a non-dumpable mm skips the dump;
 *   - the fatal signals go back to their default action, so the crash handlers every app process
 *     inherits (debuggerd's, ART's) do not catch the SIGSYS and write a tombstone.
 *
 * Nothing here changes the app. The helper is a raw clone of the calling thread, so no atfork
 * handler runs in the app or the helper (the app's allocator is not stopped for the copy); the
 * helper and the tests make only system calls, directly or through libc wrappers that are one; the
 * tests' arguments are harmless (an empty range, a NULL that fails after the filter has passed it,
 * the uid already held), and anything they map, open or set is the helper's or their own. Results
 * go into a shared anonymous page that the app formats once the helper has gone.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "capprobe.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#if defined(__aarch64__)
#  define ARCH_NR(x86_64, aarch64) (aarch64)
#elif defined(__x86_64__)
#  define ARCH_NR(x86_64, aarch64) (x86_64)
#else
#  error "capprobe: aarch64 (the app) and x86_64 (the host tests) only"
#endif

/*
 * The calls the tests make, by number: x86_64 for the host tests, aarch64 for the app. They are
 * tools/native/launcher/policy.inc.h's numbers (tools/tests/test_native_capprobe.py compares the
 * two), and the build's own headers are held to them below.
 */
#define CAPPROBE_NRS(X)                 \
  X(openat2,                 437, 437)  \
  X(faccessat2,              439, 439)  \
  X(fchmodat2,               452, 452)  \
  X(clone3,                  435, 435)  \
  X(close_range,             436, 436)  \
  X(landlock_create_ruleset, 444, 444)  \
  X(sched_setattr,           314, 274)  \
  X(sched_getattr,           315, 275)  \
  X(userfaultfd,             323, 282)  \
  X(memfd_create,            319, 279)  \
  X(setresuid,               117, 147)  \
  X(rseq,                    334, 293)  \
  X(set_robust_list,         273,  99)  \
  X(get_robust_list,         274, 100)  \
  X(futex_waitv,             449, 449)  \
  X(io_uring_setup,          425, 425)  \
  X(setuid,                  105, 146)  \
  X(getpid,                   39, 172)

#define X(name, x86_64, aarch64) NR_##name = ARCH_NR(x86_64, aarch64),
enum { CAPPROBE_NRS(X) };
#undef X

#if defined(__NR_openat2)
_Static_assert(__NR_openat2 == NR_openat2, "openat2");
#endif
#if defined(__NR_faccessat2)
_Static_assert(__NR_faccessat2 == NR_faccessat2, "faccessat2");
#endif
#if defined(__NR_fchmodat2)
_Static_assert(__NR_fchmodat2 == NR_fchmodat2, "fchmodat2");
#endif
#if defined(__NR_clone3)
_Static_assert(__NR_clone3 == NR_clone3, "clone3");
#endif
#if defined(__NR_close_range)
_Static_assert(__NR_close_range == NR_close_range, "close_range");
#endif
#if defined(__NR_landlock_create_ruleset)
_Static_assert(__NR_landlock_create_ruleset == NR_landlock_create_ruleset, "landlock_create_ruleset");
#endif
#if defined(__NR_sched_setattr)
_Static_assert(__NR_sched_setattr == NR_sched_setattr, "sched_setattr");
#endif
#if defined(__NR_sched_getattr)
_Static_assert(__NR_sched_getattr == NR_sched_getattr, "sched_getattr");
#endif
#if defined(__NR_userfaultfd)
_Static_assert(__NR_userfaultfd == NR_userfaultfd, "userfaultfd");
#endif
#if defined(__NR_memfd_create)
_Static_assert(__NR_memfd_create == NR_memfd_create, "memfd_create");
#endif
#if defined(__NR_setresuid)
_Static_assert(__NR_setresuid == NR_setresuid, "setresuid");
#endif
#if defined(__NR_rseq)
_Static_assert(__NR_rseq == NR_rseq, "rseq");
#endif
#if defined(__NR_set_robust_list)
_Static_assert(__NR_set_robust_list == NR_set_robust_list, "set_robust_list");
#endif
#if defined(__NR_get_robust_list)
_Static_assert(__NR_get_robust_list == NR_get_robust_list, "get_robust_list");
#endif
#if defined(__NR_futex_waitv)
_Static_assert(__NR_futex_waitv == NR_futex_waitv, "futex_waitv");
#endif
#if defined(__NR_io_uring_setup)
_Static_assert(__NR_io_uring_setup == NR_io_uring_setup, "io_uring_setup");
#endif
#if defined(__NR_setuid)
_Static_assert(__NR_setuid == NR_setuid, "setuid");
#endif
#if defined(__NR_getpid)
_Static_assert(__NR_getpid == NR_getpid, "getpid");
#endif

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
#ifndef AT_EACCESS
#define AT_EACCESS 0x200
#endif
/* The kernel's own values; the headers of either libc may predate them. */
#define CP_RESOLVE_IN_ROOT 0x10
#define CP_LANDLOCK_CREATE_RULESET_VERSION 1
#define CP_SCHED_FLAG_KEEP_ALL 0x18 /* KEEP_POLICY | KEEP_PARAMS */
#define CP_SCHED_FLAG_UTIL_CLAMP_MIN 0x20
#define CP_UCLAMP_MIN 512
#define CP_MFD_NOEXEC_SEAL 0x8
/* The fast path's stub page: FP_STUB_ADDR in tools/proot/fastpath/fastpath.c. */
#define CP_STUB_ADDR 0xffff00000UL

struct cp_open_how { uint64_t flags, mode, resolve; };

struct cp_sched_attr {
  uint32_t size, sched_policy;
  uint64_t sched_flags;
  int32_t sched_nice;
  uint32_t sched_priority;
  uint64_t sched_runtime, sched_deadline, sched_period;
  uint32_t sched_util_min, sched_util_max;
};

/* One test's record in the page the app, the helper and the test share. */
struct cp_slot {
  int ran;       /* the helper got to this test */
  int start_err; /* the helper could not start or reap it: errno */
  int reaped;    /* the helper has its wait status, */
  int status;    /* this */
  int step;      /* the call it was on: 0 set-up, 1 the call under test, 2.. what follows it */
  int setup;     /* a set-up call failed: err is its errno, and nothing was learned */
  int done;      /* the test returned */
  long ret;
  int err;
};

struct cp_ctx {
  char file[PATH_MAX]; /* a regular file in the app's data */
  char root[PATH_MAX]; /* a directory holding "inside" and "link" -> "/inside" */
  unsigned uid;
  long page;
};

typedef void (*cp_fn)(volatile struct cp_slot *, const struct cp_ctx *);
struct cp_test { const char *name; cp_fn run; };

/* One call, as step `step` of a test: its result, or -1 and its errno. */
static long call(volatile struct cp_slot *s, int step, long nr, long a, long b, long c, long d, long e)
{
  s->step = step;
  long r = syscall(nr, a, b, c, d, e, 0L);
  s->ret = r;
  s->err = r < 0 ? errno : 0;
  return r;
}

static void setup_failed(volatile struct cp_slot *s, int step)
{
  s->step = step;
  s->setup = 1;
  s->ret = -1;
  s->err = errno;
}

/* openat2(AT_FDCWD, "/", {O_PATH, RESOLVE_IN_ROOT}): an fd. */
static void t_openat2(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  struct cp_open_how how = { O_PATH | O_CLOEXEC, 0, CP_RESOLVE_IN_ROOT };
  call(s, 1, NR_openat2, AT_FDCWD, (long)"/", (long)&how, sizeof how, 0);
}

/*
 * An absolute symlink under RESOLVE_IN_ROOT resolves against the root, which is what the fast
 * path's openat2 relies on (03 S3.4): ret 1 when root/link opens root/inside, 0 when it opened
 * something else.
 */
static void t_resolve_in_root(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  struct stat want, got;
  s->step = 0;
  int root = open(c->root, O_PATH | O_DIRECTORY | O_CLOEXEC);
  if (root < 0 || fstatat(root, "inside", &want, AT_SYMLINK_NOFOLLOW) != 0) {
    setup_failed(s, 0);
    return;
  }
  struct cp_open_how how = { O_PATH | O_CLOEXEC, 0, CP_RESOLVE_IN_ROOT };
  long fd = call(s, 1, NR_openat2, root, (long)"link", (long)&how, sizeof how, 0);
  if (fd < 0) return;
  s->step = 2;
  if (fstat((int)fd, &got) != 0) {
    setup_failed(s, 2);
    return;
  }
  s->ret = got.st_dev == want.st_dev && got.st_ino == want.st_ino;
}

/* faccessat2(AT_FDCWD, "/", F_OK, AT_EACCESS): 0. */
static void t_faccessat2(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_faccessat2, AT_FDCWD, (long)"/", F_OK, AT_EACCESS, 0);
}

/* fchmodat2 on the probe file, to the mode it already has, AT_SYMLINK_NOFOLLOW: 0. */
static void t_fchmodat2(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  struct stat st;
  s->step = 0;
  if (stat(c->file, &st) != 0) {
    setup_failed(s, 0);
    return;
  }
  call(s, 1, NR_fchmodat2, AT_FDCWD, (long)c->file, st.st_mode & 07777, AT_SYMLINK_NOFOLLOW, 0);
}

/* clone3(NULL, 0): EINVAL, refused before anything is made. */
static void t_clone3(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_clone3, 0, 0, 0, 0, 0);
}

/* close_range(~0U, ~0U, 0): a range above every fd, so 0 and nothing closed. */
static void t_close_range(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_close_range, ~0U, ~0U, 0, 0, 0);
}

/* landlock_create_ruleset(NULL, 0, VERSION): the ABI version; no ruleset is made. */
static void t_landlock(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_landlock_create_ruleset, 0, 0, CP_LANDLOCK_CREATE_RULESET_VERSION, 0, 0);
}

/*
 * uclamp_min 512 on the test's own thread, policy and parameters kept, then read back: ret is the
 * value sched_getattr reports, 512 when it took.
 */
static void t_uclamp(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  struct cp_sched_attr a, b;
  memset(&a, 0, sizeof a);
  memset(&b, 0, sizeof b);
  a.size = sizeof a;
  a.sched_flags = CP_SCHED_FLAG_KEEP_ALL | CP_SCHED_FLAG_UTIL_CLAMP_MIN;
  a.sched_util_min = CP_UCLAMP_MIN;
  /* KEEP_PARAMS leaves the nice value alone, but without CAP_SYS_NICE the kernel still refuses a
     lower one than the thread has: pass the one it has. */
  s->step = 0;
  errno = 0;
  int nice = getpriority(PRIO_PROCESS, 0);
  if (nice == -1 && errno != 0) {
    setup_failed(s, 0);
    return;
  }
  a.sched_nice = nice;
  if (call(s, 1, NR_sched_setattr, 0, (long)&a, 0, 0, 0) < 0) return;
  if (call(s, 2, NR_sched_getattr, 0, (long)&b, sizeof b, 0, 0) < 0) return;
  s->ret = b.sched_util_min;
}

/* fastpath.c's stub: syscall(nr, a..f) as one svc / syscall instruction in its own page. */
typedef long (*cp_stub_fn)(long nr, long a, long b, long c, long d, long e, long f);
#if defined(__x86_64__)
static const uint8_t STUB_CODE[] = {
  0x48, 0x89, 0xf8, /* mov rax, rdi */  0x48, 0x89, 0xf7, /* mov rdi, rsi */
  0x48, 0x89, 0xd6, /* mov rsi, rdx */  0x48, 0x89, 0xca, /* mov rdx, rcx */
  0x4d, 0x89, 0xc2, /* mov r10, r8 */   0x4d, 0x89, 0xc8, /* mov r8, r9 */
  0x4c, 0x8b, 0x4c, 0x24, 0x08, /* mov r9, [rsp+8] */  0x0f, 0x05, /* syscall */  0xc3, /* ret */
};
#else
static const uint32_t STUB_CODE[] = {
  0xAA0003E8, /* mov x8, x0 */  0xAA0103E0, /* mov x0, x1 */  0xAA0203E1, /* mov x1, x2 */
  0xAA0303E2, /* mov x2, x3 */  0xAA0403E3, /* mov x3, x4 */  0xAA0503E4, /* mov x4, x5 */
  0xAA0603E5, /* mov x5, x6 */  0xD4000001, /* svc #0 */      0xD65F03C0, /* ret */
};
#endif

/*
 * The fast path's stub, as fastpath.c makes it: a page at its fixed address, written, made RX, and
 * getpid called through it. ret 1 when that returns the test's own pid. Steps: 1 the mapping at
 * the address (EEXIST: something holds it), 2 RX (EACCES: execmem refused), 3 the call.
 */
static void t_fp_stub(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  s->step = 1;
  void *p = mmap((void *)CP_STUB_ADDR, (size_t)c->page, PROT_READ | PROT_WRITE,
                 MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
  if (p == MAP_FAILED) {
    s->ret = -1;
    s->err = errno;
    return;
  }
  if (p != (void *)CP_STUB_ADDR) { /* a kernel before 4.17 took the address as a hint */
    munmap(p, (size_t)c->page);
    s->ret = -1;
    s->err = EEXIST;
    return;
  }
  memcpy(p, STUB_CODE, sizeof STUB_CODE);
  __builtin___clear_cache((char *)p, (char *)p + sizeof STUB_CODE);
  s->step = 2;
  if (mprotect(p, (size_t)c->page, PROT_READ | PROT_EXEC) != 0) {
    s->ret = -1;
    s->err = errno;
    munmap(p, (size_t)c->page);
    return;
  }
  s->step = 3;
  long pid = ((cp_stub_fn)p)(NR_getpid, 0, 0, 0, 0, 0, 0);
  s->ret = pid == syscall(NR_getpid);
  s->err = 0;
  munmap(p, (size_t)c->page);
}

/* An anonymous executable mapping (SELinux execmem): 0. */
static void t_execmem(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  s->step = 1;
  void *p = mmap(NULL, (size_t)c->page, PROT_READ | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (p == MAP_FAILED) {
    s->ret = -1;
    s->err = errno;
    return;
  }
  munmap(p, (size_t)c->page);
  s->ret = 0;
}

/* An executable mapping of a file in the app's data, as ld.so maps a rootfs library: 0. */
static void t_exec_data(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  s->step = 0;
  int fd = open(c->file, O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    setup_failed(s, 0);
    return;
  }
  s->step = 1;
  void *p = mmap(NULL, (size_t)c->page, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
  if (p == MAP_FAILED) {
    s->ret = -1;
    s->err = errno;
    return;
  }
  munmap(p, (size_t)c->page);
  s->ret = 0;
}

/* userfaultfd with every flag bit set: EINVAL (EPERM on older kernels), never an fd. */
static void t_userfaultfd(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_userfaultfd, -1, 0, 0, 0, 0);
}

/* memfd_create(NULL, MFD_NOEXEC_SEAL): EFAULT (EINVAL before 6.3), never an fd. */
static void t_memfd_create(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_memfd_create, 0, CP_MFD_NOEXEC_SEAL, 0, 0, 0);
}

/* setresuid to the uid already held: 0, and nothing changes. Allowed on every Android: the
   probe's own check that it reads an allowed call as allowed. */
static void t_setresuid(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  call(s, 1, NR_setresuid, c->uid, c->uid, c->uid, 0, 0);
}

/* Blocked on every Android 10-17 (02 S3); each would fail with EINVAL/EFAULT if it were not. */
static void t_rseq(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_rseq, 0, 0, 0, 0, 0);
}

static void t_set_robust_list(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_set_robust_list, 0, 0, 0, 0, 0);
}

static void t_get_robust_list(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_get_robust_list, 0, 0, 0, 0, 0);
}

static void t_futex_waitv(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_futex_waitv, 0, 0, 0, 0, 0);
}

static void t_io_uring_setup(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  (void)c;
  call(s, 1, NR_io_uring_setup, 0, 0, 0, 0, 0);
}

static void t_setuid(volatile struct cp_slot *s, const struct cp_ctx *c)
{
  call(s, 1, NR_setuid, c->uid, 0, 0, 0, 0);
}

/* The order of the report. Names are what core/CapProbe.kt and BL_CAPS use. */
static const struct cp_test TESTS[] = {
  { "openat2", t_openat2 },
  { "resolve_in_root", t_resolve_in_root },
  { "faccessat2", t_faccessat2 },
  { "fchmodat2", t_fchmodat2 },
  { "clone3", t_clone3 },
  { "close_range", t_close_range },
  { "landlock", t_landlock },
  { "uclamp", t_uclamp },
  { "fp_stub", t_fp_stub },
  { "execmem", t_execmem },
  { "exec_data", t_exec_data },
  { "userfaultfd", t_userfaultfd },
  { "memfd_create", t_memfd_create },
  { "setresuid", t_setresuid },
  { "rseq", t_rseq },
  { "set_robust_list", t_set_robust_list },
  { "get_robust_list", t_get_robust_list },
  { "futex_waitv", t_futex_waitv },
  { "io_uring_setup", t_io_uring_setup },
  { "setuid", t_setuid },
};
#define NTESTS ((int)(sizeof TESTS / sizeof TESTS[0]))

struct cp_job { const struct cp_test *test; volatile struct cp_slot *slot; const struct cp_ctx *ctx; };

/* A test's own process: CLONE_VM | CLONE_VFORK from the helper. */
static int run_one(void *arg)
{
  struct cp_job *job = arg;
  /* Killed with the helper, if the app gives up on it. */
  prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
  job->test->run(job->slot, job->ctx);
  job->slot->done = 1;
  return 0;
}

/* The tests' stack, in the helper's copy of this library: they run one at a time. */
static char test_stack[64 * 1024] __attribute__((aligned(16)));

static void reset_signals(void)
{
  static const int fatal[] = { SIGSYS, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP };
  /* The kernel's struct sigaction with SIG_DFL, no flags and an empty mask: zeros on every 64-bit
     arch. Raw, so no signal-chaining layer in the process can keep its own handler. */
  unsigned long dfl[4] = { 0, 0, 0, 0 };
  for (size_t i = 0; i < sizeof fatal / sizeof fatal[0]; i++)
    syscall(__NR_rt_sigaction, (long)fatal[i], (long)dfl, 0L, 8L);
}

/* The forked helper: never returns. */
static void helper(const struct cp_test *tests, int n, volatile struct cp_slot *slots, const struct cp_ctx *ctx)
{
  prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
  reset_signals();
  for (int i = 0; i < n; i++) {
    struct cp_job job = { &tests[i], &slots[i], ctx };
    slots[i].ran = 1;
    int pid = clone(run_one, test_stack + sizeof test_stack, CLONE_VM | CLONE_VFORK | SIGCHLD, &job);
    if (pid < 0) {
      slots[i].start_err = errno;
      continue;
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
      if (errno != EINTR) {
        slots[i].start_err = errno;
        break;
      }
    }
    if (slots[i].start_err) continue;
    slots[i].status = status;
    slots[i].reaped = 1;
  }
  _exit(0);
}

static void append(char *out, size_t len, size_t *pos, const char *fmt, ...)
{
  if (*pos >= len) return;
  va_list ap;
  va_start(ap, fmt);
  int n = vsnprintf(out + *pos, len - *pos, fmt, ap);
  va_end(ap);
  if (n > 0) *pos = *pos + (size_t)n < len ? *pos + (size_t)n : len - 1;
}

static long elapsed_ms(const struct timespec *since)
{
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return (now.tv_sec - since->tv_sec) * 1000L + (now.tv_nsec - since->tv_nsec) / 1000000L;
}

static void report_test(const struct cp_test *t, volatile struct cp_slot *s, char *out, size_t len, size_t *pos)
{
  int st = s->status;
  if (!s->ran)
    append(out, len, pos, "%s error not-run\n", t->name);
  else if (s->start_err)
    append(out, len, pos, "%s error errno=%d step=0\n", t->name, s->start_err);
  else if (!s->reaped)
    append(out, len, pos, "%s error unfinished step=%d\n", t->name, s->step);
  else if (WIFSIGNALED(st) && WTERMSIG(st) == SIGSYS)
    append(out, len, pos, "%s blocked step=%d\n", t->name, s->step);
  else if (WIFSIGNALED(st))
    append(out, len, pos, "%s error signal=%d step=%d\n", t->name, WTERMSIG(st), s->step);
  else if (!WIFEXITED(st) || WEXITSTATUS(st) != 0 || !s->done)
    append(out, len, pos, "%s error exit=%d step=%d\n", t->name, WIFEXITED(st) ? WEXITSTATUS(st) : -1, s->step);
  else if (s->setup)
    append(out, len, pos, "%s error errno=%d step=%d\n", t->name, s->err, s->step);
  else
    append(out, len, pos, "%s allowed ret=%ld errno=%d step=%d\n", t->name, s->ret, s->err, s->step);
}

static int run_tests(const struct cp_test *tests, int n, const char *dir, int timeout_ms, char *out, size_t len)
{
  size_t pos = 0;
  out[0] = '\0';
  struct cp_ctx ctx;
  memset(&ctx, 0, sizeof ctx);
  if (snprintf(ctx.file, sizeof ctx.file, "%s/file", dir) >= (int)sizeof ctx.file ||
      snprintf(ctx.root, sizeof ctx.root, "%s/root", dir) >= (int)sizeof ctx.root) {
    append(out, len, &pos, "capprobe 1 failed errno=%d\n", ENAMETOOLONG);
    return -1;
  }
  ctx.uid = (unsigned)getuid();
  ctx.page = sysconf(_SC_PAGESIZE);

  size_t size = (size_t)n * sizeof(struct cp_slot);
  volatile struct cp_slot *slots = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
  if (slots == MAP_FAILED) {
    append(out, len, &pos, "capprobe 1 failed errno=%d\n", errno);
    return -1;
  }

  /* fork(), raw: nothing registered with pthread_atfork runs, here or in the helper. */
  long pid = syscall(__NR_clone, (long)SIGCHLD, 0L, 0L, 0L, 0L);
  if (pid == 0) helper(tests, n, slots, &ctx);
  if (pid < 0) {
    append(out, len, &pos, "capprobe 1 failed errno=%d\n", errno);
    munmap((void *)slots, size);
    return -1;
  }

  struct timespec start;
  clock_gettime(CLOCK_MONOTONIC, &start);
  int status = 0, timed_out = 0, wait_err = 0;
  for (;;) {
    long r = waitpid((pid_t)pid, &status, WNOHANG);
    if (r == pid) break;
    if (r < 0 && errno != EINTR) {
      wait_err = errno;
      break;
    }
    if (elapsed_ms(&start) >= timeout_ms) {
      /* A test that never returned: the helper goes, and the test with it (PR_SET_PDEATHSIG). */
      kill((pid_t)pid, SIGKILL);
      while (waitpid((pid_t)pid, &status, 0) < 0 && errno == EINTR) {}
      timed_out = 1;
      break;
    }
    struct timespec nap = { 0, 200 * 1000 };
    nanosleep(&nap, NULL);
  }

  int ok = 0;
  if (timed_out) append(out, len, &pos, "capprobe 1 timeout\n");
  else if (wait_err) append(out, len, &pos, "capprobe 1 failed errno=%d\n", wait_err);
  else if (WIFSIGNALED(status)) append(out, len, &pos, "capprobe 1 failed signal=%d\n", WTERMSIG(status));
  else if (WEXITSTATUS(status) != 0) append(out, len, &pos, "capprobe 1 failed exit=%d\n", WEXITSTATUS(status));
  else {
    append(out, len, &pos, "capprobe 1 ok\n");
    ok = 1;
  }
  /* After a lost wait the helper may still be writing: its results cannot be trusted. */
  if (!wait_err)
    for (int i = 0; i < n; i++) report_test(&tests[i], &slots[i], out, len, &pos);
  munmap((void *)slots, size);
  return ok ? 0 : -1;
}

int capprobe_run(const char *dir, int timeout_ms, char *out, size_t len)
{
  return run_tests(TESTS, NTESTS, dir, timeout_ms, out, len);
}
