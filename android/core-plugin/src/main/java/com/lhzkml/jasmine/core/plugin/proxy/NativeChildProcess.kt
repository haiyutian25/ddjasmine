package com.lhzkml.jasmine.core.plugin.proxy

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * E16：native spawn 的子进程句柄（精确控制）。
 *
 * 相比 [java.lang.Process]（ProcessBuilder）：
 * - 子进程经 `setsid()` 成为新会话/进程组首领（pid == pgid），销毁可整组杀掉；
 * - 环境经 `clearenv()` 完全隔离，只含显式传入的变量（E8）；
 * - 支持 `rlimits` 资源限制（E21）；
 * - stdin/stdout/stderr 为独立管道（不合并）。
 */
class NativeChildProcess internal constructor(
    private val execBridge: ExecBridge,
    /** 子进程 PID（同时是进程组 ID，因 setsid）。 */
    val pid: Int,
    stdinWriteFd: Int,
    stdoutReadFd: Int,
    stderrReadFd: Int,
) {

    /** 接管三个管道 fd 的所有权；[close] 时一并关闭。 */
    private val stdinPfd = ParcelFileDescriptor.adoptFd(stdinWriteFd)
    private val stdoutPfd = ParcelFileDescriptor.adoptFd(stdoutReadFd)
    private val stderrPfd = ParcelFileDescriptor.adoptFd(stderrReadFd)

    /** 子进程 stdin（写入）。 */
    val outputStream: OutputStream = FileOutputStream(stdinPfd.fileDescriptor)

    /** 子进程 stdout。 */
    val inputStream: InputStream = FileInputStream(stdoutPfd.fileDescriptor)

    /** 子进程 stderr（独立，未与 stdout 合并）。 */
    val errorStream: InputStream = FileInputStream(stderrPfd.fileDescriptor)

    @Volatile
    private var reaped = false

    @Volatile
    private var cachedExit: Int? = null

    private val exitLock = Any()

    /** 子进程是否存活（`kill(pid, 0)` 探测，不 reap）。 */
    val isAlive: Boolean
        get() {
            if (reaped) return false
            return execBridge.signalPid(pid, 0) == 0
        }

    /**
     * 阻塞等待子进程退出并收割。
     * @return 退出码；被信号杀死时为 `128 + signal`。
     */
    fun waitFor(): Int = synchronized(exitLock) {
        cachedExit?.let { return it }
        val code = execBridge.waitPid(pid, block = true)
        reaped = true
        cachedExit = code
        code
    }

    /** 向子进程**进程组**发送信号（含子进程树）。0 成功，负值为 `-errno`。 */
    fun signal(sig: Int): Int = execBridge.signalGroup(pid, sig)

    /** E5/E12：优雅关闭——进程组 SIGTERM → 等待 → SIGKILL + 收割 + 关管道。 */
    fun destroyGracefully(timeoutMs: Long = 5_000L) {
        if (isAlive) {
            signal(SIGTERM)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (isAlive) signal(SIGKILL)
        }
        runCatching { waitFor() }
        close()
    }

    /** 强制终止：进程组 SIGKILL + 收割 + 关管道。 */
    fun destroy() {
        if (isAlive) signal(SIGKILL)
        runCatching { waitFor() }
        close()
    }

    /** 关闭三个管道 fd（幂等）。 */
    fun close() {
        runCatching { stdinPfd.close() }
        runCatching { stdoutPfd.close() }
        runCatching { stderrPfd.close() }
    }

    private companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}
