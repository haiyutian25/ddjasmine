package com.lhzkml.jasmine.core.plugin.internal

import android.app.Application
import android.os.Build
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
import com.lhzkml.jasmine.core.plugin.ServiceKey
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
)

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
    var failureCallback: LoadFailureCallback? = null,
) {
    private val loaded = ConcurrentHashMap<String, LoadedPlugin>()
    private val serviceTables = ConcurrentHashMap<String, Map<ServiceKey<*>, Any>>()

    /** Invoked whenever the loaded set changes (load/unload), so the host
     *  can recompute reactive views like the dynamic menu entries. */
    var onChange: (() -> Unit)? = null

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

        val classLoader = PluginClassLoader(
            pluginId = pluginId,
            dexPath = payloadFile(pluginId).absolutePath,
            librarySearchPath = librarySearchPath(pluginId),
            parent = application.classLoader,
            locate = { className, borrower -> core.locateClass(className, borrower) },
            loadedPlugins = { loaded.mapValues { it.value.classLoader } },
        )
        try {
            val entry = instantiateEntry(classLoader, record.entryClass, pluginId)
            val resources = PluginResourcesLoader.load(
                application,
                payloadFile(pluginId).absolutePath,
            )
            entry.onLoad(
                PluginContext(
                    application = application,
                    pluginId = pluginId,
                    pluginDir = record.installPath,
                    resources = resources,
                ),
            )
            loaded[pluginId] = LoadedPlugin(record, classLoader, entry, resources)
            serviceTables[pluginId] = entry.services
            registerComponents(record)
            onChange?.invoke()
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
        try {
            plugin.entry.onUnload()
        } catch (e: Throwable) {
            failureCallback?.onFailure(pluginId, "unload", e)
        }
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

    private fun registerComponents(record: FfiPluginRecord) {
        val receivers = record.staticReceiversJson.receiversFromJson()
        if (receivers.isNotEmpty()) {
            core.registerReceivers(record.pluginId, receivers.map { it.toFfi() })
        }
        val providers = record.providersJson.providersFromJson()
        if (providers.isNotEmpty()) {
            core.registerProviders(record.pluginId, providers.map { it.toFfi() })
        }
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
