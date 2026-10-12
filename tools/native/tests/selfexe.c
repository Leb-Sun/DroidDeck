#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
int main(int argc,char**av){
  char b[4096]; ssize_t n=readlink("/proc/self/exe",b,sizeof b-1); if(n>0){b[n]=0;} else strcpy(b,"<err>");
  printf("    /proc/self/exe -> %s\n", b);
  printf("    argv0=%s\n", av[0]);
  if(argc<2){ printf("    re-exec via /proc/self/exe...\n"); char*na[]={av[0],"reexeced",NULL}; execv("/proc/self/exe",na); perror("    execv"); return 3;}
  printf("    reexec OK (argv1=%s)\n", av[1]);
  return 0;
}
