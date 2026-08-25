package com.lhzkml.jasmine.core.plugin.proxy

import android.app.Application
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
     * `-3` no `main`, `-4`/`-5` allocation/thread failure).
     */
    suspend fun runNative(
        pluginId: String,
        name: String,
        args: List<String> = emptyList(),
    ): Int {
        PluginHost.requireCapability(FfiCapability.EXEC, pluginId)
        val binary = executablePath(pluginId, name)
            ?: throw IllegalArgumentException("可执行资产不存在: $pluginId/$name")
        if (!dlopenBridgeAvailable()) {
            throw IllegalStateException("dlopen 桥不可用（libexecbridge.so 未加载）")
        }
        return nativeRun(binary.absolutePath, args.toTypedArray())
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

    private external fun nativeRun(path: String, args: Array<String>): Int

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
