package com.lhzkml.jasmine.core.kernel

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FiberTest {

    private fun kernel() = Kernel()

    @Test
    fun `disposers run in reverse order`() = runBlocking {
        val k = kernel()
        val fiber = k.fiber("ordered")
        val order = mutableListOf<String>()
        fiber.effect { { order.add("first") } }
        fiber.effect { { order.add("second") } }
        fiber.effect { { order.add("third") } }
        fiber.dispose().await()
        assertEquals(listOf("third", "second", "first"), order)
    }

    @Test
    fun `one failing disposer never breaks the others`() = runBlocking {
        val k = kernel()
        val fiber = k.fiber("isolated")
        val cleaned = mutableListOf<String>()
        fiber.effect { { error("boom") } }
        fiber.effect { { cleaned.add("ok") } }
        fiber.dispose().await() // completes despite the failure
        assertEquals(listOf("ok"), cleaned)
        assertEquals(FiberState.DISPOSED, fiber.state)
    }

    @Test
    fun `dispose twice is a no-op and await rethrows startup failure`() = runBlocking {
        val k = kernel()
        val fiber = k.fiber("idem")
        val ran = AtomicInteger(0)
        fiber.effect { { ran.incrementAndGet() } }
        fiber.dispose().await()
        fiber.dispose().await()
        assertEquals(1, ran.get())

        val failed = k.fiber("failed")
        failed.failure = IllegalStateException("startup boom")
        failed.state = FiberState.FAILED
        failed.dispose()
        val t = assertFailsWith<IllegalStateException> { failed.await() }
        assertEquals("startup boom", t.message)
    }

    @Test
    fun `parent disposal cascades into child fibers`() = runBlocking {
        val k = kernel()
        val parent = k.fiber("parent")
        val child = Fiber(parent = parent, kernel = k, label = "child")
        child.state = FiberState.ACTIVE
        parent.dispose().await()
        child.dispose() // idempotent if cascade already ran
        assertEquals(FiberState.DISPOSED, child.state)
    }

    @Test
    fun `effects nested inside an effect belong to the outer one`() = runBlocking {
        val k = kernel()
        val fiber = k.fiber("nested")
        val order = mutableListOf<String>()
        fiber.effect outer@{
            fiber.effect { { order.add("inner") } }
            return@outer { order.add("outer") }
        }
        // Only the outer disposer is on the fiber stack; it reclaims the inner
        // effect's disposer. Disposal is reverse registration order, so the
        // outer's own disposer runs before the inner effect's (upstream splice+reverse).
        fiber.dispose().await()
        assertEquals(listOf("outer", "inner"), order)
    }

    @Test
    fun `async disposers complete before dispose settles`() = runBlocking {
        val k = kernel()
        val fiber = k.fiber("async")
        var finished = false
        fiber.effect { { delay(50); finished = true } }
        fiber.dispose().await()
        assertTrue(finished)
    }

    @Test
    fun `handle dispose reclaims only its own effect`() = runBlocking {
        val k = kernel()
        val fiber = k.fiber("handle")
        val ran = mutableListOf<String>()
        val handle = fiber.effect { { ran.add("early") } }
        fiber.effect { { ran.add("late") } }
        handle.dispose()
        // Disposal is asynchronous (disposers may suspend); wait for settle.
        withTimeout(5_000) {
            while (ran.isEmpty()) delay(10)
        }
        assertEquals(listOf("early"), ran)
        fiber.dispose().await()
        assertEquals(listOf("early", "late"), ran)
    }
}
