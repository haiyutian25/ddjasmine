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
        val candidate = File(InstallExecutor(application).execDir(pluginId), name)
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
        if (!PluginHost.checkCapability(FfiCapability.EXEC, pluginId)) return -100
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
     */
    suspend fun run(
        pluginId: String,
        name: String,
        args: List<String> = emptyList(),
        workDir: File? = null,
    ): Process? {
        if (!PluginHost.checkCapability(FfiCapability.EXEC, pluginId)) return null
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
