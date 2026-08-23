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
 * Centralized static-broadcast proxy. Registered once in the host manifest
 * with the shared action set; dispatch matching itself happens in the Rust
 * core so the proxy and the host can never disagree about who sees what.
 */
open class HostReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        intent.action ?: return
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (!PluginHost.isInitialized) return@launch
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
                        PluginHost.loadFailureCallback?.onFailure(
                            match.pluginId, "receiver", e,
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
