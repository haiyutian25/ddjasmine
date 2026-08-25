package com.lhzkml.jasmine.core.plugin.proxy

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * E4：PTY 子进程句柄（伪终端，交互式 shell 必需）。
 *
 * `runViaLinker` 返回的 [Process] 使用 pipe——无终端语义（`isatty()` 恒假、
 * 无行编辑、无信号生成），guest 内 bash/vim/top 等会降级或失败。本类封装
 * 伪终端 master fd，提供：
 * - [inputStream]/[outputStream]：终端 I/O（stdout/stderr 已在 slave 端合并）；
 * - [resize]：调整窗口大小（`TIOCSWINSZ`，向子进程组发 `SIGWINCH`）；
 * - [waitFor]/[destroy]/[destroyGracefully]：生命周期控制。
 *
 * 子进程经 `setsid()` 成为新会话/进程组首领（pid == pgid），故 [signal]
 * 作用于整个进程组（含 proot tracee，避免孤儿）。
 */
class PtyProcess internal constructor(
    private val execBridge: ExecBridge,
    /** 子进程 PID（同时是进程组 ID，因 setsid）。 */
    val pid: Int,
    masterFd: Int,
) {

    /** 接管 master fd 所有权；[close] 时一并关闭。 */
    private val pfd = ParcelFileDescriptor.adoptFd(masterFd)

    /** 终端输出（子进程打印的内容）。 */
    val inputStream: InputStream = FileInputStream(pfd.fileDescriptor)

    /** 终端输入（写入子进程 stdin）。 */
    val outputStream: OutputStream = FileOutputStream(pfd.fileDescriptor)

    @Volatile
    private var reaped = false

    @Volatile
    private var cachedExit: Int? = null

    private val exitLock = Any()

    /**
     * 子进程是否存活。用 `kill(pid, 0)` 探测（不 reap，避免与 [waitFor]
     * 竞争）；已被 [waitFor]/[destroy] 收割后恒为 false。
     */
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

    /**
     * E4：调整终端窗口大小（`TIOCSWINSZ`）。
     * @return 0 成功，负值为 `-errno`。
     */
    fun resize(rows: Int, cols: Int): Int = execBridge.ptyResize(pfd.fd, rows, cols)

    /**
     * 向子进程**进程组**发送信号（setsid 后 pid == pgid，含 proot tracee）。
     * @return 0 成功，负值为 `-errno`。
     */
    fun signal(sig: Int): Int = execBridge.signalGroup(pid, sig)

    /**
     * E5/E12：优雅关闭——进程组 SIGTERM → 等待 [timeoutMs] → SIGKILL，
     * 随后收割并关闭 master fd。
     */
    fun destroyGracefully(timeoutMs: Long = 5_000L) {
        if (isAlive) {
            signal(SIGTERM)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (isAlive && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (isAlive) signal(SIGKILL)
        }
        runCatching { waitFor() } // 收割，避免僵尸。
        close()
    }

    /** 强制终止：进程组 SIGKILL + 收割 + 关闭 master fd。 */
    fun destroy() {
        if (isAlive) signal(SIGKILL)
        runCatching { waitFor() }
        close()
    }

    /** 关闭 master fd（幂等）。 */
    fun close() {
        runCatching { pfd.close() }
    }

    private companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}
