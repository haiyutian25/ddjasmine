package com.lhzkml.jasmine.core.kernel

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The facade a plugin receives: events, effects, services, and mounting —
 * every registration returns a disposer and is tied to the fiber, so plugin
 * disposal reclaims everything (upstream "registrations are effects").
 *
 * @param scopeKey the dispatch scope; `null` contexts dispatch globally
 *   (untagged listeners see everything, tagged listeners see nothing)
 */
class Context(
    private val kernel: Kernel,
    val fiber: Fiber,
    val scopeKey: ScopeKey?,
) {

    /** Registers a listener; disposal removes exactly this registration. */
    fun <P, R> on(
        key: EventKey<P, R>,
        prepend: Boolean = false,
        global: Boolean = false,
        listener: suspend (args: Array<out Any?>) -> Any?,
    ): DisposableHandle {
        val registration = kernel.events.register(key, scopeKey, global, prepend, listener)
        val unlistener = fiber.effect { registration::dispose }
        return DisposableHandle {
            registration.dispose()
            unlistener.dispose()
        }
    }

    /** Convenience listener with no bail value. */
    fun <P> onEvent(
        key: EventKey<P, Unit>,
        prepend: Boolean = false,
        global: Boolean = false,
        listener: suspend (P) -> Unit,
    ): DisposableHandle = on(key, prepend, global) { args ->
        @Suppress("UNCHECKED_CAST")
        listener(args.firstOrNull() as P)
        null
    }

    /** Runs [setup] immediately; its disposer joins the fiber's reverse stack. */
    fun effect(setup: () -> (suspend () -> Unit)?): DisposableHandle = fiber.effect(setup)

    /** Provides a service owned by this fiber: disposal withdraws it. */
    fun <T : Any> provide(key: ServiceKey<T>, value: T): DisposableHandle {
        val registration = kernel.registry.provide(key, value)
        val owner = fiber.effect { registration::dispose }
        return DisposableHandle {
            registration.dispose()
            owner.dispose()
        }
    }

    /** Suspends until the service is available. */
    suspend fun <T : Any> await(key: ServiceKey<T>): T = kernel.registry.await(key)

    /** Fire-and-forget dispatch admitted through this context's scope. */
    fun <P> emit(key: EventKey<P, *>, payload: P) = kernel.events.emit(key, scopeKey, payload)

    suspend fun <P> parallel(key: EventKey<P, *>, payload: P) =
        kernel.events.parallel(key, scopeKey, payload)

    suspend fun <P, R : Any> serial(key: EventKey<P, R>, payload: P): R? =
        kernel.events.serial(key, scopeKey, payload)

    fun <P, R : Any> bail(key: EventKey<P, R>, payload: P): R? =
        kernel.events.bail(key, scopeKey, payload)

    suspend fun <P, R> waterfall(key: EventKey<P, R>, payload: P, inner: suspend () -> R): R =
        kernel.events.waterfall(key, scopeKey, payload, inner)
}
