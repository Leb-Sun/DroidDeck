/*
 * libpathxlat: an LD_PRELOAD that translates absolute guest paths to host
 * paths in-process (S3), using pathxlat_core.h, for the case where the rootfs
 * is NOT mounted at / and there is no proot.  It covers the libc path
 * wrappers and the libc-internal callers that reach them through the public
 * symbols (fopen -> open, realpath -> readlink/stat, opendir, nss, dlopen of
 * an absolute path, posix_spawn's path).  getcwd / readlink results are
 * detranslated back to guest paths.
 *
 * WHERE THE TABLE LIVES: environment (BL_XLAT_ROOT, BL_XLAT_BINDS), read once.
 * Justification (04-spikes.md S3): ld.so runs before any preload and must have
 * the table too, and ld.so has the environment on its initial stack but no
 * file yet, so an env table is the only source available to *both* ld.so and
 * libc without a chicken-and-egg open.  A preload cannot translate ld.so's own
 * library search; that must be built into the patched glibc's ld.so (layer 3).
 * This preload stands in for the libc-and-above half and measures its cost; in
 * the test, ld.so's own search is satisfied with --library-path/--inhibit-cache.
 *
 * Spike code.  Build: cc -shared -fPIC -o libpathxlat.so libpathxlat.c -ldl
 */
#define _GNU_SOURCE
#include "pathxlat_core.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdlib.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>
#include <stdio.h>

static struct px_table T;
static int on;
/* cost counters */
static unsigned long n_xlat;

static void __attribute__((constructor)) px_init(void)
{
  const char *root = getenv("BL_XLAT_ROOT");
  const char *binds = getenv("BL_XLAT_BINDS");
  if (root && *root && px_parse(&T, root, binds ? binds : "") == 0) on = 1;
}

/* Translate an absolute guest path into a thread-local host buffer.  Returns
   the buffer (always valid) or the original for relative paths / when off. */
static const char *X(const char *p, char *buf, size_t cap)
{
  if (!on || !p || p[0] != '/') return p;
  __atomic_fetch_add(&n_xlat, 1, __ATOMIC_RELAXED);
  if (px_to_host(&T, p, buf, cap) < 0) return p;
  return buf;
}

#define DECL(ret, name, ...) static ret (*real_##name)(__VA_ARGS__);
#define LOAD(name) do { if (!real_##name) real_##name = dlsym(RTLD_NEXT, #name); } while (0)

/* ---- open family ---- */
int open(const char *p, int fl, ...) {
  static int (*r)(const char*,int,...); if(!r) r=dlsym(RTLD_NEXT,"open");
  char b[PX_PATH_MAX]; mode_t m=0; if(fl&O_CREAT){va_list a;va_start(a,fl);m=va_arg(a,int);va_end(a);}
  return r(X(p,b,sizeof b), fl, m);
}
int open64(const char *p, int fl, ...) {
  static int (*r)(const char*,int,...); if(!r) r=dlsym(RTLD_NEXT,"open64");
  char b[PX_PATH_MAX]; mode_t m=0; if(fl&O_CREAT){va_list a;va_start(a,fl);m=va_arg(a,int);va_end(a);}
  return r(X(p,b,sizeof b), fl, m);
}
int openat(int d, const char *p, int fl, ...) {
  static int (*r)(int,const char*,int,...); if(!r) r=dlsym(RTLD_NEXT,"openat");
  char b[PX_PATH_MAX]; mode_t m=0; if(fl&O_CREAT){va_list a;va_start(a,fl);m=va_arg(a,int);va_end(a);}
  return r(d, (d==AT_FDCWD||p[0]=='/')?X(p,b,sizeof b):p, fl, m);
}
int openat64(int d, const char *p, int fl, ...) {
  static int (*r)(int,const char*,int,...); if(!r) r=dlsym(RTLD_NEXT,"openat64");
  char b[PX_PATH_MAX]; mode_t m=0; if(fl&O_CREAT){va_list a;va_start(a,fl);m=va_arg(a,int);va_end(a);}
  return r(d, (d==AT_FDCWD||p[0]=='/')?X(p,b,sizeof b):p, fl, m);
}
FILE *fopen(const char *p, const char *mode) {
  static FILE*(*r)(const char*,const char*); if(!r) r=dlsym(RTLD_NEXT,"fopen");
  char b[PX_PATH_MAX]; return r(X(p,b,sizeof b), mode);
}
FILE *fopen64(const char *p, const char *mode) {
  static FILE*(*r)(const char*,const char*); if(!r) r=dlsym(RTLD_NEXT,"fopen64");
  char b[PX_PATH_MAX]; return r(X(p,b,sizeof b), mode);
}

/* ---- stat family (glibc exposes __xstat/__lxstat/__fxstatat in 2.43 for ABI;
   the public stat/lstat/fstatat forward to statx/newfstatat) ---- */
int stat(const char *p, struct stat *st){ static int(*r)(const char*,struct stat*); if(!r)r=dlsym(RTLD_NEXT,"stat"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b),st);}
int stat64(const char *p, struct stat64 *st){ static int(*r)(const char*,struct stat64*); if(!r)r=dlsym(RTLD_NEXT,"stat64"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b),st);}
int lstat(const char *p, struct stat *st){ static int(*r)(const char*,struct stat*); if(!r)r=dlsym(RTLD_NEXT,"lstat"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b),st);}
int lstat64(const char *p, struct stat64 *st){ static int(*r)(const char*,struct stat64*); if(!r)r=dlsym(RTLD_NEXT,"lstat64"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b),st);}
int fstatat(int d, const char *p, struct stat *st, int f){ static int(*r)(int,const char*,struct stat*,int); if(!r)r=dlsym(RTLD_NEXT,"fstatat"); char b[PX_PATH_MAX]; return r(d,(d==AT_FDCWD||p[0]=='/')?X(p,b,sizeof b):p,st,f);}
int fstatat64(int d, const char *p, struct stat64 *st, int f){ static int(*r)(int,const char*,struct stat64*,int); if(!r)r=dlsym(RTLD_NEXT,"fstatat64"); char b[PX_PATH_MAX]; return r(d,(d==AT_FDCWD||p[0]=='/')?X(p,b,sizeof b):p,st,f);}
int statx(int d, const char *p, int f, unsigned m, struct statx *stx){ static int(*r)(int,const char*,int,unsigned,struct statx*); if(!r)r=dlsym(RTLD_NEXT,"statx"); char b[PX_PATH_MAX]; return r(d,(d==AT_FDCWD||p[0]=='/')?X(p,b,sizeof b):p,f,m,stx);}

/* ---- access ---- */
int access(const char *p, int m){ static int(*r)(const char*,int); if(!r)r=dlsym(RTLD_NEXT,"access"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b),m);}
int faccessat(int d, const char *p, int m, int f){ static int(*r)(int,const char*,int,int); if(!r)r=dlsym(RTLD_NEXT,"faccessat"); char b[PX_PATH_MAX]; return r(d,(d==AT_FDCWD||p[0]=='/')?X(p,b,sizeof b):p,m,f);}

/* ---- readlink / realpath (detranslate result) ---- */
ssize_t readlink(const char *p, char *buf, size_t sz){
  static ssize_t(*r)(const char*,char*,size_t); if(!r)r=dlsym(RTLD_NEXT,"readlink");
  char b[PX_PATH_MAX]; ssize_t n=r(X(p,b,sizeof b),buf,sz);
  if(n>0 && (size_t)n<sz){ buf[n]=0; char g[PX_PATH_MAX]; if(buf[0]=='/' && px_to_guest(&T,buf,g,sizeof g)>=0){ size_t gl=strlen(g); if(gl<sz){memcpy(buf,g,gl); n=gl;} } }
  return n;
}
ssize_t readlinkat(int d, const char *p, char *buf, size_t sz){
  static ssize_t(*r)(int,const char*,char*,size_t); if(!r)r=dlsym(RTLD_NEXT,"readlinkat");
  char b[PX_PATH_MAX]; ssize_t n=r(d,(d==AT_FDCWD||p[0]=='/')?X(p,b,sizeof b):p,buf,sz);
  if(n>0 && (size_t)n<sz){ buf[n]=0; char g[PX_PATH_MAX]; if(buf[0]=='/' && px_to_guest(&T,buf,g,sizeof g)>=0){ size_t gl=strlen(g); if(gl<sz){memcpy(buf,g,gl); n=gl;} } }
  return n;
}
char *realpath(const char *p, char *out){
  static char*(*r)(const char*,char*); if(!r)r=dlsym(RTLD_NEXT,"realpath");
  char b[PX_PATH_MAX], tmp[PX_PATH_MAX];
  char *res=r(X(p,b,sizeof b), out?out:tmp);
  if(res){ char g[PX_PATH_MAX]; if(res[0]=='/' && px_to_guest(&T,res,g,sizeof g)>=0){ if(out){strcpy(out,g); return out;} strcpy(tmp,g);} if(!out){ char*d=malloc(strlen(tmp)+1); if(d)strcpy(d,tmp); return d;} }
  return res;
}

/* ---- getcwd (detranslate) ---- */
char *getcwd(char *buf, size_t sz){
  static char*(*r)(char*,size_t); if(!r)r=dlsym(RTLD_NEXT,"getcwd");
  char tmp[PX_PATH_MAX]; char *res=r(tmp,sizeof tmp);
  if(!res) return r(buf,sz);
  char g[PX_PATH_MAX]; const char *out=tmp;
  if(tmp[0]=='/' && px_to_guest(&T,tmp,g,sizeof g)>=0) out=g;
  size_t n=strlen(out)+1;
  if(buf){ if(n>sz){errno=ERANGE; return NULL;} memcpy(buf,out,n); return buf; }
  char *d=malloc(n); if(d) memcpy(d,out,n); return d;
}

/* ---- opendir ---- */
DIR *opendir(const char *p){ static DIR*(*r)(const char*); if(!r)r=dlsym(RTLD_NEXT,"opendir"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b)); }

/* ---- dlopen of an absolute path ---- */
void *dlopen(const char *p, int fl){ static void*(*r)(const char*,int); if(!r)r=dlsym(RTLD_NEXT,"dlopen"); if(p&&p[0]=='/'){char b[PX_PATH_MAX]; return r(X(p,b,sizeof b),fl);} return r(p,fl); }

/* ---- a few write ops used by tests ---- */
int unlink(const char *p){ static int(*r)(const char*); if(!r)r=dlsym(RTLD_NEXT,"unlink"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b)); }
int mkdir(const char *p, mode_t m){ static int(*r)(const char*,mode_t); if(!r)r=dlsym(RTLD_NEXT,"mkdir"); char b[PX_PATH_MAX]; return r(X(p,b,sizeof b),m); }

/* report translation count at exit if asked */
static void __attribute__((destructor)) px_fini(void){ if(getenv("BL_XLAT_STATS")) fprintf(stderr,"[pathxlat] translations=%lu\n", n_xlat); }
