package com.lhzkml.jasmine.core.plugin.proxy

import android.app.Application
import android.os.Build
import android.provider.Settings
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.internal.InstallExecutor
import com.lhzkml.jasmine.core.plugin.rust.FfiCapability
import java.io.File
import java.io.InputStream

/**
 * Executes a plugin's executable assets (`assets/exec/`, extracted by
 * [InstallExecutor]). Proot-style user-space binaries run here.
 *
 * Android 10+ mounts `filesDir` noexec, so a plain `execve` of an extracted
 * file fails on modern devices. [runNative] therefore prefers the dlopen
 * bridge — the native shim `libexecbridge.so` loads the executable (a PIE
 * built with `-fPIE -pie` exporting `main`) as a shared object and runs its
 * `main` — and [run] falls back to direct `ProcessBuilder` execution only
 * where the mount permits it.
 *
 * Every launch is gated through [PluginHost.checkCapability] with the `EXEC`
 * capability, so an undeclared plugin cannot spawn binaries.
 */
class ExecBridge(private val application: Application) {

    /** E19：插件侧记录的探针结果缓存（探针名 → 返回码）。 */
    private val probeResults = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Absolute path of an extracted executable asset, or null when absent. */
    fun executablePath(pluginId: String, name: String): File? {
        if (name.isEmpty()) return null
        val base = InstallExecutor(application).execDir(pluginId).canonicalFile
        val candidate = File(base, name).canonicalFile
        // 规范化后必须仍在本插件 execDir 内：杜绝 `../` 逃逸到其它插件的
        // native 库 / base.apk / 任意沙箱文件（runNative 走 dlopen 不受 noexec
        // 限制，逃逸即可加载任意可读文件）。
        if (!candidate.path.startsWith(base.path + File.separator)) return null
        return candidate.takeIf { it.isFile }
    }

    /**
     * Runs an executable asset via the dlopen bridge (noexec-safe). Blocks
     * until the program's `main` returns; returns its exit code, or a
     * negative bridge error code (`-1` path unreadable, `-2` dlopen failed,
     * `-3` no `main`, `-4`/`-5` allocation/thread failure, `-6` workDir
     * switch failed).
     *
     * `env`/`workDir`（《PRoot插件可行性与缺口分析》§5.4）：dlopen 路径在宿主进程内
     * 运行，注入只能是进程级、运行期间生效、结束后尽力还原；并发运行多个带不同
     * env/cwd 的 dlopen 程序会相互干扰——需要严格隔离时改用 [runViaLinker]
     * （独立子进程）。
     */
    suspend fun runNative(
        pluginId: String,
        name: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workDir: File? = null,
    ): Int {
        PluginHost.requireCapability(FfiCapability.EXEC, pluginId)
        val binary = executablePath(pluginId, name)
            ?: throw IllegalArgumentException("可执行资产不存在: $pluginId/$name")
        if (!dlopenBridgeAvailable()) {
            throw IllegalStateException("dlopen 桥不可用（libexecbridge.so 未加载）")
        }
        val envPairs = env.map { (k, v) -> "$k=$v" }.toTypedArray()
        return nativeRun(binary.absolutePath, args.toTypedArray(), envPairs, workDir?.absolutePath)
    }

    /**
     * Runs an executable asset directly via `ProcessBuilder`. Works only
     * where the mount permits exec; prefer [runNative] on Android 10+.
     * Throws [SecurityException] when the plugin lacks the `EXEC` capability.
     */
    suspend fun run(
        pluginId: String,
        name: String,
        args: List<String> = emptyList(),
        workDir: File? = null,
    ): Process {
        PluginHost.requireCapability(FfiCapability.EXEC, pluginId)
        val binary = executablePath(pluginId, name)
            ?: throw IllegalArgumentException("可执行资产不存在: $pluginId/$name")
        val command = mutableListOf(binary.absolutePath)
        command += args
        val builder = ProcessBuilder(command)
        workDir?.let { builder.directory(it) }
        builder.redirectErrorStream(true)
        return builder.start()
    }

    /**
     * Runs a plugin binary via **system_linker_exec** (see §4.6 of the PRoot
     * analysis): forks a child that `execve`s the system dynamic linker with
     * the plugin binary as its argument; the linker then mmap-loads and runs
     * it. This is the Android 10+ W^X-safe way to execute a
     * dynamically-linked binary that lives in the plugin's `app_data_file`
     * directory (direct `execve` of which SELinux denies). Unlike [runNative]
     * (in-process dlopen, synchronous, no handle), this returns a real child
     * [Process] with stdin/stdout/stderr pipes and full lifecycle control —
     * the proot-runner primitive proot needs (it must be a separate traced
     * process). Gated on the `EXEC` capability.
     *
     * @param relPath binary path relative to the plugin dir (e.g.
     *   `lib/arm64-v8a/libproot.so` or `exec/proot`); traversal-safe.
     * @param env extra environment (e.g. `PROOT_LOADER`, `PROOT_TMP_DIR`).
     */
    suspend fun runViaLinker(
        pluginId: String,
        relPath: String,
        args: List<String> = emptyList(),
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): Process {
        PluginHost.requireCapability(FfiCapability.EXEC, pluginId)
        val binary = pluginBinaryPath(pluginId, relPath)
            ?: throw IllegalArgumentException("插件二进制不存在: $pluginId/$relPath")
        // system_linker_exec：argv[0]=linker，argv[1]=目标二进制（链接器直接调用约定）。
        val command = mutableListOf(linkerPath, binary.absolutePath)
        command += args
        val builder = ProcessBuilder(command)
        workDir?.let { builder.directory(it) }
        // E1：LD_LIBRARY_PATH 自动注入——链接器搜索序为 DT_RUNPATH → LD_LIBRARY_PATH →
        // app nativeLibraryDir → 系统目录，不搜索二进制自身目录。插件未显式提供时，
        // 自动注入 nativeLibraryDir + 插件 lib/<abi> 目录，确保动态链接依赖可解析。
        if (!env.containsKey("LD_LIBRARY_PATH")) {
            builder.environment()["LD_LIBRARY_PATH"] = defaultLdLibraryPath(pluginId)
        }
        if (env.isNotEmpty()) builder.environment().putAll(env)
        return builder.start()
    }

    /** Resolves a binary anywhere within the plugin dir (traversal-safe), or null. */
    fun pluginBinaryPath(pluginId: String, relPath: String): File? {
        if (relPath.isEmpty()) return null
        val base = InstallExecutor(application).pluginDir(pluginId).canonicalFile
        val candidate = File(base, relPath).canonicalFile
        // 规范化后必须仍在本插件目录内：杜绝 `../` 逃逸到其它插件 / 宿主文件。
        if (!candidate.path.startsWith(base.path + File.separator)) return null
        return candidate.takeIf { it.isFile }
    }

    /** System dynamic linker for system_linker_exec, matching process bitness. */
    private val linkerPath: String
        get() = if (android.os.Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

    /**
     * 通用「插件原生可执行文件托管」管线（运行时定位）：返回插件声明的原生可执行
     * 文件在宿主 `nativeLibraryDir`（全体系唯一可 execve 处）的路径。
     *
     * 构建期插件经 `pluginPack.nativeExecutables` 声明、由宿主 `jasmine.plugin-dev`
     * 管线并入宿主 jniLibs，安装后落 nativeLibraryDir。文件名约定
     * `lib<pluginId>.<name>.so`：pluginId 即插件 namespace，name 为声明文件的
     * 不含扩展名的基名（与构建期重命名一致）。产物归插件，框架只提供定位能力，
     * 不打包任何插件专属产物。
     *
     * @param name 声明文件的基名（不含扩展名），如声明 `loader`/`loader.so` 则传 `loader`。
     * @return nativeLibraryDir 中的文件；插件未声明或宿主未并入时返回 null。
     */
    fun nativeExecutablePath(pluginId: String, name: String): File? {
        if (pluginId.isEmpty() || name.isEmpty()) return null
        val dir = File(application.applicationInfo.nativeLibraryDir)
        val candidate = File(dir, "lib$pluginId.$name.so")
        return candidate.takeIf { it.isFile }
    }

    /**
     * Whether the dlopen-based exec bridge is available — true once
     * `libexecbridge.so` loads and its probe answers (bionic linker supports
     * PIE dlopen).
     */
    fun dlopenBridgeAvailable(): Boolean = bridgeLoaded

    /** Drains a process's merged output to a string (best effort). */
    fun readAll(input: InputStream): String =
        input.bufferedReader().use { it.readText() }

    // ──────────────────────────────────────────────────────────────────────
    // 批次 1：基础补全（E1/E2/E3/E7/E8/E13/E18/E20）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * E2：宿主 `nativeLibraryDir`（全体系唯一可 execve 处）。
     * 插件构造 `PROOT_LOADER`/`LD_LIBRARY_PATH` 等需要此绝对路径。
     */
    fun nativeLibraryDir(): File = File(application.applicationInfo.nativeLibraryDir)

    /**
     * E3：插件临时目录（`pluginDir/tmp`），确保存在。
     * 供 `PROOT_TMP_DIR` 等只需可写、不需可执行的场景。
     */
    fun pluginTmpDir(pluginId: String): File {
        val dir = File(InstallExecutor(application).pluginDir(pluginId), "tmp")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /**
     * E1：默认 `LD_LIBRARY_PATH`——nativeLibraryDir + 插件 `lib/<abi>` 目录。
     * 链接器经 mmap 装载依赖（app_data_file 的 mmap PROT_EXEC 已实证放行）。
     */
    private fun defaultLdLibraryPath(pluginId: String): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val pluginLib = File(InstallExecutor(application).libDir(pluginId), abi)
        return application.applicationInfo.nativeLibraryDir +
            File.pathSeparator + pluginLib.absolutePath
    }

    /**
     * E1：SONAME 兼容——把插件 `lib/<abi>/` 下的 `.so` 复制/符号链接为 SONAME
     * 匹配名（如 `libtalloc.so` → `libtalloc.so.2`）。Android 包管理器只认
     * `lib*.so` 命名，带版本后缀的 SONAME 需要此步骤才能被链接器解析。
     *
     * @param mappings `源文件名` → `目标 SONAME 名`（如 `"libtalloc.so"` → `"libtalloc.so.2"`）。
     * @return 实际创建的目标文件列表。
     */
    fun prepareSonameLibs(pluginId: String, mappings: Map<String, String>): List<File> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libDir = File(InstallExecutor(application).libDir(pluginId), abi)
        val results = mutableListOf<File>()
        for ((source, target) in mappings) {
            val src = File(libDir, source)
            if (!src.isFile) continue
            val dst = File(libDir, target)
            if (dst.exists()) dst.delete()
            try {
                // 优先符号链接（省空间）；失败则复制。
                java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath())
            } catch (_: Throwable) {
                src.copyTo(dst, overwrite = true)
            }
            results += dst
        }
        return results
    }

    /**
     * E8：guest 环境预设（`env -i` 等效）——返回最小干净环境，
     * 防止宿主变量（`LD_PRELOAD`/`BOOTCLASSPATH`/`LD_LIBRARY_PATH` 等）
     * 泄漏进 guest 污染 glibc 程序。插件构造 guest 命令前缀时使用。
     */
    fun guestEnvPreset(
        home: String,
        term: String = "xterm-256color",
        lang: String = "C.UTF-8",
    ): Map<String, String> = mapOf(
        "HOME" to home,
        "TERM" to term,
        "LANG" to lang,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    /**
     * E13：宿主层环境变量预设——插件侧启动子进程的基础 env。
     * 包含 LD_LIBRARY_PATH、HOME、TMPDIR、PATH。
     * 插件可在此基础上追加（如 `PROOT_LOADER`）。
     */
    fun envPreset(pluginId: String): Map<String, String> {
        val pluginDir = InstallExecutor(application).pluginDir(pluginId)
        return mapOf(
            "LD_LIBRARY_PATH" to defaultLdLibraryPath(pluginId),
            "HOME" to pluginDir.absolutePath,
            "TMPDIR" to pluginTmpDir(pluginId).absolutePath,
            "PATH" to InstallExecutor(application).execDir(pluginId).absolutePath +
                File.pathSeparator + "/system/bin",
        )
    }

    /** E18：当前进程的 SELinux 上下文（如 `u:r:untrusted_app:s0`）。 */
    fun selinuxContext(): String = try {
        File("/proc/self/attr/current").readText().trim()
    } catch (_: Throwable) {
        "unknown"
    }

    /**
     * E20：Yama ptrace_scope（-1 = 文件不存在/Yama 未启用，视为 0）。
     * ≥2 则 proot 的 PTRACE_TRACEME 必然失败。
     */
    fun ptraceScope(): Int = try {
        File("/proc/sys/kernel/yama/ptrace_scope").readText().trim().toInt()
    } catch (_: Throwable) {
        -1
    }

    /** E7：幻影进程杀手状态。 */
    enum class PhantomKillerStatus { ENABLED, DISABLED, UNKNOWN }

    /**
     * E7：检测 Android 12+ 幻影进程杀手（Phantom Process Killer）状态。
     * 超过 32 个子进程时系统会静默杀死（signal 9），PRoot tracee 树极易触发。
     * 返回 ENABLED 时插件应警告用户执行 adb 命令关闭。
     */
    fun phantomProcessKillerStatus(): PhantomKillerStatus {
        if (Build.VERSION.SDK_INT < 31) return PhantomKillerStatus.DISABLED
        return try {
            val value = Settings.Global.getInt(
                application.contentResolver,
                "settings_enable_monitor_phantom_procs",
                1, // 默认启用
            )
            if (value == 0) PhantomKillerStatus.DISABLED else PhantomKillerStatus.ENABLED
        } catch (_: Throwable) {
            PhantomKillerStatus.UNKNOWN
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 批次 6：E19 执行能力综合报告
    // ──────────────────────────────────────────────────────────────────────

    /**
     * E19：记录一个探针结果（插件运行探针后调用，供 [executionCapabilityReport] 呈现）。
     *
     * 框架不硬编码任何探针逻辑——探针 A–E 由插件侧经 [ProotProbe] 运行，
     * 结果经此方法记录，报告统一呈现。
     *
     * @param name 探针标识（如 "A"/"B"/"C"/"D"/"E"/"mmap"/"memfd"）。
     * @param result 探针返回码（42=成功；负值为各类失败）。
     */
    fun recordProbeResult(name: String, result: Int) {
        probeResults[name] = result
    }

    /**
     * E19：当前进程的 seccomp 模式（`/proc/self/status` 的 `Seccomp:` 行）。
     * 0=disabled, 1=strict, 2=filter。读取失败返回 -1。
     */
    fun seccompMode(): Int = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Seccomp:") }
                ?.removePrefix("Seccomp:")?.trim()?.toIntOrNull() ?: -1
        }
    } catch (_: Throwable) {
        -1
    }

    /**
     * E19：执行能力综合报告。
     *
     * 整合框架侧可直接探测的执行底座事实（dlopen 桥、nativeLibraryDir、SELinux、
     * ptrace_scope、seccomp、幻影进程杀手、ABI）与插件侧记录的探针结果（A–E）。
     * 纯读取、不执行任何探针，可在任意时机调用。
     */
    fun executionCapabilityReport(): ExecutionReport {
        val libDir = nativeLibraryDir()
        return ExecutionReport(
            dlopenBridgeAvailable = dlopenBridgeAvailable(),
            nativeLibraryDir = libDir.absolutePath,
            nativeLibraryDirWritable = libDir.canWrite(),
            selinuxContext = selinuxContext(),
            ptraceScope = ptraceScope(),
            seccompMode = seccompMode(),
            phantomProcessKiller = phantomProcessKillerStatus(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            probeResults = probeResults.toMap(),
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 批次 2：信号发送（E5/E6 依赖，供 ChildProcessSupervisor 使用）
    // ──────────────────────────────────────────────────────────────────────

    /** E5：向指定 pid 发送信号。成功返回 0，失败返回 `-errno`。 */
    internal fun signalPid(pid: Int, sig: Int): Int = nativeSignal(pid, sig)

    /** E6：向指定进程组发送信号。成功返回 0，失败返回 `-errno`。 */
    internal fun signalGroup(pgid: Int, sig: Int): Int = nativeSignalGroup(pgid, sig)

    // ──────────────────────────────────────────────────────────────────────
    // 批次 4：E15 /proc 伪文件管理基础设施
    // ──────────────────────────────────────────────────────────────────────

    /**
     * E15：插件的 /proc 伪文件目录（`pluginDir/proc_fakes/`），确保存在。
     *
     * Android 8+ SELinux/seccomp 屏蔽 `/proc/stat`、`/proc/loadavg`、
     * `/proc/uptime`、`/proc/version`、`/proc/vmstat`、`/proc/net/` 下各文件等，
     * guest 内 `top`/`htop`/`apt` 依赖这些文件。proot-distro 事实标准做法：
     * 生成静态假文件，登录时用 `-b` 绑定到 `/proc/` 对应路径。
     */
    fun procFakeDir(pluginId: String): File {
        val dir = File(InstallExecutor(application).pluginDir(pluginId), "proc_fakes")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /**
     * E15：在 [procFakeDir] 下生成伪文件。
     *
     * 框架**不硬编码**任何 /proc 条目内容（通用能力），由插件决定伪造哪些。
     *
     * @param entries `文件名` → `文件内容`（如 `"stat"` → `"cpu  0 0 0 …"`）。
     * @return 实际写入的文件列表。
     */
    fun generateProcFakes(pluginId: String, entries: Map<String, String>): List<File> {
        val dir = procFakeDir(pluginId)
        val results = mutableListOf<File>()
        for ((name, content) in entries) {
            // 防穿越：文件名不得含路径分隔符。
            if (name.contains('/') || name.contains('\\') || name == "..") continue
            val file = File(dir, name)
            file.writeText(content)
            results += file
        }
        return results
    }

    /**
     * E15：返回 `-b` 绑定参数对列表，插件直接拼接入 proot 启动参数。
     *
     * 如 `[("<proc_fakes>/stat", "/proc/stat"), ("<proc_fakes>/loadavg", "/proc/loadavg")]`。
     * 仅包含 [procFakeDir] 中实际存在的文件。
     */
    fun procFakeBindings(pluginId: String): List<Pair<String, String>> {
        val dir = procFakeDir(pluginId)
        return dir.listFiles()?.filter { it.isFile }?.map { file ->
            file.absolutePath to "/proc/${file.name}"
        } ?: emptyList()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 批次 3：E4 PTY 支持
    // ──────────────────────────────────────────────────────────────────────

    /**
     * E4：经 **system_linker_exec + PTY** 运行插件二进制。
     *
     * 与 [runViaLinker]（pipe）不同，子进程的 stdin/stdout/stderr 接在伪终端
     * 的 slave 端——`isatty()` 为真、有行编辑/信号生成/窗口大小语义，是运行
     * 交互式 shell（bash/vim/top 等）的必要条件。子进程经 `setsid()` 成为新
     * 会话/进程组首领（pid == pgid），销毁时可整组杀掉（含 proot tracee）。
     *
     * @param relPath 插件目录内相对路径（防穿越，见 [pluginBinaryPath]）。
     * @param env 额外环境；未显式提供 `LD_LIBRARY_PATH` 时自动注入（E1）。
     * @param rows/cols 初始终端窗口大小。
     * @return [PtyProcess]（含 master fd 的 I/O 流与生命周期控制）。
     * @throws SecurityException 缺少 `EXEC` 能力。
     * @throws IllegalStateException spawn 失败（负值 = `-errno` 或 `-2000`）。
     */
    suspend fun runViaLinkerWithPty(
        pluginId: String,
        relPath: String,
        args: List<String> = emptyList(),
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        rows: Int = 24,
        cols: Int = 80,
    ): PtyProcess {
        PluginHost.requireCapability(FfiCapability.EXEC, pluginId)
        val binary = pluginBinaryPath(pluginId, relPath)
            ?: throw IllegalArgumentException("插件二进制不存在: $pluginId/$relPath")
        // E1：LD_LIBRARY_PATH 自动注入（同 runViaLinker）。
        val fullEnv = if (!env.containsKey("LD_LIBRARY_PATH")) {
            env + ("LD_LIBRARY_PATH" to defaultLdLibraryPath(pluginId))
        } else env
        val envPairs = fullEnv.map { (k, v) -> "$k=$v" }.toTypedArray()
        val masterFdOut = IntArray(1)
        val pid = nativeSpawnWithPty(
            linkerPath, binary.absolutePath, args.toTypedArray(),
            envPairs, workDir?.absolutePath, rows, cols, masterFdOut,
        )
        if (pid <= 0) {
            throw IllegalStateException("PTY spawn 失败: $pid")
        }
        return PtyProcess(this, pid, masterFdOut[0])
    }

    /** E4：调整 PTY 窗口大小。0 成功，负值为 `-errno`。 */
    internal fun ptyResize(masterFd: Int, rows: Int, cols: Int): Int =
        nativePtyResize(masterFd, rows, cols)

    /** E4：等待子进程退出。见 `nativeWaitPid` 返回约定。 */
    internal fun waitPid(pid: Int, block: Boolean): Int =
        nativeWaitPid(pid, if (block) 1 else 0)

    // ──────────────────────────────────────────────────────────────────────
    // 批次 5：E16 native spawn + E21 rlimits
    // ──────────────────────────────────────────────────────────────────────

    /**
     * E16：经 **native spawn** 运行插件二进制（精确控制）。
     *
     * 相比 [runViaLinker]（ProcessBuilder）：
     * - 子进程 `setsid()` 成为新会话/进程组首领（pid == pgid），销毁可整组杀掉；
     * - 环境经 `clearenv()` 完全隔离，只含 [env] 中的变量（E8 严格隔离）；
     * - 支持 [rlimits] 资源限制（E21）；
     * - stdin/stdout/stderr 为独立管道（不合并）。
     *
     * @param rlimits 资源限制列表，每项为 `Triple(resource, soft, hard)`，
     *   resource 取 `RLIMIT_*` 常量（如 `RLIMIT_AS=9`、`RLIMIT_CPU=0`、
     *   `RLIMIT_NOFILE=7`、`RLIMIT_NPROC=6`）。
     * @return [NativeChildProcess]。
     * @throws SecurityException 缺少 `EXEC` 能力。
     * @throws IllegalStateException spawn 失败（负值 = `-errno` 或 `-2000`）。
     */
    suspend fun spawnNative(
        pluginId: String,
        relPath: String,
        args: List<String> = emptyList(),
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        rlimits: List<Triple<Int, Long, Long>> = emptyList(),
    ): NativeChildProcess {
        PluginHost.requireCapability(FfiCapability.EXEC, pluginId)
        val binary = pluginBinaryPath(pluginId, relPath)
            ?: throw IllegalArgumentException("插件二进制不存在: $pluginId/$relPath")
        // E1：LD_LIBRARY_PATH 自动注入（clearenv 后环境为空，必须显式提供）。
        val fullEnv = if (!env.containsKey("LD_LIBRARY_PATH")) {
            env + ("LD_LIBRARY_PATH" to defaultLdLibraryPath(pluginId))
        } else env
        val envPairs = fullEnv.map { (k, v) -> "$k=$v" }.toTypedArray()
        // rlimits 扁平化为 [resource, soft, hard] 三元组。
        val rlFlat = IntArray(rlimits.size * 3)
        rlimits.forEachIndexed { i, (res, soft, hard) ->
            rlFlat[i * 3] = res
            rlFlat[i * 3 + 1] = soft.toInt()
            rlFlat[i * 3 + 2] = hard.toInt()
        }
        val fdsOut = IntArray(3)
        val pid = nativeSpawn(
            linkerPath, binary.absolutePath, args.toTypedArray(),
            envPairs, workDir?.absolutePath, rlFlat, fdsOut,
        )
        if (pid <= 0) {
            throw IllegalStateException("native spawn 失败: $pid")
        }
        return NativeChildProcess(this, pid, fdsOut[0], fdsOut[1], fdsOut[2])
    }

    private external fun nativeSignal(pid: Int, sig: Int): Int

    private external fun nativeSignalGroup(pgid: Int, sig: Int): Int

    private external fun nativeSpawnWithPty(
        linkerPath: String,
        binaryPath: String,
        args: Array<String>,
        envPairs: Array<String>,
        workDir: String?,
        rows: Int,
        cols: Int,
        masterFdOut: IntArray,
    ): Int

    private external fun nativePtyResize(masterFd: Int, rows: Int, cols: Int): Int

    private external fun nativeWaitPid(pid: Int, block: Int): Int

    // ── 批次 5：E16 native spawn + E21 rlimits ──────────────────────────

    private external fun nativeSpawn(
        linkerPath: String,
        binaryPath: String,
        args: Array<String>,
        envPairs: Array<String>,
        workDir: String?,
        rlimits: IntArray,
        fdsOut: IntArray,
    ): Int

    private external fun nativeRun(
        path: String,
        args: Array<String>,
        envPairs: Array<String>,
        workDir: String?,
    ): Int

    private external fun nativeBridgeProbe(): Int

    companion object {
        @Volatile
        private var bridgeLoaded = false

        init {
            try {
                System.loadLibrary("execbridge")
                bridgeLoaded = true
            } catch (_: Throwable) {
                bridgeLoaded = false
            }
        }
    }
}
