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
class PluginHostTest {

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `manual registry rejects duplicate plugin names and mounts all`() = runBlocking {
        val k = Kernel()
        val mounted = AtomicInteger(0)
        k.host.register(PluginSpec("one", emptyList()) { mounted.incrementAndGet() })
        k.host.register(PluginSpec("two", emptyList()) { mounted.incrementAndGet() })
        assertFailsWith<IllegalArgumentException> { k.host.register(PluginSpec("one") {}) }
        assertEquals(2, k.host.registered().size)

        val handles = k.host.mountAll()
        assertEquals(2, handles.size)
        waitFor { mounted.get() == 2 }
        assertTrue(k.host.registered().any { it.name == "one" })
    }

    @Test
    fun `stopping a mount handle detaches the watcher`() = runBlocking {
        val k = Kernel()
        val applied = AtomicInteger(0)
        k.host.register(PluginSpec("stopme", listOf(SVC)) { applied.incrementAndGet() })
        val handles = k.host.mountAll()
        delay(50)
        assertEquals(0, applied.get())
        handles.first().dispose()
        k.registry.provide(SVC, "x")
        delay(50)
        assertEquals(0, applied.get(), "stopped mounts never activate")
    }

    private companion object {
        val SVC = ServiceKey<String>("host/service")
    }
}
