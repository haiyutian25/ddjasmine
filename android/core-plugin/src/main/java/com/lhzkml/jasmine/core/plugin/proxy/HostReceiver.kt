package com.lhzkml.jasmine.core.plugin.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.component.PluginReceiver
import com.lhzkml.jasmine.core.plugin.rust.FfiIntentQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 中心化静态广播代理。manifest 预声明系统 action 集；分发匹配在 Rust 核心，
 * 宿主与代理不会对「谁能看到哪个广播」产生分歧。插件自定义 action 由
 * [StaticReceiverDispatcher] 在运行期动态注册后进入同一分发链路。
 */
open class HostReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        intent.action ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                dispatchPluginReceivers(context, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** 把一条广播分发给所有匹配的插件静态接收器（Rust 匹配 + 实例化 + 回调）。 */
suspend fun dispatchPluginReceivers(context: Context, intent: Intent) {
    if (!PluginHost.isInitialized) return
    val matches = PluginHost.coreHandle.matchReceivers(
        FfiIntentQuery(
            action = intent.action,
            categories = intent.categories?.toList() ?: emptyList(),
            scheme = intent.data?.scheme,
            isInternal = intent.`package` == context.packageName,
        ),
    )
    for (match in matches) {
        try {
            val receiver = PluginHost.instantiateComponent(
                match.pluginId,
                match.receiver.className,
            ) as? PluginReceiver ?: continue
            receiver.onReceive(context, intent)
        } catch (e: Throwable) {
            PluginHost.loadFailureCallback?.onFailure(match.pluginId, "receiver", e)
        }
    }
}
