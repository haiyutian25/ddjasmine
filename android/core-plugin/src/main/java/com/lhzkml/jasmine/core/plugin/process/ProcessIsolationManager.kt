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
 * host decides which heavy-native plugins move into an isolated process.
 * [isolate] unloads the plugin from the host process, starts the isolated
 * host service for an allocated slot, and the isolated process loads the
 * plugin there. [release] stops the process and forgets the placement.
 *
 * Multiple process slots (`:plugin_isolated`, `:plugin_isolated_2..4`) let the
 * host spread heavy plugins across private processes, so one crashing plugin
 * doesn't take down its peers. Each slot binds its own cross-process
 * [PluginProcessBridge], used by [RemoteServices] to resolve Binder-backed
 * services across the boundary.
 */
object ProcessIsolationManager {

    /** pluginId → 1-based process slot. */
    private val isolated = ConcurrentHashMap<String, Int>()

    private var app: Application? = null

    /** Slot → bound cross-process bridge (one per slot). */
    private val hostBridges = ConcurrentHashMap<Int, PluginProcessBridge>()

    private val bridgeDeferred = ConcurrentHashMap<Int, CompletableDeferred<PluginProcessBridge?>>()

    // Keep strong references so ServiceConnections aren't GC'd before callbacks fire.
    private val bridgeConnections = ConcurrentHashMap<Int, ServiceConnection>()

    private val json = Json { ignoreUnknownKeys = true }

    /** Manifest-declared isolated services, indexed by slot (1-based). */
    private val slotServices: List<Class<out IsolatedPluginProcessService>> = listOf(
        IsolatedPluginProcessService::class.java,
        IsolatedPluginProcessService2::class.java,
        IsolatedPluginProcessService3::class.java,
        IsolatedPluginProcessService4::class.java,
    )

    /** Installs the manager and restores the persisted placement. */
    fun attach(application: Application) {
        app = application
        loadPersisted(application)
    }

    /** True when [pluginId] is placed in an isolated process. */
    fun isIsolated(pluginId: String): Boolean = isolated.containsKey(pluginId)

    /** The slot a plugin is placed in, or null when not isolated. */
    fun isolatedSlot(pluginId: String): Int? = isolated[pluginId]

    /** Ids currently placed in isolated processes, sorted. */
    fun isolatedIds(): List<String> = isolated.keys.toList().sorted()

    /** Marks a plugin as isolated (persisted); does not start the process. */
    fun markIsolated(pluginId: String) {
        if (isolated.putIfAbsent(pluginId, 1) == null) persist()
    }

    /** Unmarks a plugin as isolated (persisted); does not stop the process. */
    fun unmarkIsolated(pluginId: String) {
        if (isolated.remove(pluginId) != null) persist()
    }

    /**
     * Moves a plugin into an isolated process: allocate a slot, persist, unload
     * any host-process copy, start the slot's isolated host service (which loads
     * the plugin in its own process), and bind that slot's cross-process bridge.
     */
    suspend fun isolate(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val application = app ?: return@withContext false
        val slot = isolated[pluginId] ?: allocateSlot()
        isolated[pluginId] = slot
        persist()
        if (PluginHost.isLoaded(pluginId)) {
            runCatching { PluginHost.unloadPlugin(pluginId) }
        }
        val service = slotServices[slot - 1]
        application.startService(
            Intent(application, service)
                .putExtra(IsolatedPluginProcessService.EXTRA_PLUGIN_ID, pluginId),
        )
        ensureBridge(application, slot)
        true
    }

    /** Releases isolation and stops the slot's process; the plugin stays installed. */
    fun release(pluginId: String) {
        val slot = isolated.remove(pluginId) ?: return
        persist()
        val application = app ?: return
        // Stop the slot only when no other plugin still occupies it.
        if (isolated.values.none { it == slot }) {
            application.stopService(Intent(application, slotServices[slot - 1]))
            hostBridges.remove(slot)
            bridgeDeferred.remove(slot)
            bridgeConnections.remove(slot)
        }
    }

    /** Picks the least-loaded slot (round-robin-ish by occupancy). */
    private fun allocateSlot(): Int {
        val counts = IntArray(slotServices.size)
        isolated.values.forEach { slot -> if (slot in 1..slotServices.size) counts[slot - 1]++ }
        var min = 0
        for (i in 1 until counts.size) if (counts[i] < counts[min]) min = i
        return min + 1
    }

    /** The first bound bridge (backward-compatible single-bridge access). */
    fun bridge(): PluginProcessBridge? = hostBridges.values.firstOrNull()

    /** All bound slot bridges, for cross-process service resolution. */
    fun bridges(): List<PluginProcessBridge> = hostBridges.values.toList()

    /** Binds (once) a slot's isolated service and waits for its bridge. */
    private suspend fun ensureBridge(application: Application, slot: Int): PluginProcessBridge? {
        hostBridges[slot]?.let { return it }
        val deferred = CompletableDeferred<PluginProcessBridge?>()
        bridgeDeferred[slot] = deferred
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val bridge = PluginProcessBridge.wrap(service)
                if (bridge != null) hostBridges[slot] = bridge
                if (!deferred.isCompleted) deferred.complete(bridge)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                hostBridges.remove(slot)
            }
        }
        bridgeConnections[slot] = connection
        application.bindService(
            Intent(application, slotServices[slot - 1]),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        return deferred.await()
    }

    private fun loadPersisted(application: Application) {
        val file = File(application.filesDir, PERSIST_FILE)
        if (!file.exists()) return
        runCatching {
            json.decodeFromString<Map<String, Int>>(file.readText())
        }.getOrDefault(emptyMap()).forEach { (id, slot) -> isolated[id] = slot }
    }

    private fun persist() {
        val application = app ?: return
        runCatching {
            File(application.filesDir, PERSIST_FILE)
                .writeText(json.encodeToString(isolated.toMap()))
        }
    }

    private const val PERSIST_FILE = "isolated_plugins.json"
}
