package com.lhzkml.jasmine.core.plugin

import android.app.Application
import android.content.res.Resources
import androidx.compose.runtime.Composable

/**
 * Runtime context handed to a plugin's [PluginEntry.onLoad]. Resources are
 * injected explicitly — the framework never overrides the host
 * Application's resources; plugin code resolves its own resources through
 * [resources] (package ids are partitioned at packaging time, so host and
 * plugin tables merge without remapping).
 */
class PluginContext(
    val application: Application,
    val pluginId: String,
    val pluginDir: String,
    val resources: Resources,
)

/**
 * Typed handle for cross-plugin service lookup. Plugins publish
 * implementations under a key; consumers resolve by the same key. Replaces
 * container-based DI so the framework adds no container of its own.
 */
class ServiceKey<T : Any>(val name: String) {
    override fun toString(): String = "ServiceKey($name)"
}

/**
 * A plugin's published services: key → implementation.
 */
typealias ServiceTable = Map<ServiceKey<*>, Any>

/**
 * A menu entry the plugin wants to surface in the host's settings list. It
 * is dynamically added while the plugin is loaded and removed on unload —
 * the host never hardcodes it. `iconResId` points into the plugin's own
 * (package-id-partitioned) resources.
 */
class PluginMenuEntry(
    val title: String,
    val subtitle: String? = null,
    val iconResId: Int? = null,
)

/**
 * The contract every plugin's entry class implements. The class name is
 * declared via the `jasmine.plugin.entryClass` manifest meta-data and is
 * instantiated by the framework after its package loads.
 *
 * Lifecycle failures are never swallowed: an exception escaping [onLoad]
 * fails the load (and rolls the batch back); an exception escaping
 * [onUnload] is reported through [PluginHost.loadFailureCallback].
 */
interface PluginEntry {
    /** Services this plugin publishes for host/plugin consumption. */
    val services: ServiceTable
        get() = emptyMap()

    /**
     * The settings-list menu entry, dynamically surfaced by the host while
     * the plugin is loaded. Null (default) means "no menu entry".
     */
    val menuEntry: PluginMenuEntry?
        get() = null

    /** Called after the package loaded; the place for all initialization. */
    fun onLoad(context: PluginContext)

    /** Called before unload; release everything acquired in [onLoad]. */
    fun onUnload()

    /**
     * The plugin's main UI, rendered by the host after the user opens its
     * [menuEntry]. Runs entirely inside the host's Compose tree.
     */
    @Composable
    fun Content() {}
}
