package com.lhzkml.jasmine.core.plugin.proxy

import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Subprocess lifecycle supervisor for plugin-spawned child processes
 * (《PRoot插件可行性与缺口分析》§5.3).
 *
 * [ExecBridge.runViaLinker] only *starts* a child; proot-style workloads need
 * a managed lifetime: asynchronous start, stdout/stderr pumping (so full pipes
 * never deadlock the child), death notification, and — unlike the framework's
 * crash-breaker policy ("crash ⇒ disable the plugin") — an opt-in **restart**
 * policy. The two do not conflict: the crash breaker governs Java/native
 * crashes *inside* a plugin's own process, while a supervised child is a
 * separate OS process whose exit is an ordinary runtime event.
 *
 * Every start is gated on the `EXEC` capability (inherited from
 * [ExecBridge.runViaLinker]). [stopAll] is called by `PluginHost` when a
 * plugin unloads, so no child outlives its plugin.
 *
 * 批次 2 增强（E5/E6/E9/E10/E11/E12/E17）：
 * - E5 信号转发：[ManagedProcess.signal] / [ManagedProcess.destroyGracefully]
 * - E6 进程组管理：[ManagedProcess.destroy] 杀整个进程组
 * - E9 状态查询：[ManagedProcess.pid] / [uptimeMs] / [restartCount] / [lastExitCode] / [state]
 * - E10 输出环形缓冲区：[ManagedProcess.recentOutput]
 * - E11 并发子进程限制：[maxChildrenPerPlugin]
 * - E12 优雅关闭序列：[stopAll] 默认 SIGTERM → 等待 → SIGKILL
 * - E17 崩溃诊断：[ChildProcessSpec.onExit] 扩展为 (exitCode, termSignal, willRestart)
 */
class ChildProcessSupervisor(internal val execBridge: ExecBridge) {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live supervised children, keyed by plugin id. */
    private val byPlugin = ConcurrentHashMap<String, CopyOnWriteArraySet<ManagedProcess>>()

    /** E4：PTY 子进程，按插件 id 跟踪。 */
    private val ptyByPlugin = ConcurrentHashMap<String, CopyOnWriteArraySet<PtyProcess>>()

    /** E11：每个插件最大并发子进程数。 */
    var maxChildrenPerPlugin: Int = 4

    /**
     * Starts a supervised child via system_linker_exec (see [ExecBridge.runViaLinker]).
     * Output lines (stdout+stderr merged) stream into [ChildProcessSpec.onOutput];
     * exit is reported through [ChildProcessSpec.onExit] together with whether a
     * restart is scheduled.
     *
     * @throws IllegalStateException E11：超过 [maxChildrenPerPlugin] 限制。
     */
    suspend fun start(spec: ChildProcessSpec): ManagedProcess {
        // E11：并发子进程限制。
        val current = byPlugin[spec.pluginId]?.size ?: 0
        if (current >= maxChildrenPerPlugin) {
            throw IllegalStateException(
                "插件 ${spec.pluginId} 已达并发子进程上限 ($maxChildrenPerPlugin)"
            )
        }
        val process = execBridge.runViaLinker(
            pluginId = spec.pluginId,
            relPath = spec.relPath,
            args = spec.args,
            workDir = spec.workDir,
            env = spec.env,
        )
        val managed = ManagedProcess(spec, this)
        managed.attach(process)
        byPlugin.getOrPut(spec.pluginId) { CopyOnWriteArraySet() }.add(managed)
        return managed
    }

    /**
     * E22：按 [mode] 编排启动一组子进程。
     *
     * - [GroupMode.SEQUENTIAL]：依次启动，前一个成功后才启动下一个；
     *   任一失败即抛出（已启动的保留，由调用方决定清理）。
     * - [GroupMode.PARALLEL]：并发启动，全部完成后返回；
     *   任一失败则该位置抛出（其余照常完成）。
     *
     * @return 与 [specs] 顺序对应的 [ManagedProcess] 列表。
     */
    suspend fun startGroup(
        specs: List<ChildProcessSpec>,
        mode: GroupMode = GroupMode.SEQUENTIAL,
    ): List<ManagedProcess> = when (mode) {
        GroupMode.SEQUENTIAL -> specs.map { start(it) }
        GroupMode.PARALLEL -> coroutineScope {
            specs.map { spec -> async { start(spec) } }.awaitAll()
        }
    }

    /**
     * E4：启动一个受监督的 PTY 子进程（交互式终端语义）。
     *
     * 与 [start]（pipe）不同，子进程 stdin/stdout/stderr 接在伪终端上，
     * `isatty()` 为真，适合运行交互式 shell。输出经 [onOutput] 逐行回调，
     * 退出经 [onExit] 上报。
     *
     * @throws IllegalStateException E11：超过 [maxChildrenPerPlugin] 限制。
     */
    suspend fun startWithPty(
        spec: ChildProcessSpec,
        rows: Int = 24,
        cols: Int = 80,
    ): PtyProcess {
        val current = byPlugin[spec.pluginId]?.size ?: 0
        val ptyCurrent = ptyByPlugin[spec.pluginId]?.size ?: 0
        if (current + ptyCurrent >= maxChildrenPerPlugin) {
            throw IllegalStateException(
                "插件 ${spec.pluginId} 已达并发子进程上限 ($maxChildrenPerPlugin)"
            )
        }
        val pty = execBridge.runViaLinkerWithPty(
            pluginId = spec.pluginId,
            relPath = spec.relPath,
            args = spec.args,
            workDir = spec.workDir,
            env = spec.env,
            rows = rows,
            cols = cols,
        )
        ptyByPlugin.getOrPut(spec.pluginId) { CopyOnWriteArraySet() }.add(pty)
        // 输出泵：从 PTY master 读行，回调 spec.onOutput（与 start 一致）。
        // PTY 子进程不支持重启（终端会话无法复用），willRestart 恒为 false。
        scope.launch {
            try {
                pty.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        runCatching { spec.onOutput?.invoke(line) }
                    }
                }
            } catch (_: Throwable) {
                // PTY 关闭或 I/O 错误。
            }
            val code = runCatching { pty.waitFor() }.getOrDefault(-1)
            val termSignal = if (code > 128) code - 128 else null
            runCatching { spec.onExit?.invoke(code, termSignal, false) }
            ptyByPlugin[spec.pluginId]?.remove(pty)
        }
        return pty
    }

    /** E4：停止一个 PTY 子进程（幂等）。 */
    fun stopPty(pluginId: String, pty: PtyProcess) {
        pty.destroyGracefully()
        ptyByPlugin[pluginId]?.remove(pty)
    }

    /** Stops one child (idempotent); it will not be restarted. */
    fun stop(managed: ManagedProcess) {
        managed.teardown()
        byPlugin[managed.spec.pluginId]?.remove(managed)
    }

    /**
     * E12：Stops every child of [pluginId] with graceful shutdown sequence.
     * SIGTERM → wait [timeoutMs] → SIGKILL (killpg for process group).
     * E4：同时停止该插件的 PTY 子进程。
     */
    fun stopAll(pluginId: String, graceful: Boolean = true, timeoutMs: Long = 5_000L) {
        // E4：PTY 子进程。
        val ptys = ptyByPlugin.remove(pluginId)
        ptys?.forEach {
            if (graceful) it.destroyGracefully(timeoutMs) else it.destroy()
        }
        val children = byPlugin.remove(pluginId) ?: return
        if (!graceful) {
            children.forEach { it.teardown() }
            return
        }
        // E5/E12：优雅关闭——先 SIGTERM，等待，再强制。
        children.forEach { it.destroyGracefully(timeoutMs) }
        children.forEach { it.teardown() }
    }

    /** Stops every supervised child (framework shutdown). */
    fun stopAll() {
        val ids = (byPlugin.keys + ptyByPlugin.keys).toSet().toList()
        ids.forEach { stopAll(it) }
    }

    /** Number of live supervised children of [pluginId]（含 PTY 子进程）。 */
    fun childCount(pluginId: String): Int =
        (byPlugin[pluginId]?.size ?: 0) + (ptyByPlugin[pluginId]?.size ?: 0)

    /**
     * Called by [ManagedProcess] when its current child exits: reports the
     * death and schedules a restart when the policy allows it.
     *
     * E17：扩展为 (exitCode, termSignal, willRestart)。
     * termSignal 非 null 表示被信号杀死（如 SIGSYS=31 表示 seccomp 拦截，
     * SIGKILL=9 可能是幻影进程杀手）。
     */
    internal fun onChildExit(managed: ManagedProcess, exitCode: Int) {
        val spec = managed.spec
        val ranMs = System.currentTimeMillis() - managed.startedAtMs
        if (ranMs >= spec.restartPolicy.stableRunMs) managed.consecutiveFailures = 0
        val willRestart = !managed.stopping &&
            exitCode != 0 &&
            spec.restartPolicy.maxAttempts > 0 &&
            managed.consecutiveFailures < spec.restartPolicy.maxAttempts
        // E17：从退出码推断终止信号（Java 约定：128+signal）。
        val termSignal = if (exitCode > 128) exitCode - 128 else null
        managed.lastExitCodeInternal = exitCode
        runCatching { spec.onExit?.invoke(exitCode, termSignal, willRestart) }
        if (willRestart) {
            managed.consecutiveFailures++
            managed.restartCountInternal++
            val delayMs = spec.restartPolicy.delayMs * managed.consecutiveFailures
            scope.launch {
                delay(delayMs)
                if (!managed.stopping) {
                    runCatching {
                        val next = execBridge.runViaLinker(
                            pluginId = spec.pluginId,
                            relPath = spec.relPath,
                            args = spec.args,
                            workDir = spec.workDir,
                            env = spec.env,
                        )
                        managed.attach(next)
                    }.onFailure {
                        managed.teardown()
                        byPlugin[spec.pluginId]?.remove(managed)
                        runCatching { spec.onExit?.invoke(-1, null, false) }
                    }
                }
            }
        } else if (!managed.stopping) {
            // Terminal exit (success, or restart budget exhausted): drop the handle.
            byPlugin[spec.pluginId]?.remove(managed)
        }
    }
}

/**
 * Declaration of a supervised child process.
 *
 * @param relPath binary path relative to the plugin dir (traversal-checked by
 *   [ExecBridge.pluginBinaryPath]).
 * @param env extra environment (e.g. `PROOT_LOADER`, `PROOT_TMP_DIR`).
 * @param workDir child working directory, or null to inherit.
 * @param onOutput each merged stdout/stderr line (best effort; dropped after stop).
 * @param onExit E17：`(exitCode, termSignal, willRestart)` —
 *   `termSignal` 非 null 表示被信号杀死（SIGSYS=31 seccomp、SIGKILL=9 幻影进程杀手等）；
 *   `-1` means the restart itself failed.
 * @param isolateGuestEnv E8：为 true 时不继承宿主环境，仅使用 [env] 中的变量。
 */
data class ChildProcessSpec(
    val pluginId: String,
    val relPath: String,
    val args: List<String> = emptyList(),
    val workDir: File? = null,
    val env: Map<String, String> = emptyMap(),
    val restartPolicy: RestartPolicy = RestartPolicy(),
    val onOutput: ((line: String) -> Unit)? = null,
    val onExit: ((exitCode: Int, termSignal: Int?, willRestart: Boolean) -> Unit)? = null,
    val isolateGuestEnv: Boolean = false,
    /** E14：挂起检测（多线程死锁缓解）。null 表示不检测。 */
    val hangDetection: HangDetection? = null,
    /** E23：健康检查（插件自定义活性探针）。null 表示不检查。 */
    val healthCheck: HealthCheck? = null,
)

/**
 * E14：挂起检测配置。
 *
 * proot 单线程事件循环在多线程 guest（Node.js/npm）下会死锁
 * （termux/proot#326），表现为子进程"活着但不响应"。框架层监控
 * `/proc/<pid>/status` 是否长时间处于 `t (tracing stop)` 且无输出，
 * 超时触发 [onHangDetected]（插件可决定重启/通知用户）。
 *
 * @param checkIntervalMs 检查间隔（默认 10 秒）。
 * @param hangThresholdMs 判定挂起的无输出阈值（默认 60 秒）。
 * @param onHangDetected 挂起回调，参数为子进程 PID。
 */
data class HangDetection(
    val checkIntervalMs: Long = 10_000L,
    val hangThresholdMs: Long = 60_000L,
    val onHangDetected: ((pid: Int) -> Unit)? = null,
)

/**
 * E23：健康检查配置。
 *
 * 子进程可能"活着但不响应"（区别于 E14 的 tracing-stop 挂起检测，
 * 健康检查是插件自定义的活性探针，如向子进程发心跳命令并等待响应）。
 *
 * @param intervalMs 检查间隔（默认 30 秒）。
 * @param probe 活性探针：传入子进程 PID，返回 true 表示健康。
 * @param maxConsecutiveFailures 连续失败多少次判定不健康（默认 3）。
 * @param onUnhealthy 不健康回调（插件可决定重启/通知）。参数为子进程 PID。
 */
data class HealthCheck(
    val intervalMs: Long = 30_000L,
    val probe: (pid: Int) -> Boolean,
    val maxConsecutiveFailures: Int = 3,
    val onUnhealthy: ((pid: Int) -> Unit)? = null,
)

/**
 * Restart policy for a supervised child. The default ([maxAttempts] = 0) never
 * restarts. With restarts enabled, each consecutive failure waits
 * `delayMs × attempt` (linear backoff); a child that survives longer than
 * [stableRunMs] resets the failure counter.
 */
data class RestartPolicy(
    val maxAttempts: Int = 0,
    val delayMs: Long = 1_000L,
    val stableRunMs: Long = 60_000L,
)

/** E9：子进程状态枚举。 */
enum class ChildState { STARTING, RUNNING, RESTARTING, STOPPING, STOPPED }

/** E22：多子进程编排模式。 */
enum class GroupMode { SEQUENTIAL, PARALLEL }

/**
 * Handle of one supervised child. After a restart the underlying [Process]
 * changes: re-read [stdin]/[isAlive] from this handle, do not cache the old
 * `Process` object.
 *
 * 批次 2 增强：
 * - E5 [signal] / [destroyGracefully]
 * - E6 [destroy] 杀整个进程组
 * - E9 [pid] / [uptimeMs] / [restartCount] / [lastExitCode] / [state]
 * - E10 [recentOutput]
 */
class ManagedProcess internal constructor(
    val spec: ChildProcessSpec,
    private val supervisor: ChildProcessSupervisor,
) {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var pumpJob: Job? = null

    /** stderr 泵独立于 stdout 泵；teardown 时一并取消，避免泄漏。 */
    @Volatile
    private var stderrPumpJob: Job? = null

    @Volatile
    internal var stopping = false
        private set

    @Volatile
    internal var startedAtMs = 0L

    @Volatile
    internal var consecutiveFailures = 0

    // ── E9：状态查询 ──────────────────────────────────────────────────────

    @Volatile
    internal var restartCountInternal = 0

    @Volatile
    internal var lastExitCodeInternal: Int? = null

    @Volatile
    private var stateInternal = ChildState.STARTING

    /** E9：当前子进程 PID（-1 表示无活跃进程）。
     * 反射获取：android.jar 未暴露 `Process.pid()`。 */
    val pid: Int
        get() {
            val p = process ?: return -1
            return try {
                val m = p.javaClass.getMethod("pid")
                (m.invoke(p) as? Int) ?: -1
            } catch (_: Throwable) {
                try {
                    val f = p.javaClass.getDeclaredField("pid")
                    f.isAccessible = true
                    f.getInt(p)
                } catch (_: Throwable) {
                    -1
                }
            }
        }

    /** E9：运行时长（毫秒）。 */
    val uptimeMs: Long
        get() = if (startedAtMs > 0 && isAlive) System.currentTimeMillis() - startedAtMs else 0L

    /** E9：累计重启次数。 */
    val restartCount: Int get() = restartCountInternal

    /** E9：最后退出码（null 表示尚未退出过）。 */
    val lastExitCode: Int? get() = lastExitCodeInternal

    /** E9：当前状态。 */
    val state: ChildState get() = stateInternal

    // ── E10：输出环形缓冲区 ──────────────────────────────────────────────

    private val outputRing = ArrayDeque<String>(256)
    private val ringLock = Any()

    /** E14：最后一次有输出的时间戳（挂起检测用）。 */
    @Volatile
    private var lastOutputAtMs = 0L

    /** E14：挂起检测监控协程。 */
    @Volatile
    private var hangWatchJob: Job? = null

    /** E23：健康检查监控协程。 */
    @Volatile
    private var healthCheckJob: Job? = null

    /** E10：最近 [lines] 行输出（崩溃回溯诊断）。 */
    fun recentOutput(lines: Int = 50): List<String> = synchronized(ringLock) {
        outputRing.takeLast(lines)
    }

    private fun recordOutput(line: String) {
        lastOutputAtMs = System.currentTimeMillis() // E14
        synchronized(ringLock) {
            outputRing.addLast(line)
            while (outputRing.size > 256) outputRing.removeFirst()
        }
    }

    // ── 原有接口 ─────────────────────────────────────────────────────────

    /** Write-side of the child's stdin (changes across restarts). */
    val stdin: OutputStream?
        get() = process?.outputStream

    /** Whether the current child generation is alive. */
    val isAlive: Boolean
        get() = process?.isAlive == true

    /** Stops this child permanently (no restart). */
    fun destroy() = supervisor.stop(this)

    // ── E5：信号转发 ─────────────────────────────────────────────────────

    /**
     * E5：向当前子进程发送信号。
     * @return 0 成功，负值为 -errno。
     */
    fun signal(sig: Int): Int {
        val p = pid
        if (p <= 0) return -3 // ESRCH
        return supervisor.execBridge.signalPid(p, sig)
    }

    /**
     * E5：优雅关闭——SIGTERM → 等待 [timeoutMs] → SIGKILL。
     * E6：使用进程组信号（若可用）。
     */
    fun destroyGracefully(timeoutMs: Long = 5_000L) {
        val p = pid
        if (p <= 0) return
        stateInternal = ChildState.STOPPING
        // 先发 SIGTERM。
        supervisor.execBridge.signalPid(p, SIGTERM)
        // 等待退出。
        val deadline = System.currentTimeMillis() + timeoutMs
        while (isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        // 仍存活则强制。
        if (isAlive) {
            runCatching { process?.destroyForcibly() }
        }
    }

    // ── 内部生命周期 ─────────────────────────────────────────────────────

    /** Binds a freshly started child and launches its output pump + exit watch. */
    internal fun attach(child: Process) {
        process = child
        startedAtMs = System.currentTimeMillis()
        lastOutputAtMs = startedAtMs // E14：初始视为有输出
        stateInternal = ChildState.RUNNING
        pumpJob?.cancel()
        stderrPumpJob?.cancel()
        hangWatchJob?.cancel()
        // stderr 独立泵：runViaLinker 未设 redirectErrorStream，stderr 管道写满
        // 会阻塞子进程，必须持续排空。
        stderrPumpJob = drainStream(child.errorStream)
        pumpJob = supervisor.scope.launch {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        recordOutput(line) // E10
                        runCatching { spec.onOutput?.invoke(line) }
                    }
                }
            } catch (_: Throwable) {
                // Stream closed (destroy) or I/O error — fall through to exit watch.
            }
            val code = try {
                child.waitFor()
            } catch (_: Throwable) {
                -1
            }
            if (!stopping) supervisor.onChildExit(this@ManagedProcess, code)
        }
        // E14：挂起检测监控。
        spec.hangDetection?.let { hd -> startHangWatch(hd) }
        // E23：健康检查监控。
        spec.healthCheck?.let { hc -> startHealthCheck(hc) }
    }

    /**
     * E14：启动挂起检测协程。
     *
     * 定期读 `/proc/<pid>/status` 的 `State:` 行，若长时间处于
     * `t (tracing stop)` 且 [hangThresholdMs] 内无输出，判定挂起并回调。
     */
    private fun startHangWatch(hd: HangDetection) {
        hangWatchJob = supervisor.scope.launch {
            while (isAlive && !stopping) {
                delay(hd.checkIntervalMs)
                if (!isAlive || stopping) break
                val p = pid
                if (p <= 0) continue
                val idleMs = System.currentTimeMillis() - lastOutputAtMs
                if (idleMs < hd.hangThresholdMs) continue
                // 无输出超阈值：进一步检查 /proc 状态。
                val state = readProcState(p)
                if (state != null && state.startsWith("t")) {
                    // tracing stop + 长时间无输出 → 判定挂起。
                    runCatching { hd.onHangDetected?.invoke(p) }
                }
            }
        }
    }

    /** E14：读 `/proc/<pid>/status` 的 `State:` 行（如 `t (tracing stop)`）。 */
    private fun readProcState(pid: Int): String? = try {
        java.io.File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("State:") }
                ?.removePrefix("State:")?.trim()
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * E23：启动健康检查协程。
     *
     * 定期调用 [HealthCheck.probe]，连续 [HealthCheck.maxConsecutiveFailures]
     * 次失败判定不健康并回调 [HealthCheck.onUnhealthy]（随后重置计数，避免重复触发）。
     */
    private fun startHealthCheck(hc: HealthCheck) {
        healthCheckJob = supervisor.scope.launch {
            var failures = 0
            while (isAlive && !stopping) {
                delay(hc.intervalMs)
                if (!isAlive || stopping) break
                val p = pid
                if (p <= 0) continue
                val healthy = runCatching { hc.probe(p) }.getOrDefault(false)
                if (healthy) {
                    failures = 0
                } else {
                    failures++
                    if (failures >= hc.maxConsecutiveFailures) {
                        runCatching { hc.onUnhealthy?.invoke(p) }
                        failures = 0
                    }
                }
            }
        }
    }

    /** Idempotent teardown: kill the child, cancel the pumps, mark stopped. */
    internal fun teardown() {
        stopping = true
        stateInternal = ChildState.STOPPED
        pumpJob?.cancel()
        pumpJob = null
        stderrPumpJob?.cancel()
        stderrPumpJob = null
        hangWatchJob?.cancel() // E14
        hangWatchJob = null
        healthCheckJob?.cancel() // E23
        healthCheckJob = null
        // E6：优先杀进程组（防止 tracee 孤儿）。
        val p = pid
        if (p > 0) {
            supervisor.execBridge.signalGroup(p, SIGKILL)
        }
        runCatching { process?.destroyForcibly() }
        process = null
    }

    /** Drains one stream line-by-line into [ChildProcessSpec.onOutput]. */
    private fun drainStream(stream: java.io.InputStream): Job =
        supervisor.scope.launch {
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        recordOutput(line) // E10
                        runCatching { spec.onOutput?.invoke(line) }
                    }
                }
            } catch (_: Throwable) {
                // Stream closed or I/O error — nothing to do.
            }
        }

    companion object {
        private const val SIGTERM = 15
        private const val SIGKILL = 9
    }
}
