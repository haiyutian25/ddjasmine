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
import kotlinx.coroutines.withTimeoutOrNull
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

    /** The host Application (set by [attach]); null before attach. */
    fun application(): Application? = app

    /** True when [pluginId] is placed in an isolated process. */
    fun isIsolated(pluginId: String): Boolean = isolated.containsKey(pluginId)

    /** The slot a plugin is placed in, or null when not isolated. */
    fun isolatedSlot(pluginId: String): Int? = isolated[pluginId]

    /** Ids currently placed in isolated processes, sorted. */
    fun isolatedIds(): List<String> = isolated.keys.toList().sorted()

    /** Marks a plugin as isolated (persisted); does not start the process. */
    fun markIsolated(pluginId: String) {
        // 此前一律钉槽 1，使 allocateSlot 的负载分散对安装期声明的插件成为
        // 死代码（所有重插件挤进同一进程）。改为按当前占用选最闲的槽。
        if (isolated.putIfAbsent(pluginId, allocateSlot()) == null) persist()
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
        // 仅卸载宿主里的完整主入口副本；UI 伴侣（轻量 UI 入口）必须保留，
        // 否则隔离后插件菜单/界面消失且不会恢复。
        if (PluginHost.isLoaded(pluginId) && !PluginHost.isUiCompanionLoaded(pluginId)) {
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
            // 先解绑再 stop：BIND_AUTO_CREATE 的活动绑定会让 stopService
            // 失效（服务不销毁、进程不收、插件双活）。
            bridgeConnections.remove(slot)?.let { connection ->
                runCatching { application.unbindService(connection) }
            }
            application.stopService(Intent(application, slotServices[slot - 1]))
            hostBridges.remove(slot)
            bridgeDeferred.remove(slot)
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

    /** 指定槽的已绑定 bridge；未绑定返回 null。 */
    fun bridgeFor(slot: Int): PluginProcessBridge? = hostBridges[slot]

    /** Binds (once) a slot's isolated service and waits for its bridge. */
    private suspend fun ensureBridge(application: Application, slot: Int): PluginProcessBridge? {
        hostBridges[slot]?.let { return it }
        bridgeDeferred[slot]?.let { pending ->
            // 已有进行中的绑定：等它的结果，不再重复 bind（重复 bind 会覆盖
            // bridgeConnections 里的连接引用，造成旧连接永久泄漏）。
            if (pending.isActive) return withTimeoutOrNull(BIND_TIMEOUT_MS) { pending.await() }
        }
        val deferred = CompletableDeferred<PluginProcessBridge?>()
        bridgeDeferred[slot] = deferred
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val bridge = PluginProcessBridge.wrap(service)
                if (bridge != null) {
                    hostBridges[slot] = bridge
                    // 把宿主目录 binder 注册进隔离进程：隔离插件据此解析宿主
                    // 发布的远程服务（RemoteServices 双向）。
                    runCatching {
                        bridge.register(PluginProcessBridge.HOST_DIRECTORY_KEY, PluginProcessBridge.server())
                    }
                }
                if (!deferred.isCompleted) deferred.complete(bridge)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                hostBridges.remove(slot)
            }
        }
        bridgeConnections[slot] = connection
        val bound = application.bindService(
            Intent(application, slotServices[slot - 1]),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            // bindService 返回 false = 永远不会回调：必须主动失败，
            // 否则 await 永久挂起（此前无超时无返回值检查）。
            bridgeConnections.remove(slot)
            bridgeDeferred.remove(slot)
            return null
        }
        val result = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        if (result == null) {
            // 超时或桥包装失败：回收连接与等待句柄，允许调用方重试。
            bridgeDeferred.remove(slot)
            bridgeConnections.remove(slot)?.let { runCatching { application.unbindService(it) } }
        }
        return result
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

    /** 跨进程 bridge 绑定的最长等待；超时回收并返回 null（可重试）。 */
    private const val BIND_TIMEOUT_MS = 10_000L
}
