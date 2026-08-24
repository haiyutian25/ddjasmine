package com.lhzkml.jasmine.core.plugin.proxy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.lhzkml.jasmine.core.plugin.PluginHost
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized content-provider proxy. Callers address plugin providers
 * through the host authority, encoding the plugin authority as the first
 * path segment: `content://<hostAuth>/<urlEncodedPluginAuth>/rest/of/path`.
 *
 * Routing is decided by the Rust core (authority → owning plugin);
 * rewriting follows the memo'd rules: drop the first segment, restore the
 * original authority, clear query/fragment; `insert` results are re-wrapped
 * under the host authority. `exported=false` providers reject cross-UID
 * callers.
 */
open class HostProvider : ContentProvider() {

    companion object {
        const val KEY_TARGET_URI = "jasmine.plugin.runtime.TARGET_URI"
    }

    private val instances = ConcurrentHashMap<String, ContentProvider>()

    private var hostAuthority: String? = null

    override fun onCreate(): Boolean {
        hostAuthority = context?.packageName?.let { "$it.plugin.proxy" }
        return true
    }

    /** Builds the proxy URI addressing a plugin provider. */
    fun proxyUri(pluginAuthority: String, path: String): Uri {
        val host = checkNotNull(hostAuthority) { "HostProvider 未就绪" }
        return Uri.Builder()
            .scheme("content")
            .authority(host)
            .appendPath(URLEncoder.encode(pluginAuthority, "UTF-8"))
            .apply { if (path.isNotEmpty()) appendEncodedPath(path) }
            .build()
    }

    private inner class Forward(
        val provider: ContentProvider,
        val rewrittenUri: Uri,
    )

    private fun resolve(uri: Uri): Forward {
        val pluginAuthority = uri.pathSegments.getOrNull(0)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw IllegalArgumentException("无法从 URI 解析插件 authority: $uri")
        val route = PluginHost.coreHandle.routeAuthority(pluginAuthority)
            ?: throw SecurityException("未注册的 Provider authority: $pluginAuthority")
        val (pluginId, className) = route[0] to route[1]

        val spec = PluginHost.providerSpecOf(pluginId, className)
            ?: throw SecurityException("Provider 所属插件已卸载: $pluginId")
        if (!spec.exported && Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("权限拒绝: Provider $className 未导出")
        }

        val provider = instances.getOrPut(className) {
            val p = PluginHost.instantiateComponent(pluginId, className) as? ContentProvider
                ?: throw IllegalStateException("$className 不是 ContentProvider")
            // 补 attachInfo + onCreate：让插件 Provider 的 getContext() 可用并走正常
            // 生命周期（否则直接 newInstance 会导致 getContext() 为 null、onCreate 不执行）。
            val info = ProviderInfo().apply {
                authority = pluginAuthority
                applicationInfo = context?.applicationInfo
            }
            p.attachInfo(context, info)
            p.onCreate()
            p
        }
        val originalPath = uri.pathSegments.drop(1).joinToString("/")
        val rewritten = uri.buildUpon()
            .authority(pluginAuthority)
            .path(originalPath)
            .clearQuery()
            .fragment(null)
            .build()
        return Forward(provider, rewritten)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = resolve(uri).let { it.provider.query(it.rewrittenUri, projection, selection, selectionArgs, sortOrder) }

    override fun getType(uri: Uri): String? = resolve(uri).let { it.provider.getType(it.rewrittenUri) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val forward = resolve(uri)
        val result = forward.provider.insert(forward.rewrittenUri, values) ?: return null
        context?.contentResolver?.notifyChange(uri, null)
        // Re-wrap: host authority + plugin authority as first segment.
        val pluginAuthority = result.authority ?: return result
        val host = hostAuthority ?: return result
        return result.buildUpon()
            .authority(host)
            .path("/${URLEncoder.encode(pluginAuthority, "UTF-8")}${result.path.orEmpty()}")
            .build()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        resolve(uri).let {
            it.provider.delete(it.rewrittenUri, selection, selectionArgs).also { count ->
                if (count > 0) context?.contentResolver?.notifyChange(uri, null)
            }
        }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = resolve(uri).let {
        it.provider.update(it.rewrittenUri, values, selection, selectionArgs).also { count ->
            if (count > 0) context?.contentResolver?.notifyChange(uri, null)
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val target = extras?.getParcelable<Uri>(KEY_TARGET_URI)
            ?: throw IllegalArgumentException("call 请求缺少目标 Uri (KEY_TARGET_URI)")
        extras.remove(KEY_TARGET_URI)
        val forward = resolve(target)
        return forward.provider.call(method, arg, extras)
    }
}
