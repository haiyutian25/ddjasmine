package com.lhzkml.jasmine.core.kernel

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RegistryTest {

    private fun kernel() = Kernel()

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `plugin suspends until dependencies are available then activates`() = runBlocking {
        val k = kernel()
        val applied = AtomicInteger(0)
        val disposed = AtomicInteger(0)
        k.host.register(
            PluginSpec(name = "dependent", dependencies = listOf(SVC)) { ctx ->
                applied.incrementAndGet()
                ctx.effect { { disposed.incrementAndGet() } }
            }
        )
        k.host.mountAll()
        delay(100)
        assertEquals(0, applied.get(), "must not activate before its dependency exists")

        val provision = k.registry.provide(SVC, "v1")
        waitFor("activation") { applied.get() == 1 }

        // Withdrawing the dependency disposes the live fiber and re-suspends.
        provision.dispose()
        waitFor("deactivation") { disposed.get() == 1 }
    }

    @Test
    fun `duplicate provision in one scope throws`() {
        val k = kernel()
        k.registry.provide(SVC, "a")
        assertFailsWith<DuplicateServiceException> { k.registry.provide(SVC, "b") }
    }

    @Test
    fun `await suspends and resumes on provision and withdrawal`() = runBlocking<Unit> {
        val k = kernel()
        val observed = mutableListOf<String?>()
        val deferred = async {
            observed.add("waiting")
            val value = k.registry.await(SVC)
            observed.add(value)
        }
        waitFor("suspended") { observed.firstOrNull() == "waiting" }
        k.registry.provide(SVC, "x")
        waitFor("resumed") { observed.lastOrNull() == "x" }
        deferred.await()
    }

    @Test
    fun `HMR equivalence - disposing the fiber removes its provided services`() = runBlocking {
        val k = kernel()
        val fiber = k.fiber("provider")
        fiber.state = FiberState.ACTIVE
        val ctx = Context(k, fiber, null)
        ctx.provide(SVC, "owned")
        assertEquals("owned", k.registry.get(SVC))
        fiber.dispose().await()
        waitFor("removal") { k.registry.get(SVC) == null }
        assertNull(k.registry.get(SVC))
    }

    @Test
    fun `startup failure lands in the fiber and never crashes the host`() = runBlocking {
        val k = kernel()
        k.host.register(
            PluginSpec(name = "broken", dependencies = emptyList()) {
                error("config boom")
            }
        )
        k.host.mountAll()
        // The mount watcher ends; the registry stays usable.
        delay(100)
        k.registry.provide(SVC, "still-alive")
        assertEquals("still-alive", k.registry.get(SVC))
    }

    @Test
    fun `activation order does not depend on mount order`() = runBlocking {
        val k = kernel()
        val order = mutableListOf<String>()
        k.host.register(PluginSpec(name = "late", dependencies = listOf(SVC)) { order.add("late") })
        k.host.register(PluginSpec(name = "early", dependencies = emptyList()) { order.add("early") })
        k.host.mountAll()
        waitFor("early applied") { order.contains("early") }
        k.registry.provide(SVC, "x")
        waitFor("late applied") { order.contains("late") }
        assertEquals("early", order.first(), "zero-dependency plugin activates immediately")
    }

    private companion object {
        val SVC = ServiceKey<String>("test/service")
    }
}
