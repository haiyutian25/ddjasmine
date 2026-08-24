#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

// exec_bridge: dlopen-based execution shim. Android 10+ mounts the app's
// filesDir noexec, so a plain execve of an extracted executable fails. This
// shim instead dlopen()s the executable (which must be a PIE built with
// -fPIE -pie and export `main`), resolves `main`, and runs it on a fresh
// thread — the Termux/termux-exec approach.

typedef int (*main_fn)(int, char **);

typedef struct {
    main_fn fn;
    int argc;
    char **argv;
} main_args_t;

static void *run_main_thread(void *p) {
    main_args_t *a = (main_args_t *)p;
    int rc = a->fn(a->argc, a->argv);
    return (void *)(intptr_t)rc;
}

JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeRun(
    JNIEnv *env, jobject thiz, jstring path, jobjectArray args) {
    (void)thiz;
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (!path_str) {
        return -1; // path unreadable
    }

    void *handle = dlopen(path_str, RTLD_NOW);
    if (!handle) {
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return -2; // dlopen failed (noexec / not a PIE / missing deps)
    }

    main_fn main_func = (main_fn)dlsym(handle, "main");
    if (!main_func) {
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return -3; // no `main` symbol
    }

    // C 约定 argv[0] 是程序名：此前直接把用户参数放进 argv[0..]，导致被加载
    // 程序的第一个真实参数被当成程序名静默忽略、argc 少 1。这里补上
    // argv[0] = 可执行文件路径，用户参数整体后移一位。
    jsize user_argc = args ? (*env)->GetArrayLength(env, args) : 0;
    int argc = (int)user_argc + 1;
    char **argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) {
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return -4;
    }
    argv[0] = strdup(path_str);
    for (int i = 0; i < user_argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i + 1] = cs ? strdup(cs) : strdup("");
        if (cs) {
            (*env)->ReleaseStringUTFChars(env, s, cs);
        }
        (*env)->DeleteLocalRef(env, s);
    }
    argv[argc] = NULL;

    main_args_t a = {main_func, argc, argv};
    pthread_t t;
    if (pthread_create(&t, NULL, run_main_thread, &a) != 0) {
        for (int i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return -5; // thread spawn failed
    }
    void *rc;
    pthread_join(t, &rc);

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    dlclose(handle);
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    return (jint)(intptr_t)rc;
}

// Probe: whether dlopen()ing a PIE executable is supported at all on this
// device/linker. Returns 0 when the shim is loaded (linker is the bionic
// that supports PIE dlopen), so the Kotlin side can flip dlopenBridgeAvailable.
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeBridgeProbe(
    JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return 0;
}
