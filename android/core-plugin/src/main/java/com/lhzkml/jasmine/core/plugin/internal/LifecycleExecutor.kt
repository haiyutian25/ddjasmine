package com.lhzkml.jasmine.core.plugin.internal

import android.app.Application
import android.os.Build
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
import com.lhzkml.jasmine.core.plugin.ServiceKey
import com.lhzkml.jasmine.core.plugin.proxy.StaticReceiverDispatcher
import com.lhzkml.jasmine.core.plugin.rust.FfiPluginRecord
import com.lhzkml.jasmine.core.plugin.rust.PluginCoreHandle
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A loaded plugin: registry record, class loader, entry instance, resources. */
internal class LoadedPlugin(
    val record: FfiPluginRecord,
    val classLoader: PluginClassLoader,
    val entry: PluginEntry,
    val resources: android.content.res.Resources,
    val app: android.app.Application? = null,
    /** 宿主进程里加载的隔离插件 UI 伴侣（主入口在隔离进程）。 */
    val uiOnly: Boolean = false,
    private val resourcesHolder: LoadedResources,
) {
    /** Releases native resource tables on unload. */
    fun release() {
        resourcesHolder.close()
    }
}

/** Lifecycle failure surfaced to the host (never swallowed). */
fun interface LoadFailureCallback {
    fun onFailure(pluginId: String, phase: String, error: Throwable)
}

/**
 * Executes load/unload/restart against the Rust core's plans. Owns the
 * loaded-plugin set; the ledger owns installed state. Batch loads are
 * all-or-nothing: any failure unloads everything the batch already loaded.
 */
internal class LifecycleExecutor(
    private val application: Application,
    private val core: PluginCoreHandle,
    private val payloadFile: (String) -> File,
    private val libDir: (String) -> File,
    private val readDependencies: (String) -> List<String> = { emptyList() },
    private val readUiEntryClass: (String) -> String? = { null },
    private val readApplicationClass: (String) -> String? = { null },
    var failureCallback: LoadFailureCallback? = null,
) {
    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    private val serviceTables = ConcurrentHashMap<String, Map<ServiceKey<*>, Any>>()
    private val staticActions = ConcurrentHashMap<String, Set<String>>()

    /** Invoked whenever the loaded set changes (load/unload), so the host
     *  can recompute reactive views like the dynamic menu entries. */
    var onChange: (() -> Unit)? = null

    /** 宿主加载了隔离插件的 UI 伴侣后回调（用于启动隔离进程的主入口）。 */
    var onUiCompanionLoaded: ((String) -> Unit)? = null

    /** 宿主里加载的是否为 UI 伴侣（而非完整主入口）。 */
    fun isUiOnlyLoaded(pluginId: String): Boolean = loaded[pluginId]?.uiOnly == true

    val loadedPlugins: Map<String, LoadedPlugin> get() = loaded

    fun isLoaded(pluginId: String): Boolean = loaded.containsKey(pluginId)

    fun loadedIds(): List<String> = loaded.keys.sorted()

    fun entryOf(pluginId: String): PluginEntry? = loaded[pluginId]?.entry

    /** Resolves a published service, searching host-registered tables then plugins. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolveService(key: ServiceKey<T>): T? =
        serviceTables.values.firstNotNullOfOrNull { it[key] } as? T

    /**
     * Loads one installed plugin: class loader → entry instantiation →
     * [PluginEntry.onLoad] → component route registration. Any failure
     * rolls the plugin back out and rethrows.
     */
    fun load(record: FfiPluginRecord) {
        val pluginId = record.pluginId
        if (loaded.containsKey(pluginId)) return
        check(record.enabled) { "插件已禁用: $pluginId" }

        // 宿主进程对 isolated 插件：若有 UI 入口则加载 UI 入口（轻量 UI 类），
        // 否则跳过（native 逻辑由隔离进程加载）。隔离进程始终加载主入口。
        val isIsolated =
            com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager.isIsolated(pluginId)
        val inIsolatedProcess =
            com.lhzkml.jasmine.core.plugin.process.ProcessIdentity.isIsolatedProcess(application)
        val uiEntryClass = readUiEntryClass(pluginId)
        if (isIsolated && !inIsolatedProcess && uiEntryClass == null) return
        val effectiveEntry =
            if (isIsolated && !inIsolatedProcess && uiEntryClass != null) uiEntryClass
            else record.entryClass
        val uiOnly = isIsolated && !inIsolatedProcess && uiEntryClass != null

        // Declared-dependency preload: load not-yet-loaded dependencies first
        // (dependency order), then record the edges so dependent-ordered
        // unload and chained restart work. A missing/uninstalled dependency
        // is reported but not fatal — borrow-on-first-use still backstops it.
        val dependencies = readDependencies(pluginId)
        for (dep in dependencies) {
            if (dep == pluginId || loaded.containsKey(dep)) continue
            val depRecord = core.pluginRecord(dep)
            if (depRecord == null) {
                failureCallback?.onFailure(
                    pluginId,
                    "dependency",
                    IllegalStateException("依赖插件未安装: $dep"),
                )
                continue
            }
            if (depRecord.enabled) load(depRecord)
        }
        core.declareDependencies(pluginId, dependencies)

        val classLoader = PluginClassLoader(
            pluginId = pluginId,
            dexPath = payloadFile(pluginId).absolutePath,
            librarySearchPath = librarySearchPath(pluginId),
            parent = application.classLoader,
            locate = { className, borrower -> core.locateClass(className, borrower) },
            loadedPlugins = { loaded.mapValues { it.value.classLoader } },
        )
        try {
            val entry = instantiateEntry(classLoader, effectiveEntry, pluginId)
            // 实例化插件 Application（仅主入口；UI 入口不实例化）。
            val pluginApp = if (!uiOnly) {
                instantiatePluginApplication(classLoader, readApplicationClass(pluginId), pluginId)
            } else {
                null
            }
            val loadedResources = PluginResourcesLoader.load(
                application,
                payloadFile(pluginId).absolutePath,
            )
            val context = PluginContext(
                application = application,
                pluginId = pluginId,
                pluginDir = record.installPath,
                resources = loadedResources.resources,
            )
            entry.onLoad(context)
            if (!uiOnly) entry.onNativeReady(context)
            loaded[pluginId] = LoadedPlugin(record, classLoader, entry, loadedResources.resources, pluginApp, uiOnly, loadedResources)
            serviceTables[pluginId] = entry.services
            // UI 入口不注册 receivers/providers（这些组件属于主入口所在进程）。
            staticActions[pluginId] = if (uiOnly) emptySet() else registerComponents(record)
            onChange?.invoke()
            if (uiOnly) {
                // 宿主只加载了 UI 伴侣：主入口仍需在隔离进程启动。此前无人
                // 触发，重启后隔离进程永远不启动（界面却显示“已加载”）。
                onUiCompanionLoaded?.invoke(pluginId)
            }
        } catch (e: Throwable) {
            loaded.remove(pluginId)
            serviceTables.remove(pluginId)
            core.pluginUnloaded(pluginId)
            failureCallback?.onFailure(pluginId, "load", e)
            throw e
        }
    }

    /** Unloads a loaded plugin; the registry entry stays. */
    fun unload(pluginId: String) {
        val plugin = loaded.remove(pluginId) ?: return
        serviceTables.remove(pluginId)
        staticActions.remove(pluginId)?.let { StaticReceiverDispatcher.unregisterActions(application, it) }
        // 与 onLoad 对称：UI 伴侣加载时未调 onNativeReady，卸载也不应调
        // onNativeRelease（否则对未初始化的 native 侧做释放）。
        if (!plugin.uiOnly) {
            try {
                plugin.entry.onNativeRelease()
            } catch (e: Throwable) {
                failureCallback?.onFailure(pluginId, "native-release", e)
            }
        }
        try {
            plugin.entry.onUnload()
        } catch (e: Throwable) {
            failureCallback?.onFailure(pluginId, "unload", e)
        }
        plugin.release()
        core.pluginUnloaded(pluginId)
        onChange?.invoke()
    }

    /** Loads every enabled, not-yet-loaded plugin; any failure rolls back the batch. */
    fun loadEnabled(records: List<FfiPluginRecord>) {
        val loadedSoFar = mutableListOf<String>()
        try {
            for (record in records) {
                if (!record.enabled || loaded.containsKey(record.pluginId)) continue
                load(record)
                loadedSoFar += record.pluginId
            }
        } catch (e: Throwable) {
            loadedSoFar.asReversed().forEach { unload(it) }
            throw e
        }
    }

    /**
     * Executes the core's chained-restart plan: dependents unload before
     * their lender, reload after it. Records for reload are looked up from
     * the ledger by the caller and passed in plan order.
     */
    fun executeRestart(unloadOrder: List<String>, reloadRecords: List<FfiPluginRecord>) {
        unloadOrder.forEach { unload(it) }
        reloadRecords.forEach { load(it) }
    }

    private fun instantiateEntry(
        classLoader: PluginClassLoader,
        entryClass: String,
        pluginId: String,
    ): PluginEntry {
        val clazz = classLoader.loadClass(entryClass)
        val instance = clazz.getDeclaredConstructor().newInstance()
        return instance as? PluginEntry
            ?: throw IllegalStateException(
                "入口类 [$entryClass] 未实现 PluginEntry（插件 $pluginId）",
            )
    }

    /**
     * Instantiates a plugin's declared Application (reflective `attach` with
     * the host Application as base context) and calls `onCreate`. Failures are
     * reported but never fail the load.
     */
    private fun instantiatePluginApplication(
        classLoader: PluginClassLoader,
        applicationClass: String?,
        pluginId: String,
    ): android.app.Application? {
        if (applicationClass.isNullOrBlank()) return null
        return try {
            val clazz = classLoader.loadClass(applicationClass)
            val app = clazz.getDeclaredConstructor().newInstance() as? android.app.Application
                ?: return null
            // Application.onCreate 契约要求主线程：插件常在其中建 Handler、
            // 弹 Toast、初始化主线程 SDK。加载流程跑在 IO 线程，这里切到
            // 主线程同步执行（此前在 IO 线程直接调，违反契约）。
            runOnMainThread {
                val attach = android.app.Application::class.java
                    .getDeclaredMethod("attach", android.content.Context::class.java)
                attach.isAccessible = true
                attach.invoke(app, application)
                app.onCreate()
            }
            app
        } catch (e: Throwable) {
            failureCallback?.onFailure(pluginId, "application", e)
            null
        }
    }

    /** 在主线程同步执行 [block]；已在主线程则直接执行。异常原样抛回调用线程。 */
    private fun runOnMainThread(block: () -> Unit) {
        val mainLooper = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == mainLooper) {
            block()
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var error: Throwable? = null
        android.os.Handler(mainLooper).post {
            try {
                block()
            } catch (e: Throwable) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error?.let { throw it }
    }

    /**
     * 配置变化（语言/深色/字号）时刷新所有插件 Resources 快照。插件 Resources
     * 是用加载时刻的宿主 configuration 独立构造的，不会自动跟随系统——此处
     * 手动 updateConfiguration 让插件资源与宿主保持同步（updateConfiguration
     * 虽已废弃，但对未注册进 ResourcesManager 的独立 Resources 仍是唯一可行
     * 的刷新手段）。
     */
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val metrics = application.resources.displayMetrics
        loaded.values.forEach { plugin ->
            runCatching { plugin.resources.updateConfiguration(newConfig, metrics) }
        }
    }

    /** Forwards low-memory to every loaded plugin Application. */
    fun notifyLowMemory() {
        loaded.values.forEach { it.app?.onLowMemory() }
    }

    /** Forwards trim-memory to every loaded plugin Application. */
    fun notifyTrimMemory(level: Int) {
        loaded.values.forEach { it.app?.onTrimMemory(level) }
    }

    private fun registerComponents(record: FfiPluginRecord): Set<String> {
        val receivers = record.staticReceiversJson.receiversFromJson()
        if (receivers.isNotEmpty()) {
            core.registerReceivers(record.pluginId, receivers.map { it.toFfi() })
        }
        val providers = record.providersJson.providersFromJson()
        if (providers.isNotEmpty()) {
            core.registerProviders(record.pluginId, providers.map { it.toFfi() })
        }
        val actions = receivers.flatMap { r -> r.intentFilters.flatMap { it.actions } }.toSet()
        if (actions.isNotEmpty()) {
            StaticReceiverDispatcher.registerActions(application, actions)
        }
        return actions
    }

    /**
     * Native library search path: extracted ABI directories in device
     * preference order, then the payload itself (in-APK libraries).
     */
    private fun librarySearchPath(pluginId: String): String {
        val paths = mutableListOf<String>()
        val libRoot = libDir(pluginId)
        for (abi in Build.SUPPORTED_ABIS) {
            val dir = File(libRoot, abi)
            if (dir.isDirectory) paths += dir.absolutePath
        }
        paths += payloadFile(pluginId).absolutePath
        return paths.joinToString(File.pathSeparator)
    }
}
