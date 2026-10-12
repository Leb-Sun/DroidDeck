/*
 * The app's own calls into the kernel, for core/HostSys.kt: today the capability probe
 * (capprobe.c), run from the session's start thread.
 */
#include <jni.h>

#include "capprobe.h"

JNIEXPORT jstring JNICALL
Java_com_droiddeck_launcher_core_HostSys_nativeProbe(JNIEnv *env, jclass clazz, jstring dir, jint timeout_ms)
{
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, dir, NULL);
    if (path == NULL) return NULL;
    char out[CAPPROBE_OUT_MAX];
    capprobe_run(path, timeout_ms, out, sizeof out);
    (*env)->ReleaseStringUTFChars(env, dir, path);
    return (*env)->NewStringUTF(env, out);
}
