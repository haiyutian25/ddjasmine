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

    // action → 引用计数：多个插件可声明同名 action，卸载其中一个不能把
    // 其他插件仍在用的 action 摘掉（此前是普通 Set，整体移除会误伤）。
    private val actionRefs = ConcurrentHashMap<String, Int>()
    private var receiver: BroadcastReceiver? = null

    /** 插件加载时登记其静态接收器声明的全部 action。 */
    fun registerActions(app: Application, pluginActions: Set<String>) {
        if (pluginActions.isEmpty()) return
        var changed = false
        pluginActions.forEach { action ->
            if (actionRefs.merge(action, 1, Int::plus) == 1) changed = true
        }
        if (changed) reRegister(app)
    }

    /** 插件卸载时递减其 action 引用，归零才真正移除。 */
    fun unregisterActions(app: Application, pluginActions: Set<String>) {
        if (pluginActions.isEmpty()) return
        var changed = false
        pluginActions.forEach { action ->
            val remaining = actionRefs.merge(action, -1, Int::plus) ?: 0
            if (remaining <= 0) {
                actionRefs.remove(action)
                changed = true
            }
        }
        if (changed) reRegister(app)
    }

    private fun reRegister(app: Application) {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        val actions = actionRefs.keys
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
        // RECEIVER_NOT_EXPORTED：插件自定义 action 只接受同应用广播。此前
        // RECEIVER_EXPORTED + HostReceiver 用攻击者可控的 intent.package 判
        // "内部"，第三方可 setPackage(宿主) 伪造内部广播触达 exported=false
        // 的插件接收器。与 manifest HostReceiver(exported=false) 的安全默认一致。
        ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }
}
