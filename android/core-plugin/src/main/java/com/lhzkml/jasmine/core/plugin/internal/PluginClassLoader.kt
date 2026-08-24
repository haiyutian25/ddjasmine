package com.lhzkml.jasmine.core.plugin.internal

import com.lhzkml.jasmine.core.plugin.rust.FfiLocateOutcome
import dalvik.system.BaseDexClassLoader

/**
 * Thrown when a plugin's class cannot be resolved anywhere: own DEX, every
 * linked plugin, and the host. Carries the culprit plugin and the missing
 * class so the crash hook can attribute precisely.
 */
class PluginLinkException(
    val culpritPluginId: String,
    val missingClassName: String,
    cause: Throwable? = null,
) : ClassNotFoundException(
    "插件 [$culpritPluginId] 依赖的类 [$missingClassName] 未找到",
    cause,
)

/**
 * Per-plugin class loader. Delegation chain (the Rust core decides the
 * target, this class only executes):
 *
 * parent (host) → own DEX → `core.locateClass` → target plugin's
 * [findClassLocally] (recursion-proof) → host fallback →
 * [PluginLinkException].
 *
 * @param loadedPlugins read-only view of pluginId → loaded class loader,
 *   consulted on cross-plugin delegation.
 */
internal class PluginClassLoader(
    val pluginId: String,
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
    private val locate: (className: String, borrower: String) -> FfiLocateOutcome,
    private val loadedPlugins: () -> Map<String, PluginClassLoader>,
) : BaseDexClassLoader(dexPath, null, librarySearchPath, parent) {

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        return try {
            super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            when (val outcome = locate(name, pluginId)) {
                is FfiLocateOutcome.Plugin -> {
                    val target = loadedPlugins()[outcome.pluginId]
                        ?: throw PluginLinkException(pluginId, name, e)
                    try {
                        target.findClassLocally(name)
                    } catch (inner: ClassNotFoundException) {
                        throw PluginLinkException(pluginId, name, inner)
                    }
                }
                FfiLocateOutcome.HostFallback -> try {
                    parent.loadClass(name)
                } catch (inner: ClassNotFoundException) {
                    throw PluginLinkException(pluginId, name, inner)
                }
            }
        }
    }

    /** Own-DEX-only lookup; never delegates, so cross-plugin recursion is impossible. */
    @Throws(ClassNotFoundException::class)
    fun findClassLocally(name: String): Class<*> =
        // 先查已加载表：跨插件借类是“定义发生在目标 loader”，同一类第二次被
        // 借用时裸 findClass 会重复 defineClass 抛 LinkageError（不是 CNFE，
        // 借方 catch 接不住）。
        findLoadedClass(name) ?: findClass(name)
}
