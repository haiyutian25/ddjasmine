package com.lhzkml.jasmine.core.plugin.proxy

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.component.PluginService
import com.lhzkml.jasmine.core.plugin.process.PluginProcessBridge
import com.lhzkml.jasmine.core.plugin.process.ProcessIdentity
import com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager
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
    private val hostAvailable = ConcurrentLinkedQueue<Class<out HostService>>()

    /** 宿主进程内的服务占用表（仅宿主进程服务；隔离服务不在此记账）。 */
    private val active = ConcurrentHashMap<String, Class<out HostService>>()

    private var application: Application? = null

    /** Replaces the in-process pool (host setup calls once). */
    fun configure(app: Application, proxies: List<Class<out HostService>>) {
        application = app
        hostAvailable.clear()
        active.clear()
        hostAvailable.addAll(proxies)
    }

    /**
     * 隔离服务池的种子：只在隔离进程生效——把「当前槽」的代理类种进本进程
     * bridge Server 的池。宿主进程调用则无操作。池权威在隔离进程，宿主经
     * bridge 同步分配/归还，彻底解决 acquire/release 跨进程导致的槽位永久
     * 泄漏，并让槽 2-4 的插件服务运行在插件自己的进程里。
     */
    fun configureIsolated(pools: Map<Int, List<Class<out HostService>>>) {
        val app = application ?: return
        val slot = ProcessIdentity.currentIsolatedSlot(app) ?: return
        pools[slot]?.let { PluginProcessBridge.seedServicePool(it.map { c -> c.name }) }
    }

    val capacity: Int get() = hostAvailable.size + active.size

    /**
     * Resumes the running instance of `serviceClassName:taskId` if one is
     * active (exact id match), else takes a free proxy. Host plugins use the
     * in-process [hostAvailable] pool; isolated plugins allocate through the
     * owning slot's bridge (authority lives in the isolated process).
     * Null when the pool is exhausted — no queuing.
     */
    @Synchronized
    fun acquire(serviceClassName: String, taskId: String): Pair<String, Class<out HostService>>? {
        val instanceId = "$serviceClassName:$taskId"
        active[instanceId]?.let { return instanceId to it }
        val pluginId = ownerPluginOf(serviceClassName)
        val slot = if (pluginId.isNotEmpty()) ProcessIsolationManager.isolatedSlot(pluginId) else null
        if (slot != null) {
            val app = application
            if (app != null && ProcessIdentity.isIsolatedProcess(app)) {
                // 已在隔离进程内（插件自己的代码启动自己的服务）：本地池直接分配。
                val proxyName = PluginProcessBridge.servicePool.acquire(instanceId) ?: return null
                val proxy = isolatedProxyClassOf(proxyName) ?: run {
                    PluginProcessBridge.servicePool.release(instanceId)
                    return null
                }
                return instanceId to proxy
            }
            // 宿主进程：经 bridge 到隔离进程分配。
            val bridge = ProcessIsolationManager.bridgeFor(slot) ?: return null
            val proxyName = bridge.acquireServiceSlot(instanceId) ?: return null
            val proxy = isolatedProxyClassOf(proxyName) ?: run {
                bridge.releaseServiceSlot(instanceId)
                return null
            }
            return instanceId to proxy
        }
        val proxy = hostAvailable.poll() ?: return null
        active[instanceId] = proxy
        PluginHost.coreHandle.registerInstance(instanceId, pluginId)
        return instanceId to proxy
    }

    /** Releases the proxy behind an instance id. */
    fun release(instanceId: String) {
        val proxy = active.remove(instanceId)
        if (proxy != null) {
            PluginHost.coreHandle.unregisterInstance(instanceId)
            hostAvailable.offer(proxy)
            return
        }
        // 隔离服务：宿主侧无 active 记录。隔离进程 onDestroy 本地归还；
        // 宿主主动回滚（startService 失败）经 bridge 归还。
        val app = application ?: return
        if (ProcessIdentity.isIsolatedProcess(app)) {
            PluginProcessBridge.servicePool.release(instanceId)
        } else {
            ProcessIsolationManager.bridges().forEach { it.releaseServiceSlot(instanceId) }
        }
    }

    /** Exact-match running instances of one service class (colon included). */
    fun runningInstancesOf(serviceClassName: String): List<String> =
        active.keys.filter { it.startsWith("$serviceClassName:") }.sorted()

    /** The proxy class behind an exact instance id, or null when not running. */
    fun proxyClassOf(instanceId: String): Class<out HostService>? {
        active[instanceId]?.let { return it }
        val app = application ?: return null
        if (ProcessIdentity.isIsolatedProcess(app)) {
            val name = PluginProcessBridge.servicePool.proxyClass(instanceId) ?: return null
            return isolatedProxyClassOf(name)
        }
        for (bridge in ProcessIsolationManager.bridges()) {
            val name = bridge.serviceProxyClass(instanceId)
            if (name != null) return isolatedProxyClassOf(name)
        }
        return null
    }

    private fun isolatedProxyClassOf(name: String): Class<out HostService>? =
        isolatedServicePool.values.flatten().firstOrNull { it.name == name }

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
    return try {
        startService(
            Intent(this, proxyClass).apply {
                putExtra(ProxyKeys.SERVICE_CLASS, serviceClassName)
                putExtra(ProxyKeys.SERVICE_INSTANCE_ID, instanceId)
            },
        )
        true
    } catch (e: Throwable) {
        // startService 抛异常（如 Android 12+ 后台 FGS 限制）必须回滚槽位，
        // 否则每次失败永久烧掉一个代理槽。
        ServiceProxyPool.release(instanceId)
        throw e
    }
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
            if (className.isNullOrBlank()) {
                // START_STICKY 粘性重启带 null intent：类名未随 intent 重投，
                // 无法恢复插件服务。静默停止，避免产生伪造原因的加载失败上报。
                stopSelf()
                return
            }
            // 槽位在调用方 acquire 时已占：先记下 instanceId，实例化失败
            // 也必须归还，否则池槽位永久泄漏（此前在实例化成功之后才赋值，
            // 失败路径拿不到 id，onDestroy 跳过 release）。
            instanceId = intent?.getStringExtra(ProxyKeys.SERVICE_INSTANCE_ID)
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
                pluginService?.attach(this)
                pluginService?.onCreate()
            } catch (e: Throwable) {
                PluginHost.loadFailureCallback?.onFailure(className.orEmpty(), "service", e)
                pluginService = null
                instanceId?.let { ServiceProxyPool.release(it) }
                instanceId = null
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
