#include <dlfcn.h>
#include <stdio.h>
int main(int c,char**v){
  const char *lib = c>1?v[1]:"/usr/lib/x86_64-linux-gnu/libz.so.1";
  void *h=dlopen(lib, RTLD_NOW);
  if(!h){ printf("dlopen FAIL: %s\n", dlerror()); return 1; }
  void *s=dlsym(h,"zlibVersion");
  printf("dlopen ok: %s  zlibVersion sym=%p\n", lib, s);
  return 0;
}
