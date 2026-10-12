/*
 * glibc patch 0007 check (tools/glibc/patches): every glibc path that issues
 * close_range -- close_range itself, closefrom, and posix_spawn's closefrom
 * action -- must still close what it is asked to when BL_NATIVE_BLOCK marks
 * close_range blocked, without issuing the syscall.  Run untraced under
 * `seccomp-launch --profile android12` (close_range trapped): with bit 0x8 set
 * (or the variable unset) it passes; with the bit clear the first call dies of
 * SIGSYS, which shows the gate is what saved it.  Each sub-test prints a line;
 * exit 0 iff all pass.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
static int fails;

static void check(const char *name, int ok) {
  printf("%-26s %s\n", name, ok ? "ok" : "FAIL");
  if (!ok) fails++;
}

/* Opens n descriptors on /dev/null at or above `from`; returns the highest. */
static int open_some(int from, int n) {
  int top = -1;
  for (int i = 0; i < n; i++) {
    int fd = open("/dev/null", O_RDONLY);
    if (fd < 0) return -1;
    if (fd < from) { int d = fcntl(fd, F_DUPFD, from); close(fd); fd = d; }
    if (fd > top) top = fd;
  }
  return top;
}

static int any_open(int from, int to) {
  for (int fd = from; fd <= to; fd++)
    if (fcntl(fd, F_GETFD) != -1) return 1;
  return 0;
}

int main(void) {
  /* 1. close_range: closes the range, or answers ENOSYS (gated) and leaves it. */
  int top = open_some(10, 4);
  int r = close_range(10, (unsigned) top, 0);
  int e = errno;
  if (r == 0) check("close_range", !any_open(10, top));
  else {
    check("close_range ENOSYS", r == -1 && e == ENOSYS && any_open(10, top));
    for (int fd = 10; fd <= top; fd++) close(fd);
  }

  /* 2. closefrom: falls back to /proc/self/fd when close_range is gated. */
  top = open_some(20, 4);
  closefrom(20);
  check("closefrom", top >= 20 && !any_open(20, top));

  /* 3. posix_spawn's closefrom action: the child inherits nothing from 3 up,
        so `ls /proc/self/fd` lists 0 1 2 and its own directory fd only. */
  top = open_some(30, 4);
  int pipefd[2];
  if (pipe(pipefd) != 0) return 2;
  posix_spawn_file_actions_t fa;
  posix_spawn_file_actions_init(&fa);
  posix_spawn_file_actions_adddup2(&fa, pipefd[1], 1);
  posix_spawn_file_actions_addclosefrom_np(&fa, 3);
  pid_t pid;
  char *av[] = { "/bin/ls", "/proc/self/fd", NULL };
  int rc = posix_spawn(&pid, "/bin/ls", &fa, NULL, av, environ);
  close(pipefd[1]);
  char buf[256] = { 0 };
  size_t got = 0;
  ssize_t n;
  while (got < sizeof buf - 1 && (n = read(pipefd[0], buf + got, sizeof buf - 1 - got)) > 0) got += n;
  int st = -1;
  if (rc == 0) waitpid(pid, &st, 0);
  int count = 0;
  for (char *t = strtok(buf, "\n"); t; t = strtok(NULL, "\n")) count++;
  /* 0, 1, 2 and the directory ls reads: nothing at 30 and up came along. */
  check("posix_spawn closefrom", rc == 0 && WIFEXITED(st) && WEXITSTATUS(st) == 0 && count == 4);
  posix_spawn_file_actions_destroy(&fa);
  for (int fd = 30; fd <= top; fd++) close(fd);

  printf("close_range battery: %s\n", fails ? "FAILED" : "all ok");
  return fails ? 1 : 0;
}
