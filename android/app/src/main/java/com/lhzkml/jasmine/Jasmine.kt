package com.lhzkml.jasmine

import android.util.Log
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.PluginHostApplication
import com.lhzkml.jasmine.core.plugin.SignaturePolicy
import com.lhzkml.jasmine.rust.FfiSessionHeader
import com.lhzkml.jasmine.rust.SessionLogHandle
import dagger.hilt.android.HiltAndroidApp
import java.util.UUID

@HiltAndroidApp
class Jasmine : PluginHostApplication() {

    /**
     * Debug builds sign every module with the same debug key, so the
     * bundled sample plugin passes the Strict gate as host-trusted.
     */
    override fun pluginPolicy(): SignaturePolicy = SignaturePolicy.Strict

    override fun onPluginFrameworkReady(): suspend () -> Unit = {
        PluginHost.updateManifestBaseUrl = "https://updates.example.com/jasmine"
        val installed = PluginHost.installBundledPlugins()
        if (installed.isNotEmpty()) {
            Log.i(TAG, "bundled plugins installed: $installed")
        }
        for (pluginId in PluginHost.allPlugins().map { it.pluginId }) {
            if (!PluginHost.isLoaded(pluginId)) {
                runCatching { PluginHost.launchPlugin(pluginId) }
                    .onFailure { Log.e(TAG, "launch failed: $pluginId", it) }
            }
        }
        Log.i(TAG, "plugin runtime ready: loaded=${PluginHost.loadedPluginIds()}")

        // Network update check against the placeholder manifest host; any
        // failure is logged, never fatal. Point the base URL at a real
        // server (updates/<id>.json per plugin) to enable delivery.
        val updated = runCatching { PluginHost.applyAvailableUpdates() }.getOrDefault(emptyList())
        if (updated.isNotEmpty()) Log.i(TAG, "插件已热更: $updated")
    }

    private companion object {
        const val TAG = "Jasmine"
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
        installCrashRecorder()
        rustSmokeTest()
    }

    /**
     * Persists the last uncaught JVM crash under files/crash/last-crash.txt.
     * Native tombstones still require logcat, but Java/Kotlin crashes become
     * inspectable after relaunch even when no development machine is attached.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { AppLog.crash(thread, failure) }
            previous?.uncaughtException(thread, failure)
        }
    }

    /**
     * Proves the Rust data spine is loadable and round-trips on this device:
     * create an in-memory session log, append one user message, project the
     * history back. Failures are logged loudly but never crash startup —
     * the demo UI must stay reachable even when the spine is missing (for
     * example an ABI not packaged in a test build).
     */
    private fun rustSmokeTest() {
        try {
            System.loadLibrary("ffi")
            val log = SessionLogHandle.inMemory(
                FfiSessionHeader(
                    formatVersion = 0u,
                    sessionId = UUID.randomUUID().toString(),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            log.append("user/message", """{"content":"hello from Kotlin"}""")
            val messages = log.deriveMessages(ULong.MAX_VALUE)
            check(messages.size == 1 && messages.first().content == "hello from Kotlin") {
                "unexpected projection: $messages"
            }
            Log.i(TAG, "rust spine ok: ${log.eventCount()} events, projection round-trips")
        } catch (t: Throwable) {
            Log.e(TAG, "rust spine unavailable", t)
        }
    }

}
