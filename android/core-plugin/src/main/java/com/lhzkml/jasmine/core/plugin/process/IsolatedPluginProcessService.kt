package com.lhzkml.jasmine.core.plugin.process

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lhzkml.jasmine.core.plugin.PluginHost

/**
 * The isolated-process host. Declared with `android:process=":plugin_isolated"`
 * in the library manifest, this service runs a single plugin in a private
 * process so heavy-native plugins (MNN inference, Proot rootfs) cannot drag
 * the host down on a crash and can be memory-reclaimed by killing the
 * process.
 *
 * On bind it exposes the process-local [PluginProcessBridge.server], through
 * which the plugin publishes cross-process service tokens and the host (or
 * sibling processes) resolve them.
 *
 * Lifecycle is driven by [ProcessIsolationManager]: it starts this service,
 * receives the plugin id via `EXTRA_PLUGIN_ID`, and stops it on unload.
 */
class IsolatedPluginProcessService : Service() {

    override fun onBind(intent: Intent?): IBinder = PluginProcessBridge.server()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID) ?: return START_NOT_STICKY
        // The isolated process owns its own PluginHost instance (process-local
        // singleton); launch the plugin here so its classes, native libs and
        // services live in this process, not the host's.
        if (!PluginHost.isInitialized) {
            // Initialization is asynchronous; the manager re-launches once
            // ready. The service itself only anchors the process.
            intent.putExtra(EXTRA_PENDING, pluginId)
        }
        return START_STICKY
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "jasmine.plugin.process.pluginId"
        const val EXTRA_PENDING = "jasmine.plugin.process.pending"
    }
}
