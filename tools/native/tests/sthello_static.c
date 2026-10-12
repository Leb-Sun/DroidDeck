#include <stdio.h>
#include <pthread.h>
static void*w(void*a){return a;}
int main(){ pthread_t t; pthread_create(&t,0,w,0); pthread_join(t,0); printf("static hello ok\n"); return 0; }
