package com.lhzkml.jasmine.core.plugin.process

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lhzkml.jasmine.core.plugin.PluginHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The isolated-process host. Declared with `android:process=":plugin_isolated"`
 * in the library manifest, this service runs a single plugin in a private
 * process so heavy-native plugins (MNN inference, Proot rootfs) cannot drag
 * the host down on a crash and can be memory-reclaimed by killing the
 * process.
 *
 * The isolated process initializes its own [PluginHost] with no auto-load
 * (see [com.lhzkml.jasmine.core.plugin.PluginHostApplication]); this service
 * then awaits that init and loads the requested plugin here, so its classes,
 * native libs and services live in this process, not the host's.
 *
 * On bind it exposes the process-local [PluginProcessBridge.server], through
 * which the plugin publishes cross-process service tokens.
 */
open class IsolatedPluginProcessService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder = PluginProcessBridge.server()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID) ?: return START_NOT_STICKY
        scope.launch {
            PluginHost.awaitReady()
            // Reconcile the in-memory ledger with disk before loading: the host
            // may have installed/updated plugins since this process opened it.
            runCatching { PluginHost.refreshLedger() }
            runCatching { PluginHost.launchPlugin(pluginId) }
                .onFailure { PluginHost.loadFailureCallback?.onFailure(pluginId, "isolated-load", it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "jasmine.plugin.process.pluginId"
    }
}

/** 隔离进程槽 2（`:plugin_isolated_2`），供多进程隔离分配。 */
class IsolatedPluginProcessService2 : IsolatedPluginProcessService()

/** 隔离进程槽 3（`:plugin_isolated_3`），供多进程隔离分配。 */
class IsolatedPluginProcessService3 : IsolatedPluginProcessService()

/** 隔离进程槽 4（`:plugin_isolated_4`），供多进程隔离分配。 */
class IsolatedPluginProcessService4 : IsolatedPluginProcessService()
