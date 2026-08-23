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
 * file fails on modern devices. [run] therefore documents that constraint
 * and exposes two paths:
 *  1. [run] — direct `ProcessBuilder` execution; works only where the mount
 *     permits it, and always requires the `EXEC` capability grant.
 *  2. [dlopenBridgeAvailable] — whether a dlopen-based bridge (load the
 *     executable as a shared object and call its `main`) is wired; the
 *     actual bridging is left to a native shim the host supplies.
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
     * Runs an executable asset, gating on the `EXEC` capability. Returns the
     * running process, or null when the capability is denied.
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
     * Whether a dlopen-based exec bridge is available. The bridge loads an
     * executable asset as a shared object and invokes its `main`, sidestepping
     * the noexec mount. False by default until a host native shim registers.
     */
    fun dlopenBridgeAvailable(): Boolean = false

    /** Drains a process's merged output to a string (best effort). */
    fun readAll(input: InputStream): String =
        input.bufferedReader().use { it.readText() }
}
