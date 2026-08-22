package com.lhzkml.jasmine.core.kernel

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/**
 * The kernel root: owns the confine dispatcher (every registry, fiber, and
 * listener-registration mutation happens there — the thread discipline
 * ANDROID-PLAN §7 pins on day one), the root fiber, and the five core
 * services. The kernel itself is fixed core: the machine that patches
 * plugins can never be patched (§2.1).
 *
 * @param confine single-thread dispatcher for all kernel state; defaults to
 *   a private single-thread executor
 * @param onError receives every isolated disposer failure
 * @param onLeakedNext receives the pending-`next` count after a waterfall
 *   whose chain completed without delegating (risk-table telemetry)
 */
class Kernel(
    confine: CoroutineDispatcher = ConfineDispatcher(),
    internal val onError: (Throwable) -> Unit = { /* logged by host */ },
    internal val onLeakedNext: (Int) -> Unit = { /* telemetry by host */ },
) {
    internal val confine = confine
    internal val leakedNexts = AtomicInteger(0)
    private val uidCounter = AtomicInteger(1)

    /** Root fiber: uid 0, ACTIVE, disposal shuts the whole kernel down. */
    val root = Fiber(parent = null, kernel = this, label = "kernel").apply {
        state = FiberState.ACTIVE
    }

    val scopes = ScopeRegistry()
    val events = EventBus(this)
    val registry = Registry(this)
    val host = PluginHost(this)

    internal fun nextUid(): Int = uidCounter.getAndIncrement()

    /**
     * Runs [block] on the confine dispatcher synchronously (blocking).
     * Re-entrant: when already running ON the confine worker thread the
     * block runs inline — a second `runBlocking` onto the same single
     * thread would deadlock the scheduler against itself.
     */
    internal fun <T> confined(block: () -> T): T {
        val known = (confine as? ConfineDispatcher)?.thread?.get()
        if (known != null && Thread.currentThread() === known) return block()
        return runBlocking(confine) { block() }
    }

    /** Reads kernel state from the confine dispatcher. */
    internal fun <T> confinedGet(block: () -> T): T = confined(block)

    /** Launches [block] on the confine dispatcher without waiting. */
    internal fun launchConfined(block: suspend () -> Unit) {
        CoroutineScope(root.scope.coroutineContext.job + confine).launch { block() }
    }

    /** Spawns a child fiber of the root fiber. */
    fun fiber(label: String): Fiber = Fiber(parent = root, kernel = this, label = label)

    /** Disposes the kernel: cascades root fiber, then drops the dispatcher. */
    fun dispose() {
        root.dispose()
    }
}

/**
 * Single-thread dispatcher that records its worker thread, so
 * [Kernel.confined] can run inline when already on it. The worker is a
 * daemon: kernels never outlive their host process.
 */
class ConfineDispatcher : CoroutineDispatcher() {
    private val inner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kernel").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    internal val thread = AtomicReference<Thread?>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        inner.dispatch(context) {
            thread.compareAndSet(null, Thread.currentThread())
            block.run()
        }
    }
}
