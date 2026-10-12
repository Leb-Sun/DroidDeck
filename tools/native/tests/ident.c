#include <stdio.h>
#include <sys/auxv.h>
#include <unistd.h>
int main(){char c[64]={0};FILE*f=fopen("/proc/self/comm","r");if(f){fgets(c,sizeof c,f);fclose(f);}for(int i=0;c[i];i++)if(c[i]=='\n')c[i]=0;
printf("comm=%s\n",c);printf("AT_EXECFN=%s\n",(char*)getauxval(AT_EXECFN));
char b[4096];ssize_t n=readlink("/proc/self/exe",b,sizeof b-1);if(n>0){b[n]=0;printf("exe=%s\n",b);}return 0;}
