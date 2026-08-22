package com.lhzkml.jasmine.core.kernel

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Typed event key; the Kotlin counterpart of a merged `SessionEventMap` entry. */
class EventKey<P, R>(val name: String) {
    override fun toString(): String = "EventKey($name)"
}

internal class Listener(
    val tag: ScopeKey?,
    val global: Boolean,
    val invoke: suspend (args: Array<out Any?>) -> Any?,
)

/** Aggregates every failure of a parallel dispatch (upstream `AggregateError`). */
class MultipleFailuresException(val failures: List<Throwable>) :
    Exception("parallel dispatch failed: ${failures.size} listener(s) threw", failures.first())

/**
 * The five dispatch modes of the Cordis event bus, ported to coroutines.
 *
 * Semantics pinned from upstream `vendor/cordis/src/events.ts`:
 * - **bail value**: only a non-`null` return bails; `false` does NOT
 *   short-circuit (a Kotlin `Boolean` listener must return `null` to pass)
 * - `emit` and `bail` are synchronous; a listener exception truncates the
 *   remaining listeners and propagates to the caller
 * - `parallel` starts every listener concurrently and aggregates ALL
 *   failures into one [MultipleFailuresException]
 * - `serial` awaits listeners in order and returns the first bail value
 * - `waterfall` composes listeners registration-order-outermost; the last
 *   argument is the `next` continuation — NOT calling it vetoes the rest of
 *   the chain and the inner behavior
 *
 * Scope admission (upstream filter rule): an untagged listener sees every
 * dispatch; a tagged listener sees dispatches from its own scope or any
 * descendant; events travel UP the scope chain, never down; `global`
 * bypasses the filter.
 */
class EventBus(private val kernel: Kernel) {

    private val listeners = LinkedHashMap<String, MutableList<Listener>>()

    internal fun register(
        key: EventKey<*, *>,
        tag: ScopeKey?,
        global: Boolean,
        prepend: Boolean,
        invoke: suspend (Array<out Any?>) -> Any?,
    ): DisposableHandle {
        val entry = Listener(tag, global, invoke)
        kernel.confined {
            listeners.getOrPut(key.name) { mutableListOf() }.apply {
                if (prepend) add(0, entry) else add(entry)
            }
        }
        return object : DisposableHandle {
            override fun dispose() {
                kernel.confined { listeners[key.name]?.remove(entry) }
            }
        }
    }

    private fun visible(entry: Listener, from: ScopeKey?): Boolean =
        entry.global || entry.tag == null || kernel.scopes.isInAncestry(entry.tag, from)

    private fun of(key: EventKey<*, *>, from: ScopeKey?): List<Listener> =
        kernel.confinedGet { listeners[key.name].orEmpty().filter { visible(it, from) } }

    /** Fire-and-forget: synchronous, registration order, exceptions truncate. */
    fun emit(key: EventKey<*, *>, from: ScopeKey? = null, vararg args: Any?) {
        runBlocking {
            withContext(kernel.confine) {
                for (entry in of(key, from)) entry.invoke(args)
            }
        }
    }

    /** Concurrent dispatch; waits for all, aggregates every failure. */
    suspend fun parallel(key: EventKey<*, *>, from: ScopeKey? = null, vararg args: Any?) {
        val failures = coroutineScope {
            of(key, from)
                .map { entry -> async { runCatching { entry.invoke(args) } } }
                .awaitAll()
        }.mapNotNull { it.exceptionOrNull() }
        if (failures.isNotEmpty()) throw MultipleFailuresException(failures)
    }

    /**
     * Ordered dispatch; the first bail value returns. A bail value is
     * non-null and not `false` — upstream `isBailed` treats `false` exactly
     * like `null`, so a Boolean listener must return `true` to short-circuit.
     */
    suspend fun <R : Any> serial(key: EventKey<*, R>, from: ScopeKey? = null, vararg args: Any?): R? {
        for (entry in of(key, from)) {
            val result = entry.invoke(args)
            if (!isBailed(result)) continue
            @Suppress("UNCHECKED_CAST")
            return result as R
        }
        return null
    }

    /** Synchronous bail: first bail value wins (see [serial]), no waiting. */
    fun <R : Any> bail(key: EventKey<*, R>, from: ScopeKey? = null, vararg args: Any?): R? =
        runBlocking { serial(key, from, *args) }

    /**
     * Continuation-passing dispatch. The first-registered listener is the
     * outermost; each receives `[payload, next]` and must call `next` to
     * delegate — returning without calling it vetoes everything below.
     * Returns the outermost listener's value.
     */
    suspend fun <P, R> waterfall(
        key: EventKey<P, R>,
        from: ScopeKey? = null,
        payload: P,
        inner: suspend () -> R,
    ): R {
        val chain = of(key, from)
        fun build(index: Int): suspend () -> R {
            kernel.leakedNexts.incrementAndGet()
            return suspend {
                kernel.leakedNexts.decrementAndGet()
                if (index >= chain.size) inner()
                else {
                    val entry = chain[index]
                    val nextArgs = arrayOfNulls<Any?>(2)
                    nextArgs[0] = payload
                    nextArgs[1] = build(index + 1)
                    @Suppress("UNCHECKED_CAST")
                    entry.invoke(nextArgs) as R
                }
            }
        }
        return build(0)().also {
            val leaked = kernel.leakedNexts.get()
            if (leaked > 0) kernel.onLeakedNext(leaked)
        }
    }
}


/** A value that short-circuits serial/bail dispatch (upstream `isBailed`). */
internal fun isBailed(value: Any?): Boolean = value != null && value !== false
