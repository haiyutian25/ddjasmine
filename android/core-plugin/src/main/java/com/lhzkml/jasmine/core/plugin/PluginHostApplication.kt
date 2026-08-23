package com.lhzkml.jasmine.core.plugin

import android.app.Application
import com.lhzkml.jasmine.core.plugin.crash.CrashHook
import com.lhzkml.jasmine.core.plugin.crash.PluginCrashCallback
import com.lhzkml.jasmine.core.plugin.process.ProcessIdentity
import com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager
import com.lhzkml.jasmine.core.plugin.proxy.ServiceProxyPool
import com.lhzkml.jasmine.core.plugin.proxy.defaultServicePool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-line host integration. Subclass this (or mirror its onCreate order):
 * crash hook first — so plugin failures during the framework's own load are
 * attributable — then the runtime initializes and loads enabled plugins.
 *
 * Proxy components (host activity, receiver, provider, a ten-slot service
 * pool) ship in this library's manifest and merge into the host app
 * automatically; the service pool is configured here.
 */
open class PluginHostApplication : Application() {

    /** Signature trust policy for installs; override to relax for development. */
    protected open fun pluginPolicy(): SignaturePolicy = SignaturePolicy.Strict

    /** Crash policy hook; default lets the process die after reporting. */
    protected open fun pluginCrashCallback(): PluginCrashCallback? = null

    /** Extra setup after the runtime initialized (e.g. bundled installs). */
    protected open fun onPluginFrameworkReady(): suspend () -> Unit = {}

    override fun onCreate() {
        super.onCreate()
        val crashCallback = pluginCrashCallback()
        if (crashCallback != null) {
            CrashHook.install(crashCallback)
        }
        ServiceProxyPool.configure(defaultServicePool)
        ProcessIsolationManager.attach(this)
        CoroutineScope(Dispatchers.IO).launch {
            if (ProcessIdentity.isIsolatedProcess(this@PluginHostApplication)) {
                // Isolated process: init the runtime but load nothing up
                // front — IsolatedPluginProcessService drives the load.
                PluginHost.initialize(this@PluginHostApplication, pluginPolicy()) { false }
            } else {
                // Host process: init and auto-load every enabled plugin
                // except those placed in the isolated process.
                PluginHost.initialize(this@PluginHostApplication, pluginPolicy()) { record ->
                    !ProcessIsolationManager.isIsolated(record.pluginId)
                }
                onPluginFrameworkReady()()
            }
        }
    }
}
