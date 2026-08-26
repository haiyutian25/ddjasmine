#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
/* 探针 A/B/C 所需：execve 真实文件、ptrace、seccomp。 */
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/prctl.h>
#include <signal.h>
#include <elf.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
/* E4 PTY 支持：伪终端（交互式 shell 必需）。
 * Android NDK bionic 无 pty.h/openpty；用 posix_openpt 系列手动实现。 */
#include <termios.h>
#include <sys/select.h>
/* E16/E21：native spawn 的 setrlimit 资源限制。 */
#include <sys/resource.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

#ifndef SYS_memfd_create
#define SYS_memfd_create 279 /* arm64 */
#endif

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

/* env/cwd 注入（《PRoot插件可行性与缺口分析》§5.4）：dlopen 路径在宿主进程内
 * 运行，环境变量与工作目录的注入只能是进程级、运行期间生效、结束后尽力还原。
 * env_pairs 为 "KEY=VALUE" 字符串数组；work_dir 为 NULL 时不改 cwd。 */
typedef struct {
    char *key;
    char *old_value; /* NULL 表示原先不存在（还原时 unsetenv） */
    int had_old;
} env_restore_t;

JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeRun(
    JNIEnv *env, jobject thiz, jstring path, jobjectArray args,
    jobjectArray envPairs, jstring workDir) {
    (void)thiz;
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    if (!path_str) {
        return -1; // path unreadable
    }

    /* --- cwd 注入：记住原目录，运行结束后还原 --- */
    char *saved_cwd = NULL;
    const char *work_dir_str = NULL;
    if (workDir) {
        work_dir_str = (*env)->GetStringUTFChars(env, workDir, NULL);
        if (work_dir_str) {
            char cwd_buf[4096];
            if (getcwd(cwd_buf, sizeof(cwd_buf))) {
                saved_cwd = strdup(cwd_buf);
            }
            if (chdir(work_dir_str) != 0) {
                free(saved_cwd);
                (*env)->ReleaseStringUTFChars(env, workDir, work_dir_str);
                (*env)->ReleaseStringUTFChars(env, path, path_str);
                return -6; // workDir 切换失败
            }
        }
    }

    /* --- env 注入：逐项记录旧值，运行结束后还原 --- */
    jsize env_count = envPairs ? (*env)->GetArrayLength(env, envPairs) : 0;
    env_restore_t *restores = NULL;
    if (env_count > 0) {
        restores = (env_restore_t *)calloc((size_t)env_count, sizeof(env_restore_t));
    }
    int applied = 0;
    for (int i = 0; i < env_count; i++) {
        jstring pair = (jstring)(*env)->GetObjectArrayElement(env, envPairs, i);
        const char *cs = pair ? (*env)->GetStringUTFChars(env, pair, NULL) : NULL;
        if (!cs) continue;
        const char *eq = strchr(cs, '=');
        if (eq && eq != cs) {
            size_t key_len = (size_t)(eq - cs);
            char *key = (char *)malloc(key_len + 1);
            memcpy(key, cs, key_len);
            key[key_len] = '\0';
            const char *value = eq + 1;
            restores[applied].key = key;
            const char *old = getenv(key);
            restores[applied].had_old = (old != NULL);
            restores[applied].old_value = old ? strdup(old) : NULL;
            setenv(key, value, 1);
            applied++;
        }
        (*env)->ReleaseStringUTFChars(env, pair, cs);
        (*env)->DeleteLocalRef(env, pair);
    }

    void *handle = dlopen(path_str, RTLD_NOW);
    if (!handle) {
        goto restore_and_fail_dlopen;
    }

    main_fn main_func = (main_fn)dlsym(handle, "main");
    if (!main_func) {
        dlclose(handle);
        goto restore_and_fail_dlopen;
    }

    // C 约定 argv[0] 是程序名：此前直接把用户参数放进 argv[0..]，导致被加载
    // 程序的第一个真实参数被当成程序名静默忽略、argc 少 1。这里补上
    // argv[0] = 可执行文件路径，用户参数整体后移一位。
    {
        jsize user_argc = args ? (*env)->GetArrayLength(env, args) : 0;
        int argc = (int)user_argc + 1;
        char **argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
        if (!argv) {
            dlclose(handle);
            goto restore_and_fail_dlopen;
        }
        argv[0] = strdup(path_str);
        for (int i = 0; i < user_argc; i++) {
            jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
            const char *acs = (*env)->GetStringUTFChars(env, s, NULL);
            argv[i + 1] = acs ? strdup(acs) : strdup("");
            if (acs) {
                (*env)->ReleaseStringUTFChars(env, s, acs);
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
            goto restore_and_fail_dlopen;
        }
        void *rc;
        pthread_join(t, &rc);

        for (int i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        dlclose(handle);

        /* --- 还原 env / cwd（尽力而为） --- */
        for (int i = 0; i < applied; i++) {
            if (restores[i].had_old && restores[i].old_value) {
                setenv(restores[i].key, restores[i].old_value, 1);
            } else {
                unsetenv(restores[i].key);
            }
            free(restores[i].key);
            free(restores[i].old_value);
        }
        free(restores);
        if (saved_cwd) {
            (void)chdir(saved_cwd);
            free(saved_cwd);
        }
        if (work_dir_str) (*env)->ReleaseStringUTFChars(env, workDir, work_dir_str);
        (*env)->ReleaseStringUTFChars(env, path, path_str);
        return (jint)(intptr_t)rc;
    }

restore_and_fail_dlopen:
    for (int i = 0; i < applied; i++) {
        if (restores[i].had_old && restores[i].old_value) {
            setenv(restores[i].key, restores[i].old_value, 1);
        } else {
            unsetenv(restores[i].key);
        }
        free(restores[i].key);
        free(restores[i].old_value);
    }
    free(restores);
    if (saved_cwd) {
        (void)chdir(saved_cwd);
        free(saved_cwd);
    }
    if (work_dir_str) (*env)->ReleaseStringUTFChars(env, workDir, work_dir_str);
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    return -2; // dlopen failed (noexec / not a PIE / missing deps) or no main
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

/* ===================== PRoot 可行性探针（ProotProbe） =====================
 * 供"探针插件"在插件上下文调用，裁决 PRoot 插件路线。崩溃均隔离在子进程。
 * 返回约定：
 *   42            -> 成功（映射/匿名文件可执行）
 *   -errno        -> 被拒（如 -13 EACCES / -1 EPERM）
 *   -(1000+sig)   -> 执行时子进程被信号杀死（如 -1011 SIGSEGV）
 *   -2000/-2001   -> fork/wait 异常 / 执行了但结果不符
 */

/* 探针一：只读 mmap(PROT_READ|PROT_EXEC) [path] 并在子进程执行其内容。
 * 测"PRoot loader 用 mmap PROT_EXEC 装载 guest 二进制"（guest 装载路径）。 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ProotProbe_nativeMmapExecProbe(
    JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return -1;
    int fd = open(p, O_RDONLY);
    if (fd < 0) {
        int e = errno;
        (*env)->ReleaseStringUTFChars(env, path, p);
        return -e;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path, p);
        return -EIO;
    }
    size_t len = (size_t)st.st_size;
    void *m = mmap(NULL, len, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    int mmap_errno = errno;
    close(fd);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (m == MAP_FAILED) return -mmap_errno;

    pid_t pid = fork();
    if (pid < 0) { munmap(m, len); return -2000; }
    if (pid == 0) {
        int (*fn)(void) = (int (*)(void))m;
        int r = fn();
        _exit(r == 42 ? 42 : 99);
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) { munmap(m, len); return -2000; }
    munmap(m, len);
    if (WIFEXITED(status)) return (WEXITSTATUS(status) == 42) ? 42 : -2001;
    if (WIFSIGNALED(status)) return -(1000 + WTERMSIG(status));
    return -2000;
}

/* 探针二：memfd_create 匿名文件写入"退出码 42"的独立程序并 fexecve。
 * 测"proot/loader 能否经 memfd 从不可执行的插件目录被跑起来"（插件模型生死线）。 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ProotProbe_nativeMemfdExecProbe(
    JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    /* arm64 独立程序：mov w0,#42 ; mov w8,#93(exit) ; svc #0  → 以状态 42 退出 */
    static const unsigned char code[12] = {
        0x40, 0x05, 0x80, 0x52, /* movz w0, #42   */
        0xA8, 0x0B, 0x80, 0x52, /* movz w8, #93   */
        0x01, 0x00, 0x00, 0xD4, /* svc  #0        */
    };
    int fd = (int)syscall(SYS_memfd_create, "proot_probe", 0u);
    if (fd < 0) return -errno;
    if (write(fd, code, sizeof(code)) != (ssize_t)sizeof(code)) {
        close(fd);
        return -EIO;
    }
    pid_t pid = fork();
    if (pid < 0) { close(fd); return -2000; }
    if (pid == 0) {
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        execl(path, "proot_probe", (char *)NULL);
        _exit(errno); /* execl 返回即失败；用退出码回传真实 errno（13=EACCES 多为 SELinux） */
    }
    close(fd);
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -2000;
    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        if (code == 42) return 42;          /* 成功 */
        return -(3000 + code);              /* execl 失败，errno=code */
    }
    if (WIFSIGNALED(status)) return -(1000 + WTERMSIG(status));
    return -2000;
}

/* ===================== 地基探针 A/B/C（PRoot 专属链路） =====================
 * 对应《PRoot插件可行性与缺口分析》§4.5 的缺口 2/3/4。
 * 返回约定沿用：42=成功；-(3000+errno)=execve 被拒；-(1000+sig)=被信号杀死；
 * -2xxx / -4xxx / -5xxx = 各探针内部步骤失败。
 */

/* 探针 A：对"真实文件路径" fork+execve（区别于 memfd 探针的匿名文件）。
 * 用于测"框架执行底座落点"的 execve 是否被 SELinux 放行。
 * 执行底座归框架（core-plugin）：极小静态 PIE（libproot_probe_pie.so，退出码 42）
 * 打包在框架模块 core-plugin 的 jniLibs，运行时并入应用 nativeLibraryDir
 * （框架唯一可 execve 的位置；插件无自己的 nativeLibraryDir，见 InstallExecutor）。
 * 探针传入该 PIE 的绝对路径。
 *   42            -> 放行且 PIE 正常运行（框架执行底座成立，proot/loader 有落点）
 *   -(3000+13)    -> execve 被 SELinux 拒（EACCES）
 *   -(3000+8)     -> 放行但 ELF 不被识别（ENOEXEC，理论不应出现）
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ProotProbe_nativeExecFileProbe(
    JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return -1;
    pid_t pid = fork();
    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, path, p);
        return -2000;
    }
    if (pid == 0) {
        execl(p, "proot_probe_pie", (char *)NULL);
        _exit(errno); /* execve 返回即失败，用退出码回传真实 errno */
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -2000;
    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        if (code == 42) return 42;      /* PIE 正常运行并退出 42 */
        return -(3000 + code);          /* execve 失败，errno=code */
    }
    if (WIFSIGNALED(status)) return -(1000 + WTERMSIG(status));
    return -2000;
}

/* 探针 B：ptrace 子进程往返（PRoot 的命脉）。
 * 子进程 PTRACE_TRACEME + raise(SIGSTOP)；父进程 waitpid 见其停止后
 * PTRACE_GETREGSET 读寄存器，再 PTRACE_CONT 放行，子进程 _exit(42)。
 * 覆盖 PRoot 必需的 TRACEME / GETREGSET(=GETREGS) / CONT 三类请求。
 *   42            -> 附着 + 读寄存器 + 继续 全链路可用
 *   -2001         -> 子进程未按预期停止 / 结果不符
 *   -2002         -> PTRACE_CONT 失败
 *   -(4000+e)     -> 子进程 TRACEME 失败，errno=e
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ProotProbe_nativePtraceProbe(
    JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    pid_t pid = fork();
    if (pid < 0) return -2000;
    if (pid == 0) {
        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) < 0) _exit(110 + errno);
        raise(SIGSTOP); /* 被 tracer 附着后自停，等父进程读寄存器再继续 */
        _exit(42);
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) { kill(pid, SIGKILL); return -2000; }
    if (!WIFSTOPPED(status)) { kill(pid, SIGKILL); return -2001; }
    /* 读寄存器（arm64 用 GETREGSET + NT_PRSTATUS；等价于 PRoot 的 GETREGS）。 */
    char regs[1024];
    struct iovec iov;
    iov.iov_base = regs;
    iov.iov_len = sizeof(regs);
    long got = ptrace(PTRACE_GETREGSET, pid, (void *)(uintptr_t)NT_PRSTATUS, &iov);
    int regs_ok = (got == 0);
    if (ptrace(PTRACE_CONT, pid, NULL, NULL) < 0) { kill(pid, SIGKILL); return -2002; }
    if (waitpid(pid, &status, 0) < 0) return -2000;
    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        if (code == 42 && regs_ok) return 42;   /* 全链路成功 */
        if (code >= 110) return -(4000 + (code - 110)); /* TRACEME 失败 errno */
        return -2001;
    }
    if (WIFSIGNALED(status)) return -(1000 + WTERMSIG(status));
    return -2001;
}

/* 探针 C：seccomp BPF 安装（PRoot 的加速项，可用 PROOT_NO_SECCOMP 回退）。
 * 在子进程里 prctl(NO_NEW_PRIVS) 后装一条"全放行"的 BPF 过滤，装成即 _exit(42)。
 *   42          -> 允许安装 seccomp 过滤（PRoot 加速可用）
 *   -(5000+e)   -> 安装失败，errno=e（子进程退出码 60+e / 70+e 解码）
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ProotProbe_nativeSeccompProbe(
    JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    pid_t pid = fork();
    if (pid < 0) return -2000;
    if (pid == 0) {
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) _exit(60 + errno);
        struct sock_filter filter[] = {
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW), /* 全放行，仅测"能否安装" */
        };
        struct sock_fprog prog;
        prog.len = (unsigned short)(sizeof(filter) / sizeof(filter[0]));
        prog.filter = filter;
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) < 0) _exit(70 + errno);
        _exit(42);
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -2000;
    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        if (code == 42) return 42;
        if (code >= 60) return -(5000 + (code - 60)); /* NO_NEW_PRIVS 或装过滤失败 */
        return -2001;
    }
    if (WIFSIGNALED(status)) return -(1000 + WTERMSIG(status));
    return -2000;
}

/* 探针 D：system_linker_exec —— Termux 官方绕法，框架 runner 的增强方向。
 * 不直接 execve app_data_file 的二进制（被 SELinux 拒），改
 * execve("/system/bin/linker64", "linker64", <目标绝对路径>)：内核只看到执行了
 * linker64（system_linker_exec 标签，放行），由链接器 mmap 装载目标运行
 * （mmap PROT_EXEC app_data_file 已证放行，见探针二）。目标须为动态链接且绝对路径。
 * 测"框架能否据此执行插件目录里的 proot"（proot 留在插件、不必进 nativeLibraryDir）。
 *   42          -> 目标经 linker64 装载运行成功（框架 runner 可用 system_linker_exec）
 *   -(3000+e)   -> execve linker64 失败，errno=e
 *   -(1000+sig) -> 装载/运行中被信号杀死
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ProotProbe_nativeLinkerExecProbe(
    JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return -1;
    pid_t pid = fork();
    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, path, p);
        return -2000;
    }
    if (pid == 0) {
        /* system_linker_exec 约定：argv[1] 为待装载的目标（绝对路径）。 */
        execl("/system/bin/linker64", "linker64", p, (char *)NULL);
        _exit(errno);
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) return -2000;
    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        if (code == 42) return 42;
        return -(3000 + code);
    }
    if (WIFSIGNALED(status)) return -(1000 + WTERMSIG(status));
    return -2000;
}

/* ── 批次 2：信号发送（E5/E6）────────────────────────────────────────── */

/**
 * E5：向指定 pid 发送信号。成功返回 0，失败返回 -errno。
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeSignal(
    JNIEnv *env, jobject thiz, jint pid, jint sig) {
    (void)env; (void)thiz;
    if (kill((pid_t)pid, (int)sig) == 0) return 0;
    return -errno;
}

/**
 * E6：向指定进程组发送信号。成功返回 0，失败返回 -errno。
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeSignalGroup(
    JNIEnv *env, jobject thiz, jint pgid, jint sig) {
    (void)env; (void)thiz;
    if (killpg((pid_t)pgid, (int)sig) == 0) return 0;
    return -errno;
}

/* ── 批次 3：E4 PTY 支持 ─────────────────────────────────────────────── */

/**
 * E4：打开伪终端对（master/slave）。
 * Android NDK bionic 无 openpty()，用 posix_openpt 系列手动实现。
 * 成功返回 master fd 并通过 slave_fd_out 输出 slave fd；失败返回 -errno。
 */
static int open_pty_pair(int *slave_fd_out) {
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return -errno;
    if (grantpt(master) != 0) { int e = errno; close(master); return -e; }
    if (unlockpt(master) != 0) { int e = errno; close(master); return -e; }
    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        int e = errno; close(master); return -e;
    }
    int slave = open(slave_name, O_RDWR);
    if (slave < 0) { int e = errno; close(master); return -e; }
    *slave_fd_out = slave;
    return master;
}

/**
 * E4：PTY 子进程 spawn。
 *
 * 流程：openpty → 设初始窗口大小 → fork →
 *   子进程：setsid + TIOCSCTTY + dup2(slave→0/1/2) + chdir + execve(linker, binary, args)
 *   父进程：关 slave，返回 pid，master fd 写入 masterFdOut[0]。
 *
 * @return pid > 0 成功；负值 = -errno 或 -2000（fork 失败）。
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeSpawnWithPty(
    JNIEnv *env, jobject thiz,
    jstring linkerPath, jstring binaryPath, jobjectArray args,
    jobjectArray envPairs, jstring workDir,
    jint rows, jint cols, jintArray masterFdOut) {
    (void)thiz;

    /* ── 1. 提取所有 JNI 字符串（fork 前完成）── */
    const char *linker_str = (*env)->GetStringUTFChars(env, linkerPath, NULL);
    const char *binary_str = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    if (!linker_str || !binary_str) return -1;

    const char *workdir_str = NULL;
    if (workDir) {
        workdir_str = (*env)->GetStringUTFChars(env, workDir, NULL);
    }

    jsize argc_user = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = (char **)calloc((size_t)argc_user + 3, sizeof(char *));
    if (!argv) return -ENOMEM;
    /* argv[0] = linker, argv[1] = binary, argv[2..] = user args */
    argv[0] = strdup(linker_str);
    argv[1] = strdup(binary_str);
    for (int i = 0; i < argc_user; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *cs = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        argv[i + 2] = cs ? strdup(cs) : strdup("");
        if (cs) (*env)->ReleaseStringUTFChars(env, s, cs);
        if (s) (*env)->DeleteLocalRef(env, s);
    }
    argv[argc_user + 2] = NULL;

    jsize env_count = envPairs ? (*env)->GetArrayLength(env, envPairs) : 0;
    char **envp = (char **)calloc((size_t)env_count + 1, sizeof(char *));
    if (!envp) { /* cleanup argv */ return -ENOMEM; }
    for (int i = 0; i < env_count; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, envPairs, i);
        const char *cs = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        envp[i] = cs ? strdup(cs) : strdup("");
        if (cs) (*env)->ReleaseStringUTFChars(env, s, cs);
        if (s) (*env)->DeleteLocalRef(env, s);
    }
    envp[env_count] = NULL;

    /* ── 2. 打开 PTY 对 ── */
    int slave_fd = -1;
    int master_fd = open_pty_pair(&slave_fd);
    if (master_fd < 0) return master_fd; /* -errno */

    /* 设初始窗口大小。 */
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(master_fd, TIOCSWINSZ, &ws);

    /* ── 3. fork ── */
    pid_t pid = fork();
    if (pid < 0) {
        close(master_fd);
        close(slave_fd);
        return -2000;
    }

    if (pid == 0) {
        /* ── 子进程 ── */
        close(master_fd);

        /* 新会话 + 控制终端。 */
        setsid();
        ioctl(slave_fd, TIOCSCTTY, 0);

        /* slave → stdin/stdout/stderr。 */
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        if (slave_fd > STDERR_FILENO) close(slave_fd);

        /* 工作目录。 */
        if (workdir_str && workdir_str[0]) {
            (void)chdir(workdir_str);
        }

        /* execve(linker64, [linker64, binary, args...], envp)。 */
        execve(linker_str, argv, envp);
        _exit(errno); /* execve 返回即失败 */
    }

    /* ── 父进程 ── */
    close(slave_fd);

    /* 释放 JNI 字符串（子进程已复制）。 */
    (*env)->ReleaseStringUTFChars(env, linkerPath, linker_str);
    (*env)->ReleaseStringUTFChars(env, binaryPath, binary_str);
    if (workdir_str) (*env)->ReleaseStringUTFChars(env, workDir, workdir_str);
    for (int i = 0; argv[i]; i++) free(argv[i]);
    free(argv);
    for (int i = 0; envp[i]; i++) free(envp[i]);
    free(envp);

    /* 输出 master fd。 */
    jint fd_val = (jint)master_fd;
    (*env)->SetIntArrayRegion(env, masterFdOut, 0, 1, &fd_val);

    return (jint)pid;
}

/**
 * E4：调整 PTY 窗口大小（TIOCSWINSZ）。
 * @return 0 成功，-errno 失败。
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativePtyResize(
    JNIEnv *env, jobject thiz, jint masterFd, jint rows, jint cols) {
    (void)env; (void)thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    if (ioctl((int)masterFd, TIOCSWINSZ, &ws) != 0) return -errno;
    return 0;
}

/**
 * E4：等待子进程退出（非阻塞可选）。
 * @param block 1=阻塞等待，0=非阻塞（WNOHANG）。
 * @return >0 编码后的退出状态（exitCode 或 128+signal）；
 *         0 = 非阻塞模式下子进程仍存活；
 *         -errno = waitpid 失败。
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeWaitPid(
    JNIEnv *env, jobject thiz, jint pid, jint block) {
    (void)env; (void)thiz;
    int status = 0;
    int flags = block ? 0 : WNOHANG;
    pid_t r = waitpid((pid_t)pid, &status, flags);
    if (r < 0) return -errno;
    if (r == 0) return 0; /* 非阻塞：仍存活 */
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/**
 * E4：关闭文件描述符（PTY master）。
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeCloseFd(
    JNIEnv *env, jobject thiz, jint fd) {
    (void)env; (void)thiz;
    if (close((int)fd) != 0) return -errno;
    return 0;
}

/* ── 批次 5：E16 native spawn + E21 rlimits ──────────────────────────── */

/**
 * E16：精确控制的子进程 spawn（ProcessBuilder 无法做到的部分）。
 *
 * 流程：创建 3 对管道（stdin/stdout/stderr）→ fork →
 *   子进程：setsid() → chdir(workDir) → clearenv() → 逐项 setenv →
 *           逐项 setrlimit（E21）→ dup2 管道 → execve(linker, binary, args)
 *   父进程：返回 pid，管道 fd 写入 fdsOut[0..5]。
 *
 * fdsOut 布局：[0]=父写端(子 stdin)、[1]=父读端(子 stdout)、[2]=父读端(子 stderr)、
 *              [3]=子读端(已关闭，仅占位)、[4]、[5] 保留。
 * 实际只需 3 个父端 fd：fdsOut[0..2]。
 *
 * @param rlimits 资源限制，格式为 [resource, soft, hard] 三元组扁平数组。
 *                resource 取 RLIMIT_* 常量。空数组表示不设限制。
 * @return pid > 0 成功；负值 = -errno 或 -2000（fork 失败）。
 */
JNIEXPORT jint JNICALL
Java_com_lhzkml_jasmine_core_plugin_proxy_ExecBridge_nativeSpawn(
    JNIEnv *env, jobject thiz,
    jstring linkerPath, jstring binaryPath, jobjectArray args,
    jobjectArray envPairs, jstring workDir,
    jlongArray rlimits, jintArray fdsOut) {
    (void)thiz;

    /* ── 1. 提取 JNI 字符串与数组（fork 前完成）── */
    const char *linker_str = (*env)->GetStringUTFChars(env, linkerPath, NULL);
    const char *binary_str = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    if (!linker_str || !binary_str) return -1;

    const char *workdir_str = NULL;
    if (workDir) workdir_str = (*env)->GetStringUTFChars(env, workDir, NULL);

    jsize argc_user = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = (char **)calloc((size_t)argc_user + 3, sizeof(char *));
    if (!argv) return -ENOMEM;
    argv[0] = strdup(linker_str);
    argv[1] = strdup(binary_str);
    for (int i = 0; i < argc_user; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *cs = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        argv[i + 2] = cs ? strdup(cs) : strdup("");
        if (cs) (*env)->ReleaseStringUTFChars(env, s, cs);
        if (s) (*env)->DeleteLocalRef(env, s);
    }
    argv[argc_user + 2] = NULL;

    jsize env_count = envPairs ? (*env)->GetArrayLength(env, envPairs) : 0;
    char **envp = (char **)calloc((size_t)env_count + 1, sizeof(char *));
    if (!envp) return -ENOMEM;
    for (int i = 0; i < env_count; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, envPairs, i);
        const char *cs = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        envp[i] = cs ? strdup(cs) : strdup("");
        if (cs) (*env)->ReleaseStringUTFChars(env, s, cs);
        if (s) (*env)->DeleteLocalRef(env, s);
    }
    envp[env_count] = NULL;

    /* ── 2. 提取 rlimits（E21）── */
    jsize rl_count = rlimits ? (*env)->GetArrayLength(env, rlimits) : 0;
    /* 必须是 3 的倍数。 */
    int n_rl = (int)(rl_count / 3);
    jlong *rl_data = NULL;
    if (rl_count > 0) {
        rl_data = (*env)->GetLongArrayElements(env, rlimits, NULL);
    }

    /* ── 3. 创建 3 对管道 ── */
    int stdin_pipe[2], stdout_pipe[2], stderr_pipe[2];
    if (pipe(stdin_pipe) != 0 || pipe(stdout_pipe) != 0 || pipe(stderr_pipe) != 0) {
        return -errno;
    }

    /* ── 4. fork ── */
    pid_t pid = fork();
    if (pid < 0) {
        close(stdin_pipe[0]); close(stdin_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);
        return -2000;
    }

    if (pid == 0) {
        /* ── 子进程 ── */
        close(stdin_pipe[1]);   /* 关父写端 */
        close(stdout_pipe[0]);  /* 关父读端 */
        close(stderr_pipe[0]);

        /* 新会话（E6 进程组）。 */
        setsid();

        /* 工作目录。 */
        if (workdir_str && workdir_str[0]) (void)chdir(workdir_str);

        /* 清空继承环境，逐项设置（E8 隔离）。 */
        clearenv();
        for (int i = 0; envp[i]; i++) {
            char *eq = strchr(envp[i], '=');
            if (eq && eq != envp[i]) {
                *eq = '\0';
                setenv(envp[i], eq + 1, 1);
                *eq = '=';
            }
        }

        /* E21：资源限制。 */
        for (int i = 0; i < n_rl; i++) {
            struct rlimit rl;
            rl.rlim_cur = (rlim_t)rl_data[i * 3 + 1];
            rl.rlim_max = (rlim_t)rl_data[i * 3 + 2];
            setrlimit((int)rl_data[i * 3], &rl);
        }

        /* 管道 → stdin/stdout/stderr。 */
        dup2(stdin_pipe[0], STDIN_FILENO);
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stderr_pipe[1], STDERR_FILENO);
        close(stdin_pipe[0]);
        close(stdout_pipe[1]);
        close(stderr_pipe[1]);

        execve(linker_str, argv, envp);
        _exit(errno);
    }

    /* ── 父进程 ── */
    close(stdin_pipe[0]);   /* 关子读端 */
    close(stdout_pipe[1]);  /* 关子写端 */
    close(stderr_pipe[1]);

    (*env)->ReleaseStringUTFChars(env, linkerPath, linker_str);
    (*env)->ReleaseStringUTFChars(env, binaryPath, binary_str);
    if (workdir_str) (*env)->ReleaseStringUTFChars(env, workDir, workdir_str);
    for (int i = 0; argv[i]; i++) free(argv[i]);
    free(argv);
    for (int i = 0; envp[i]; i++) free(envp[i]);
    free(envp);
    if (rl_data) (*env)->ReleaseLongArrayElements(env, rlimits, rl_data, JNI_ABORT);

    /* 输出父端管道 fd：[0]=stdin 写、[1]=stdout 读、[2]=stderr 读。 */
    jint fds[3] = { (jint)stdin_pipe[1], (jint)stdout_pipe[0], (jint)stderr_pipe[0] };
    (*env)->SetIntArrayRegion(env, fdsOut, 0, 3, fds);

    return (jint)pid;
}
