package com.lhzkml.jasmine.core.plugin.process

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import java.io.File

/**
 * Process identity helpers. The framework runs in at least two processes:
 * the host (default) and the isolated `:plugin_isolated` process declared by
 * [IsolatedPluginProcessService]. Startup behavior diverges by process, so a
 * reliable process-name probe is the entry point.
 */
object ProcessIdentity {

    /** The manifest `android:process` suffix of the isolated host. */
    const val ISOLATED_SUFFIX = ":plugin_isolated"

    @Volatile
    private var cached: String? = null

    /** The current process's full name (e.g. `com.example.app:plugin_isolated`). */
    fun currentProcessName(application: Application): String {
        cached?.let { return it }
        val name = runCatching {
            // /proc/self/cmdline holds the process name (NUL-terminated).
            File("/proc/self/cmdline").readText().trimEnd('\u0000')
        }.getOrNull().takeIf { !it.isNullOrBlank() } ?: runCatching {
            val am = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
        }.getOrNull() ?: application.packageName
        cached = name
        return name
    }

    /** True when this code is executing in the isolated plugin process. */
    fun isIsolatedProcess(application: Application): Boolean =
        currentProcessName(application).endsWith(ISOLATED_SUFFIX)
}
