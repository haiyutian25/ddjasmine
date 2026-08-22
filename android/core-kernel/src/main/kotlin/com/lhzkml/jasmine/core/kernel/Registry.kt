package com.lhzkml.jasmine.core.kernel

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Typed service token; the Kotlin replacement of upstream's declaration-merged keys. */
class ServiceKey<T : Any>(val name: String) {
    override fun toString(): String = "ServiceKey($name)"
}

/** Thrown when two providers offer the same key in one scope. */
class DuplicateServiceException(val key: ServiceKey<*>) :
    IllegalStateException("service ${key.name} already provided")

/**
 * The plugin registry with an availability-driven activation state machine
 * (upstream registry.ts + fiber.ts `_checkImpl`): mounting order has no
 * semantics — a plugin suspends until every declared dependency is
 * available, activates when the set completes, and re-suspends (fiber
 * disposed, mount re-armed) when any dependency goes away.
 */
class Registry(private val kernel: Kernel) {

    private val services = ConcurrentHashMap<ServiceKey<*>, Any>()
    private val availabilityFlows = ConcurrentHashMap<ServiceKey<*>, MutableStateFlow<Any?>>()

    /** Live availability of [key]; `null` until provided and after withdrawal. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> availability(key: ServiceKey<T>): StateFlow<T?> =
        availabilityFlows.getOrPut(key) { MutableStateFlow(null) } as StateFlow<T?>

    /**
     * Provides a service implementation. The returned handle withdraws it
     * and awakens every dependent for re-suspension. Registering through a
     * [Context] ties the handle to that fiber, so plugin disposal removes
     * its services automatically (the HMR-equivalent acceptance).
     */
    fun <T : Any> provide(key: ServiceKey<T>, value: T): DisposableHandle {
        val previous = services.putIfAbsent(key, value)
        if (previous != null) throw DuplicateServiceException(key)
        availability(key)
        kernel.confined { availabilityFlows[key]!!.value = value }
        return object : DisposableHandle {
            override fun dispose() {
                kernel.confined {
                    services.remove(key, value)
                    availabilityFlows[key]!!.value = null
                }
            }
        }
    }

    /** Suspends until [key] is available (upstream `inject` hanging read). */
    suspend fun <T : Any> await(key: ServiceKey<T>): T {
        @Suppress("UNCHECKED_CAST")
        return availability(key).mapNotNull { it as T? }.first { it != null }
    }

    /** Non-suspending strict read: only a current implementation wins. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: ServiceKey<T>): T? = services[key] as T?

    /**
     * Mounts [plugin]: a supervisor coroutine waits for the dependency set,
     * runs `apply` in a fresh child fiber, and on any dependency withdrawal
     * disposes that fiber and waits again. The plugin's startup failure
     * lands in the fiber (`await()` rethrows) instead of crashing the host.
     */
    fun mount(plugin: PluginSpec, onFiberState: (FiberState) -> Unit = {}): FiberRegistryHandle {
        val mountScope = kotlinx.coroutines.CoroutineScope(kernel.root.scope.coroutineContext.job + kernel.confine)
        val live = java.util.concurrent.atomic.AtomicReference<Fiber?>(null)
        val mountJob = mountScope.launch {
            try {
                while (currentCoroutineContext().isActive) {
                    plugin.dependencies.forEach { await(it) }
                    val fiber = Fiber(parent = kernel.root, kernel = kernel, label = plugin.name)
                    live.set(fiber)
                    val context = Context(kernel = kernel, fiber = fiber, scopeKey = null)
                    try {
                        plugin.apply(context)
                        kernel.confined { fiber.state = FiberState.ACTIVE }
                        onFiberState(FiberState.ACTIVE)
                    } catch (t: Throwable) {
                        kernel.confined {
                            fiber.failure = t
                            fiber.state = FiberState.FAILED
                        }
                        onFiberState(FiberState.FAILED)
                        fiber.dispose().await()
                        return@launch
                    }
                    // Re-suspend when any dependency disappears. A plugin with no
                    // dependencies never re-suspends: its mount lives until stopped.
                    val flows = plugin.dependencies.map { availability(it) }
                    if (flows.isEmpty()) awaitCancellation() else combine(flows) { values ->
                        values.any { it == null }
                    }.first { it }
                    fiber.dispose().await()
                    live.set(null)
                }
            } finally {
                // Stopping a mount unmounts its live plugin too, not just the watcher.
                live.getAndSet(null)?.dispose()
            }
        }
        return FiberRegistryHandle(mountJob, live)
    }
}

/** Stops one plugin mount (the availability watcher and its live fiber). */
class FiberRegistryHandle internal constructor(
    private val job: kotlinx.coroutines.Job,
    private val live: java.util.concurrent.atomic.AtomicReference<Fiber?>,
) : DisposableHandle {
    override fun dispose() {
        job.cancel()
        live.getAndSet(null)?.dispose()
    }
}

/**
 * The manual plugin form for M0 (KSP compile-time indexing lands with the
 * first real plugins): declare dependencies as service keys; `apply` runs
 * exactly once per activation with a fresh fiber context.
 */
class PluginSpec(
    val name: String,
    val dependencies: List<ServiceKey<*>> = emptyList(),
    val apply: suspend (Context) -> Unit,
)

/**
 * M0 PluginHost: a manual registry of plugin specs with ordered mount.
 * The KSP-generated `PluginIndex` replaces [register] later; `mountAll`
 * stays the single entry point either way.
 */
class PluginHost(private val kernel: Kernel) {

    private val specs = LinkedHashMap<String, PluginSpec>()

    fun register(spec: PluginSpec) {
        require(specs.putIfAbsent(spec.name, spec) == null) {
            "plugin '${spec.name}' already registered"
        }
    }

    fun registered(): List<PluginSpec> = specs.values.toList()

    fun mountAll(): List<FiberRegistryHandle> = specs.values.map { kernel.registry.mount(it) }
}
