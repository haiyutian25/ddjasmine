package com.lhzkml.jasmine.core.kernel

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusTest {

    private fun kernel(): Kernel = Kernel()

    @Test
    fun `waterfall veto - not calling next skips inner and rest of chain`() = runBlocking {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        val calls = mutableListOf<String>()
        val inner = { calls.add("inner"); "done" }
        ctx.onWaterfall(key = KEY) { payload, next ->
            calls.add("outer")
            // veto: never call next
            @Suppress("UNUSED_PARAMETER")
            payload
        }
        val result: String = ctx.waterfall(KEY, "p", inner)
        assertEquals("vetoed", result)
        assertEquals(listOf("outer"), calls)
    }

    @Test
    fun `waterfall delegation order - outermost first, deepest next reaches inner`() = runBlocking {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        val order = mutableListOf<String>()
        ctx.onWaterfall(KEY) { payload, next ->
            order.add("first")
            next(payload)
        }
        ctx.onWaterfall(KEY) { payload, next ->
            order.add("second")
            next(payload)
        }
        val result: String = ctx.waterfall(KEY, "p") { order.add("inner"); "done" }
        assertEquals("done", result)
        assertEquals(listOf("first", "second", "inner"), order)
    }

    @Test
    fun `waterfall exception propagates along the next call stack`() = runBlocking {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        ctx.onWaterfall(KEY) { payload, next -> next(payload) }
        ctx.onWaterfall(KEY) { payload, next ->
            @Suppress("UNUSED_PARAMETER")
            payload; next; throw IllegalStateException("listener boom")
        }
        val t = assertFailsWith<IllegalStateException> {
            ctx.waterfall(KEY, "p") { error("inner must not run") }
        }
        assertEquals("listener boom", t.message)
    }

    @Test
    fun `waterfall leaked next is reported`() = runBlocking {
        val leaks = mutableListOf<Int>()
        val k = Kernel(onLeakedNext = { leaks.add(it) })
        val ctx = Context(k, k.root, null)
        ctx.onWaterfall(KEY) { payload, next ->
            next(payload) // delegates fine
        }
        ctx.onWaterfall(KEY) { payload, next ->
            @Suppress("UNUSED_PARAMETER")
            payload
            // veto: never calls next
        }
        ctx.waterfall(KEY, "p") { "done" }
        assertTrue(leaks.isNotEmpty())
    }

    @Test
    fun `serial returns the first non-null result and false does not bail`() = runBlocking {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        val calls = mutableListOf<String>()
        ctx.on(BOOL_KEY) { calls.add("a"); false }
        ctx.on(BOOL_KEY) { calls.add("b"); true }
        val result = ctx.serial(BOOL_KEY, "p")
        assertEquals(true, result)
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun `serial stops at the first bail value`() = runBlocking {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        val calls = mutableListOf<String>()
        ctx.on(STRING_KEY) { calls.add("a"); null }
        ctx.on(STRING_KEY) { calls.add("b"); "winner" }
        ctx.on(STRING_KEY) { calls.add("c"); "never" }
        assertEquals("winner", ctx.serial(STRING_KEY, "p"))
        assertEquals(listOf("a", "b"), calls)
    }

    @Test
    fun `bail is synchronous short-circuit`() {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        ctx.on(STRING_KEY) { "instant" }
        assertEquals("instant", ctx.bail(STRING_KEY, "p"))
    }

    @Test
    fun `parallel aggregates every failure`() = runBlocking {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        val started = AtomicInteger(0)
        repeat(3) { i ->
            ctx.on(KEY) {
                started.incrementAndGet()
                if (i != 1) throw IllegalStateException("boom $i")
                null
            }
        }
        val t = assertFailsWith<MultipleFailuresException> { ctx.parallel(KEY, "p") }
        assertEquals(2, t.failures.size)
        assertEquals(3, started.get())
    }

    @Test
    fun `emit is synchronous and a throw truncates the rest`() {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        val calls = mutableListOf<String>()
        ctx.on(KEY) { calls.add("first"); null }
        ctx.on(KEY) { calls.add("second"); throw IllegalStateException("sync boom") }
        ctx.on(KEY) { calls.add("third"); null }
        assertFailsWith<IllegalStateException> { ctx.emit(KEY, "p") }
        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun `prepend inserts at head and disposal removes exactly one registration`() {
        val k = kernel()
        val ctx = Context(k, k.root, null)
        val order = mutableListOf<String>()
        ctx.on(KEY) { order.add("tail"); null }
        val handle = ctx.on(KEY, prepend = true) { order.add("head"); null }
        ctx.emit(KEY, "p")
        assertEquals(listOf("head", "tail"), order)
        handle.dispose()
        order.clear()
        ctx.emit(KEY, "p")
        assertEquals(listOf("tail"), order)
    }

    @Test
    fun `disposed fiber registration throws InactiveFiberException`() = runBlocking<Unit> {
        val k = kernel()
        val fiber = k.fiber("doomed")
        fiber.state = FiberState.ACTIVE
        fiber.dispose().await()
        assertFailsWith<InactiveFiberException> {
            Context(k, fiber, null).on(KEY) { null }
        }
    }

    private companion object {
        val KEY = EventKey<String, String>("test/waterfall")
        val STRING_KEY = EventKey<String, String>("test/string")
        val BOOL_KEY = EventKey<String, Boolean>("test/bool")
    }
}

private fun <P, R> Context.on(key: EventKey<P, R>, listener: suspend () -> R?): DisposableHandle =
    on(key) { listener() }

private fun <P> Context.onWaterfall(
    key: EventKey<P, String>,
    listener: suspend (P, suspend (P) -> String) -> Unit,
): DisposableHandle = on(key) { args ->
    @Suppress("UNCHECKED_CAST")
    val payload = args[0] as P
    @Suppress("UNCHECKED_CAST")
    val rawNext = args[1] as suspend () -> String
    var delegated = false
    var chainResult: String? = null
    listener(payload) {
        delegated = true
        chainResult = rawNext()
        chainResult!!
    }
    if (delegated) chainResult else "vetoed"
}
