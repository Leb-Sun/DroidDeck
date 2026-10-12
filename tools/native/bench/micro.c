/*
 * micro: tiny per-call / workload benchmark for the native-mode spike (S4).
 * Modes:
 *   stat PATH N      - N x lstat(PATH), prints ns/op
 *   open PATH N      - N x open(PATH,O_RDONLY)+close, prints ns/op
 *   forkexec PROG N  - N x fork()+execve(PROG,{PROG,0})+wait, prints us/op
 * Host numbers are relative only.  Spike code.
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/wait.h>

static double now_ns(void){ struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec*1e9+t.tv_nsec; }

int main(int argc, char **argv){
  if (argc < 4) { fprintf(stderr,"usage: micro {stat|open|forkexec} ARG N\n"); return 2; }
  const char *mode = argv[1], *arg = argv[2];
  long n = atol(argv[3]);
  double t0 = now_ns();
  long ok = 0;
  if (!strcmp(mode,"stat")) {
    struct stat st;
    for (long i=0;i<n;i++) if (lstat(arg,&st)==0) ok++;
    printf("stat %s: %.1f ns/op (%ld/%ld ok)\n", arg, (now_ns()-t0)/n, ok, n);
  } else if (!strcmp(mode,"open")) {
    for (long i=0;i<n;i++){ int fd=open(arg,O_RDONLY); if(fd>=0){ok++; close(fd);} }
    printf("open %s: %.1f ns/op (%ld/%ld ok)\n", arg, (now_ns()-t0)/n, ok, n);
  } else if (!strcmp(mode,"forkexec")) {
    char *av[]={ (char*)arg, NULL };
    extern char **environ;
    for (long i=0;i<n;i++){
      pid_t p=fork();
      if (p==0){ execve(arg,av,environ); _exit(127); }
      int st; waitpid(p,&st,0); if (WIFEXITED(st)&&WEXITSTATUS(st)==0) ok++;
    }
    printf("forkexec %s: %.1f us/op (%ld/%ld ok)\n", arg, (now_ns()-t0)/n/1000.0, ok, n);
  } else { fprintf(stderr,"bad mode\n"); return 2; }
  return 0;
}
