package com.lhzkml.jasmine.core.plugin.proxy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件静态广播自定义 action 的运行期分发器：manifest 里无法动态扩展
 * [HostReceiver] 的 intent-filter，这里为「已加载插件声明但不在系统 action
 * 集内」的自定义 action 动态注册一个接收器，复用同一分发链路。
 */
object StaticReceiverDispatcher {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val actions = ConcurrentHashMap.newKeySet<String>()
    private var receiver: BroadcastReceiver? = null

    /** 插件加载时登记其静态接收器声明的全部 action。 */
    fun registerActions(app: Application, pluginActions: Set<String>) {
        if (pluginActions.isEmpty()) return
        val changed = actions.addAll(pluginActions)
        if (changed) reRegister(app)
    }

    /** 插件卸载时移除其 action。 */
    fun unregisterActions(app: Application, pluginActions: Set<String>) {
        if (pluginActions.isEmpty()) return
        val changed = actions.removeAll(pluginActions)
        if (changed) reRegister(app)
    }

    private fun reRegister(app: Application) {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        if (actions.isEmpty()) {
            receiver = null
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pending = goAsync()
                scope.launch {
                    try {
                        dispatchPluginReceivers(context, intent)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }
}
