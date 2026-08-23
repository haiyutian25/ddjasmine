package com.lhzkml.jasmine.core.plugin.process

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.lhzkml.jasmine.core.plugin.PluginHost
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Owns the plugin→process mapping for isolated plugins and persists it so a
 * restart restores the same placement.
 *
 * The mapping is host-side policy (not a plugin's trusted declaration): the
 * host decides which heavy-native plugins move into the `:plugin_isolated`
 * process. [isolate] unloads the plugin from the host process, starts the
 * isolated host service, and the isolated process loads the plugin there.
 * [release] stops the process and forgets the placement.
 *
 * The host also binds the isolated service once to obtain the cross-process
 * [PluginProcessBridge], used by [RemoteServices] to resolve Binder-backed
 * services across the boundary.
 */
object ProcessIsolationManager {

    private val isolated = ConcurrentHashMap.newKeySet<String>()

    private var app: Application? = null

    /** Host-scoped bridge, bound once from the host process. */
    @Volatile
    private var hostBridge: PluginProcessBridge? = null

    private var bridgeDeferred: CompletableDeferred<PluginProcessBridge?>? = null

    // Keep a strong reference so the ServiceConnection is not GC'd before the
    // async callback fires.
    private var bridgeConnection: ServiceConnection? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Installs the manager and restores the persisted placement. */
    fun attach(application: Application) {
        app = application
        loadPersisted(application)
    }

    /** True when [pluginId] is placed in the isolated process. */
    fun isIsolated(pluginId: String): Boolean = pluginId in isolated

    /** Ids currently placed in the isolated process, sorted. */
    fun isolatedIds(): List<String> = isolated.toList().sorted()

    /** Marks a plugin as isolated (persisted); does not start the process. */
    fun markIsolated(pluginId: String) {
        if (isolated.add(pluginId)) persist()
    }

    /** Unmarks a plugin as isolated (persisted); does not stop the process. */
    fun unmarkIsolated(pluginId: String) {
        if (isolated.remove(pluginId)) persist()
    }

    /**
     * Moves a plugin into the isolated process: persist the placement, unload
     * any host-process copy, start the isolated host service (which loads the
     * plugin in its own process), and bind the cross-process bridge.
     */
    suspend fun isolate(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val application = app ?: return@withContext false
        markIsolated(pluginId)
        if (PluginHost.isLoaded(pluginId)) {
            runCatching { PluginHost.unloadPlugin(pluginId) }
        }
        val intent = Intent(application, IsolatedPluginProcessService::class.java)
            .putExtra(IsolatedPluginProcessService.EXTRA_PLUGIN_ID, pluginId)
        application.startService(intent)
        ensureBridge(application)
        true
    }

    /** Releases isolation and stops the process; the plugin stays installed. */
    fun release(pluginId: String) {
        unmarkIsolated(pluginId)
        val application = app ?: return
        application.stopService(Intent(application, IsolatedPluginProcessService::class.java))
        hostBridge = null
        bridgeDeferred = null
        bridgeConnection = null
    }

    /** The host-scoped cross-process bridge, or null before the first bind. */
    fun bridge(): PluginProcessBridge? = hostBridge

    /** Binds (once) the isolated service and waits for the bridge. */
    private suspend fun ensureBridge(application: Application): PluginProcessBridge? {
        hostBridge?.let { return it }
        val deferred = CompletableDeferred<PluginProcessBridge?>()
        bridgeDeferred = deferred
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val bridge = PluginProcessBridge.wrap(service)
                hostBridge = bridge
                if (!deferred.isCompleted) deferred.complete(bridge)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                hostBridge = null
            }
        }
        bridgeConnection = connection
        val intent = Intent(application, IsolatedPluginProcessService::class.java)
        application.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return deferred.await()
    }

    private fun loadPersisted(application: Application) {
        val file = File(application.filesDir, PERSIST_FILE)
        if (!file.exists()) return
        runCatching {
            json.decodeFromString<List<String>>(file.readText())
        }.getOrDefault(emptyList()).forEach { isolated.add(it) }
    }

    private fun persist() {
        val application = app ?: return
        runCatching {
            File(application.filesDir, PERSIST_FILE)
                .writeText(json.encodeToString(isolated.toList().sorted()))
        }
    }

    private const val PERSIST_FILE = "isolated_plugins.json"
}
