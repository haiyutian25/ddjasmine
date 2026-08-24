package com.lhzkml.jasmine.core.plugin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.compose.runtime.Composable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
) {
    private val dir: File get() = File(pluginDir)

    /**
     * 插件专属 SharedPreferences。命名空间以 `plugin_<pluginId>_` 隔离，
     * 不与宿主或其他插件冲突；卸载时由框架统一清理。
     */
    fun prefs(name: String = "default"): SharedPreferences =
        application.getSharedPreferences("plugin_${pluginId}_$name", Context.MODE_PRIVATE)

    /** 插件私有数据目录（等价于 pluginDir，确保已创建）。 */
    fun filesDir(): File = dir.apply { mkdirs() }

    /** 打开插件私有文件输出流。 */
    fun openFileOutput(name: String, mode: Int = Context.MODE_PRIVATE): FileOutputStream =
        FileOutputStream(File(dir.apply { mkdirs() }, name))

    /** 打开插件私有文件输入流。 */
    fun openFileInput(name: String): FileInputStream =
        FileInputStream(File(dir, name))
}

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
     * Called after [onLoad] when native libraries are extractable and the
     * plugin may initialize heavy native state (inference engines, exec
     * bridges). Runs before [MainScreen] is first composed. The default is a
     * no-op so pure-JVM plugins pay nothing.
     */
    fun onNativeReady(context: PluginContext) {}

    /**
     * Called before [onUnload] to release heavy native state (engines,
     * native heaps, exec sessions) that must be torn down while the class
     * loader is still alive. Paired with [onNativeReady].
     */
    fun onNativeRelease() {}

    /**
     * The plugin's main UI, rendered by the host after the user opens its
     * [menuEntry]. Runs entirely inside the host's Compose tree.
     */
    @Composable
    fun MainScreen() {}
}
