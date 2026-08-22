package com.lhzkml.jasmine.core.kernel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Disposer handle returned by every registration effect. Double-dispose is a no-op. */
fun interface DisposableHandle {
    fun dispose()
}

/** Thrown when registering effects on a disposed fiber (upstream `INACTIVE_EFFECT`). */
class InactiveFiberException(message: String) : IllegalStateException(message)

/** Lifecycle of one plugin mount. */
enum class FiberState { PENDING, ACTIVE, FAILED, DISPOSED }

/**
 * The unit of plugin lifetime: a [CoroutineScope] with SupervisorJob
 * parenting (parent disposal cascades into children — the same property
 * upstream gets by registering a child fiber's dispose as a parent effect)
 * plus a reverse-order disposer stack.
 *
 * Effect contract ported from upstream `fiber.ts`:
 * - `effect` runs its setup immediately and returns a handle; the collected
 *   disposer runs on fiber disposal or handle disposal, whichever comes first
 * - disposers execute in **reverse registration order**
 * - one disposer failing never breaks the others (exception isolation,
 *   unlike `emit` where a throw truncates the remaining listeners)
 * - disposing twice is a no-op; registering after dispose throws
 *   [InactiveFiberException]
 *
 * All fiber state is confined to the kernel's single-thread dispatcher.
 */
class Fiber internal constructor(
    internal val parent: Fiber?,
    private val kernel: Kernel,
    internal val label: String,
) {
    val scope: CoroutineScope =
        CoroutineScope(SupervisorJob(parent?.scope?.coroutineContext?.job) + kernel.confine)

    private val disposers = ArrayDeque<suspend () -> Unit>()
    private val nested = ArrayDeque<MutableList<suspend () -> Unit>>()
    private val disposed = CompletableDeferred<Unit>()
    var state: FiberState = FiberState.PENDING
        internal set
    internal var failure: Throwable? = null

    /** Monotonic mount id; null once disposed. */
    var uid: Int? = kernel.nextUid()
        private set

    init {
        // A child fiber's dispose is one effect of its parent, so disposal
        // cascades parent→child and the child occupies a well-defined slot
        // in the parent's disposer order (upstream fiber.ts:265-297).
        val parentFiber = parent
        if (parentFiber != null) {
            kernel.confined {
                if (parentFiber.state != FiberState.DISPOSED) {
                    parentFiber.disposers.add { disposeInternal() }
                }
            }
        }
    }

    /**
     * Runs [setup] immediately and tracks its disposer. Disposers registered
     * while another effect's setup is running belong to that outer effect.
     */
    fun effect(setup: () -> (suspend () -> Unit)?): DisposableHandle = kernel.confined {
        if (state == FiberState.DISPOSED) {
            throw InactiveFiberException("effect on disposed fiber '$label'")
        }
        val owned = mutableListOf<suspend () -> Unit>()
        nested.addLast(owned)
        val disposer = try {
            setup()
        } catch (t: Throwable) {
            nested.removeLast()
            scope.launch { runIsolated(owned) }
            throw t
        }
        nested.removeLast()
        disposer?.let(owned::add)
        if (nested.isNotEmpty()) nested.last().addAll(owned) else disposers.addAll(owned)
        var active = true
        object : DisposableHandle {
            override fun dispose() {
                if (!active) return
                active = false
                kernel.confined {
                    disposers.removeAll(owned::contains)
                    scope.launch { runIsolated(owned) }
                }
            }
        }
    }

    /** Awaits full disposal; rethrows the plugin's startup failure, if any. */
    suspend fun await(): Fiber {
        disposed.await()
        failure?.let { throw it }
        return this
    }

    /** Disposes this fiber: reverse disposers with isolation, then cancel. Idempotent. */
    fun dispose(): CompletableDeferred<Unit> {
        kernel.confined { disposeInternal() }
        return disposed
    }

    private fun disposeInternal() {
        if (state == FiberState.DISPOSED) return
        state = FiberState.DISPOSED
        val toRun = ArrayList(disposers)
        disposers.clear()
        uid = null
        scope.launch {
            runIsolated(toRun)
            scope.cancel()
            disposed.complete(Unit)
        }
    }

    private suspend fun runIsolated(list: List<suspend () -> Unit>) {
        for (disposer in list.asReversed()) {
            try {
                disposer()
            } catch (t: Throwable) {
                kernel.onError(t)
            }
        }
    }
}
