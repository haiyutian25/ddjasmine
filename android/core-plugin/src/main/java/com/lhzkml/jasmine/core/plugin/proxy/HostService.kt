package com.lhzkml.jasmine.core.plugin.proxy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.component.PluginService
import com.lhzkml.jasmine.core.plugin.rust.FfiLocateOutcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Proxy-service pool. The host registers a fixed set of manifest-declared
 * proxies ([HostService] subclasses); [acquire] hands one out per plugin
 * service instance and [release] returns it. Instance ids are
 * `"className:taskN"` and recovery matches with the colon included — never
 * a bare class-name prefix.
 */
object ServiceProxyPool {
    private val available = ConcurrentLinkedQueue<Class<out HostService>>()
    private val active = ConcurrentHashMap<String, Class<out HostService>>()

    /** Replaces the pool (host setup calls once with its manifest-registered proxies). */
    fun configure(proxies: List<Class<out HostService>>) {
        available.clear()
        active.clear()
        available.addAll(proxies)
    }

    val capacity: Int get() = available.size + active.size

    /**
     * Resumes the running instance of `serviceClassName:taskId` if one is
     * active (exact id match), else takes a free proxy. Null when the pool
     * is exhausted — no queuing.
     */
    fun acquire(serviceClassName: String, taskId: String): Pair<String, Class<out HostService>>? {
        val instanceId = "$serviceClassName:$taskId"
        active[instanceId]?.let { return instanceId to it }
        val proxy = available.poll() ?: return null
        active[instanceId] = proxy
        PluginHost.coreHandle.registerInstance(instanceId, ownerPluginOf(serviceClassName))
        return instanceId to proxy
    }

    /** Releases the proxy behind an instance id. */
    fun release(instanceId: String) {
        val proxy = active.remove(instanceId) ?: return
        PluginHost.coreHandle.unregisterInstance(instanceId)
        available.offer(proxy)
    }

    /** Exact-match running instances of one service class (colon included). */
    fun runningInstancesOf(serviceClassName: String): List<String> =
        active.keys.filter { it.startsWith("$serviceClassName:") }.sorted()

    /** The proxy class behind an exact instance id, or null when not running. */
    fun proxyClassOf(instanceId: String): Class<out HostService>? = active[instanceId]

    private fun ownerPluginOf(serviceClassName: String): String =
        when (val outcome = PluginHost.coreHandle.locateClass(serviceClassName, null)) {
            is FfiLocateOutcome.Plugin -> outcome.pluginId
            FfiLocateOutcome.HostFallback -> ""
        }
}

/** Starts a plugin service through a pooled proxy. */
fun Context.startPluginService(serviceClassName: String, taskId: String): Boolean {
    val (instanceId, proxyClass) = ServiceProxyPool.acquire(serviceClassName, taskId)
        ?: return false
    startService(
        Intent(this, proxyClass).apply {
            putExtra(ProxyKeys.SERVICE_CLASS, serviceClassName)
            putExtra(ProxyKeys.SERVICE_INSTANCE_ID, instanceId)
        },
    )
    return true
}

/**
 * Pooled proxy service base. The host manifest declares a fixed number of
 * concrete subclasses (capacity planning lives there).
 */
open class HostService : Service() {

    protected var pluginService: PluginService? = null
        private set

    private var instanceId: String? = null

    private fun initPluginService(intent: Intent?) {
        if (pluginService != null) return
        synchronized(this) {
            if (pluginService != null) return
            val className = intent?.getStringExtra(ProxyKeys.SERVICE_CLASS)
            try {
                val pluginId = when (
                    val outcome = PluginHost.coreHandle.locateClass(className.orEmpty(), null)
                ) {
                    is FfiLocateOutcome.Plugin -> outcome.pluginId
                    FfiLocateOutcome.HostFallback ->
                        throw IllegalStateException("没有已加载插件能提供 Service: $className")
                }
                val instance = PluginHost.instantiateComponent(pluginId, className.orEmpty())
                pluginService = (instance as? PluginService)
                    ?: throw IllegalStateException("$className 未实现 PluginService")
                instanceId = intent?.getStringExtra(ProxyKeys.SERVICE_INSTANCE_ID)
                pluginService?.attach(this)
                pluginService?.onCreate()
            } catch (e: Throwable) {
                PluginHost.loadFailureCallback?.onFailure(className.orEmpty(), "service", e)
                pluginService = null
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initPluginService(intent)
        return pluginService?.onStartCommand(intent, flags, startId)
            ?: super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        initPluginService(intent)
        return pluginService?.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean =
        pluginService?.onUnbind(intent) ?: super.onUnbind(intent)

    override fun onRebind(intent: Intent?) {
        pluginService?.onRebind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        pluginService?.onDestroy()
        pluginService = null
        instanceId?.let { ServiceProxyPool.release(it) }
        instanceId = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pluginService?.onConfigurationChanged(newConfig)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        pluginService?.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        pluginService?.onTrimMemory(level)
    }
}
