/*
 * Shared seccomp policy table and filter builder for the native-mode spike,
 * included by seccomp-launch.c and trap-trace.c.  See seccomp-launch.c for the
 * description of Android's app policy and the profiles.  Spike code.
 */
#ifndef DROIDDECK_SPIKE_POLICY_INC_H
#define DROIDDECK_SPIKE_POLICY_INC_H

#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#if defined(__x86_64__)
#  define HOST_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#  define HOST_AUDIT_ARCH AUDIT_ARCH_AARCH64
#else
#  error "unsupported host arch for the spike"
#endif

struct sc { const char *name; int x86_64; int aarch64; };

/* Numbers verified on this host (nrs-x86_64.txt) and from asm-generic/unistd.h
   for arm64 (nrs-aarch64.txt). */
static const struct sc TABLE[] = {
  { "rseq",             334, 293 },
  { "set_robust_list",  273,  99 },
  { "get_robust_list",  274, 100 },
  { "futex_waitv",      449, 449 },
  { "io_uring_setup",   425, 425 },
  { "io_uring_enter",   426, 426 },
  { "io_uring_register",427, 427 },
  { "shmget",            29, 194 },
  { "shmat",             30, 196 },
  { "shmctl",            31, 195 },
  { "shmdt",             67, 197 },
  { "semget",            64, 190 },
  { "semop",             65, 193 },
  { "semtimedop",       220, 192 },
  { "semctl",            66, 191 },
  { "msgget",            68, 186 },
  { "msgsnd",            69, 189 },
  { "msgrcv",            70, 188 },
  { "msgctl",            71, 187 },
  { "setuid",           105, 146 },
  { "setgid",           106, 144 },
  { "setreuid",         113, 145 },
  { "setregid",         114, 143 },
  { "setfsuid",         122, 151 },
  { "setfsgid",         123, 152 },
  { "setgroups",        116, 159 },
  { "mount",            165,  40 },
  { "umount2",          166,  39 },
  { "chroot",           161,  51 },
  { "open_tree",        428, 428 },
  { "move_mount",       429, 429 },
  { "fsopen",           430, 430 },
  { "fsconfig",         431, 431 },
  { "fsmount",          432, 432 },
  { "fspick",           433, 433 },
  { "mount_setattr",    442, 442 },
  { "fanotify_init",    300, 262 },
  { "fanotify_mark",    301, 263 },
  { "name_to_handle_at",303, 264 },
  { "open_by_handle_at",304, 265 },
  { "kcmp",             312, 272 },
  { "memfd_secret",     447, 447 },
  { "cachestat",        451, 451 },
  { "map_shadow_stack", 453, 453 },
  { "process_mrelease", 448, 448 },
  { "keyctl",           250, 219 },
  { "mbind",            237, 235 },
  { "set_mempolicy",    238, 237 },
  { "clone3",           435, 435 },
  { "faccessat2",       439, 439 },
  { "fchmodat2",        452, 452 },
  { "openat2",          437, 437 },
  { "close_range",      436, 436 },
};
#define NTAB ((int)(sizeof TABLE / sizeof TABLE[0]))

static const char *ALWAYS_ALLOW[] = {
  "setresuid", "setresgid", "ptrace", "seccomp", "prctl", "unshare", "setns",
  "pivot_root", "execve", "execveat", "openat", "faccessat", "fchmodat",
  "futex", "clone", NULL,
};

/* P12 is android14's policy plus close_range, which Android blocks up to 12
   (glibc patch 0007, tools/glibc); every later profile lets it through.  */
enum { P14, P15, P16Q, P12 };

static int profile_blocks(int prof, const char *name)
{
  if (!strcmp(name, "close_range")) return prof == P12;
  int gated = !strcmp(name, "clone3") || !strcmp(name, "faccessat2")
           || !strcmp(name, "fchmodat2") || !strcmp(name, "openat2");
  if (!gated) return 1;
  if (prof == P16Q) return 0;
  if (prof == P15) return !strcmp(name, "clone3") ? 0 : 1;
  return 1;
}

static int in_list(const char *csv, const char *name)
{
  if (!csv) return 0;
  size_t n = strlen(name);
  const char *p = csv;
  while (*p) {
    const char *e = strchrnul(p, ',');
    if ((size_t)(e - p) == n && !strncmp(p, name, n)) return 1;
    p = *e ? e + 1 : e;
  }
  return 0;
}

/* Build and install the filter for this host arch; returns #blocked or -1. */
static int install_filter(int prof, const char *allow_csv, const char *extra_csv)
{
  struct sock_filter head[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, HOST_AUDIT_ARCH, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
  };
  int nhead = (int)(sizeof head / sizeof head[0]);

  int blocked[NTAB], nb = 0;
  for (int i = 0; i < NTAB; i++) {
    const char *nm = TABLE[i].name;
    if (TABLE[i].x86_64 < 0) continue;
    if (in_list(allow_csv, nm)) continue;
    if (profile_blocks(prof, nm) || in_list(extra_csv, nm)) blocked[nb++] = i;
  }

  int nprog = nhead + nb * 2 + 1;
  struct sock_filter *prog = calloc(nprog, sizeof *prog);
  int k = 0;
  for (int i = 0; i < nhead; i++) prog[k++] = head[i];
  for (int i = 0; i < nb; i++) {
    struct sock_filter cmp =
      BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, TABLE[blocked[i]].x86_64, 0, 1);
    prog[k++] = cmp;
    struct sock_filter trap = BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    prog[k++] = trap;
  }
  prog[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);

  struct sock_fprog fprog = { .len = (unsigned short)k, .filter = prog };
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) { perror("no_new_privs"); free(prog); return -1; }
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &fprog) != 0) {
    perror("seccomp"); free(prog); return -1;
  }
  free(prog);
  return nb;
}

#endif /* policy.inc.h */
