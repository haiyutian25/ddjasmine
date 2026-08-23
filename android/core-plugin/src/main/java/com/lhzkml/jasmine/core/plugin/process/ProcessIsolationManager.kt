package com.lhzkml.jasmine.core.plugin.process

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lhzkml.jasmine.core.plugin.PluginHost
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the plugin→process mapping for isolated plugins. In-process plugins
 * need nothing here; a plugin opting into isolation (heavy native) is moved
 * into the `:plugin_isolated` process via [isolate], and [release] stops the
 * process on unload/disable.
 *
 * This is the runtime half of the framework's process model; the persisted
 * isolation preference lives in the Rust ledger's plugin record (future
 * field), while the live process mapping is session-scoped here.
 */
object ProcessIsolationManager {

    /** plugin id → process anchor (the isolated host service). */
    private val isolated = ConcurrentHashMap<String, String>()

    /** Host-scoped bridge, bound once from the host process. */
    @Volatile
    private var hostBridge: PluginProcessBridge? = null

    private var app: Application? = null

    /** Installs the manager (host calls once during init). */
    fun attach(application: Application) {
        app = application
    }

    /** True when [pluginId] is currently isolated into its own process. */
    fun isIsolated(pluginId: String): Boolean = isolated.containsKey(pluginId)

    /** Ids currently isolated. */
    fun isolatedIds(): List<String> = isolated.keys.sorted()

    /**
     * Moves a plugin into the isolated process. Its classes/native libs load
     * there; a crash is contained and reclaimable by killing the process.
     */
    suspend fun isolate(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val application = app ?: return@withContext false
        if (isolated.containsKey(pluginId)) return@withContext true
        val started = bindBridge(application, pluginId)
        if (started) {
            isolated[pluginId] = pluginId
            // Re-launch the plugin in the isolated process once bound.
            PluginHost.launchPlugin(pluginId)
        }
        started
    }

    /** Releases isolation and stops the process; the plugin stays installed. */
    fun release(pluginId: String) {
        if (isolated.remove(pluginId) == null) return
        val application = app ?: return
        val intent = Intent(application, IsolatedPluginProcessService::class.java)
        application.stopService(intent)
    }

    /** The host-scoped bridge, or null before the first bind. */
    fun bridge(): PluginProcessBridge? = hostBridge

    private fun bindBridge(application: Application, pluginId: String): Boolean {
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                hostBridge = PluginProcessBridge.wrap(service)
                bound = true
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                hostBridge = null
            }
        }
        val intent = Intent(application, IsolatedPluginProcessService::class.java)
            .putExtra(IsolatedPluginProcessService.EXTRA_PLUGIN_ID, pluginId)
        application.startService(intent)
        // Binding is asynchronous; the bridge becomes available shortly.
        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return true
    }
}
