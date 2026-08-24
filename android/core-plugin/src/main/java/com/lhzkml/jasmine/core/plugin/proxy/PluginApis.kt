package com.lhzkml.jasmine.core.plugin.proxy

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import java.net.URLEncoder

/**
 * 插件侧辅助 API：插件代码里启动 Activity/Service、读写 ContentProvider 时，
 * 经宿主代理组件与中心化 Provider 路由，无需感知宿主的代理类与代理 authority。
 */

/**
 * 启动一个插件 Activity（框架按 [launchMode] 路由到对应占坑代理），
 * 可携带额外 Intent 数据。
 */
fun Context.startPluginActivity(
    pluginActivityClass: Class<*>,
    launchMode: PluginLaunchMode = PluginLaunchMode.Standard,
    block: Intent.() -> Unit = {},
) {
    startActivity(
        Intent(this, launchMode.proxyClass()).apply {
            putExtra(ProxyKeys.ACTIVITY_CLASS, pluginActivityClass.name)
            block()
        },
    )
}

/** 启动插件 Service（池代理），可携带额外 Intent 数据。 */
fun Context.startPluginService(
    serviceClass: Class<*>,
    instanceId: String,
    block: Intent.() -> Unit = {},
): Boolean {
    val serviceClassName = serviceClass.name
    val (fullId, proxyClass) = ServiceProxyPool.acquire(serviceClassName, instanceId)
        ?: return false
    startService(
        Intent(this, proxyClass).apply {
            putExtra(ProxyKeys.SERVICE_CLASS, serviceClassName)
            putExtra(ProxyKeys.SERVICE_INSTANCE_ID, fullId)
            block()
        },
    )
    return true
}

/** 绑定插件 Service（池代理）。 */
fun Context.bindPluginService(
    serviceClass: Class<*>,
    instanceId: String,
    connection: ServiceConnection,
    flags: Int,
): Boolean {
    val serviceClassName = serviceClass.name
    val (fullId, proxyClass) = ServiceProxyPool.acquire(serviceClassName, instanceId)
        ?: return false
    return bindService(
        Intent(this, proxyClass).apply {
            putExtra(ProxyKeys.SERVICE_CLASS, serviceClassName)
            putExtra(ProxyKeys.SERVICE_INSTANCE_ID, fullId)
        },
        connection,
        flags,
    )
}

/** 停止插件 Service（池代理）。 */
fun Context.stopPluginService(serviceClass: Class<*>, instanceId: String): Boolean {
    val fullId = "${serviceClass.name}:$instanceId"
    val proxyClass = ServiceProxyPool.proxyClassOf(fullId) ?: return false
    return stopService(
        Intent(this, proxyClass).apply {
            putExtra(ProxyKeys.SERVICE_CLASS, serviceClass.name)
            putExtra(ProxyKeys.SERVICE_INSTANCE_ID, fullId)
        },
    )
}

/**
 * 把插件 authority 的 URI 改写成宿主代理 URI：
 * `content://插件authority/path` → `content://宿主authority/urlEncoded(插件authority)/path`
 */
fun Context.pluginProxyUri(uri: Uri): Uri {
    val pluginAuthority = uri.authority ?: return uri
    val hostAuthority = "$packageName.plugin.proxy"
    return Uri.Builder()
        .scheme("content")
        .authority(hostAuthority)
        .appendPath(URLEncoder.encode(pluginAuthority, "UTF-8"))
        .apply { uri.pathSegments.forEach { appendPath(it) } }
        .apply { uri.encodedQuery?.let { encodedQuery(it) } }
        .build()
}

fun Context.queryPlugin(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
): Cursor? = contentResolver.query(pluginProxyUri(uri), projection, selection, selectionArgs, sortOrder)

fun Context.insertPlugin(uri: Uri, values: ContentValues?): Uri? =
    contentResolver.insert(pluginProxyUri(uri), values)

fun Context.updatePlugin(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
): Int = contentResolver.update(pluginProxyUri(uri), values, selection, selectionArgs)

fun Context.deletePlugin(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<out String>?,
): Int = contentResolver.delete(pluginProxyUri(uri), selection, selectionArgs)

fun Context.registerPluginObserver(
    uri: Uri,
    notifyForDescendants: Boolean,
    observer: ContentObserver,
) = contentResolver.registerContentObserver(pluginProxyUri(uri), notifyForDescendants, observer)

fun Context.unregisterPluginObserver(observer: ContentObserver) =
    contentResolver.unregisterContentObserver(observer)
