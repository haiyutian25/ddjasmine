package com.lhzkml.jasmine.core.plugin.proxy

/**
 * PRoot 可行性探针（框架侧原生能力，供"探针插件"在插件上下文调用）。
 *
 * 两个探针分别裁决 PRoot 插件路线的两条关键路径：
 *  - [mmapExec]：只读 `mmap(PROT_READ|PROT_EXEC)` 一个文件并在子进程执行——
 *    对应"PRoot loader 用 mmap 装载 guest 二进制"（guest 装载路径）。
 *  - [memfdExec]：`memfd_create` 匿名文件写入独立程序并 `fexecve`——
 *    对应"proot/loader 能否经 memfd 从不可执行的插件目录被跑起来"（插件模型生死线）。
 *
 * 复用 `libexecbridge.so`（宿主已加载；此处再 loadLibrary 幂等）。崩溃隔离在子进程。
 *
 * 返回约定：42=成功；-errno=被拒（如 -13 EACCES）；-(1000+sig)=执行时被信号杀死；
 * -2000/-2001=fork/wait 异常或结果不符；-9999=libexecbridge 未加载。
 */
object ProotProbe {

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("execbridge")
            loaded = true
        } catch (_: Throwable) {
            loaded = false
        }
    }

    val available: Boolean get() = loaded

    /** 对已写入机器码的文件 [payloadPath] 做 mmap PROT_EXEC + 子进程执行。 */
    fun mmapExec(payloadPath: String): Int =
        if (loaded) nativeMmapExecProbe(payloadPath) else -9999

    /** memfd_create + fexecve 匿名程序（不依赖任何目录）。 */
    fun memfdExec(): Int =
        if (loaded) nativeMemfdExecProbe() else -9999

    /** 探针 A：对真实文件 [path] fork+execve（测宿主 nativeLibraryDir 放行与否）。 */
    fun execFile(path: String): Int =
        if (loaded) nativeExecFileProbe(path) else -9999

    /** 探针 B：ptrace 子进程往返（TRACEME+GETREGSET+CONT），PRoot 命脉。 */
    fun ptraceProbe(): Int =
        if (loaded) nativePtraceProbe() else -9999

    /** 探针 C：子进程安装全放行 seccomp BPF，测 PRoot 加速项可用性。 */
    fun seccompProbe(): Int =
        if (loaded) nativeSeccompProbe() else -9999

    /** 探针 D：system_linker_exec——经 linker64 装载 [path]（测插件目录二进制可否被执行）。 */
    fun linkerExec(path: String): Int =
        if (loaded) nativeLinkerExecProbe(path) else -9999

    private external fun nativeMmapExecProbe(path: String): Int
    private external fun nativeMemfdExecProbe(): Int
    private external fun nativeExecFileProbe(path: String): Int
    private external fun nativePtraceProbe(): Int
    private external fun nativeSeccompProbe(): Int
    private external fun nativeLinkerExecProbe(path: String): Int
}
