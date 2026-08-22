package com.lhzkml.jasmine.data

import com.lhzkml.jasmine.core.data.KernelHolder
import com.lhzkml.jasmine.core.data.PluginRow
import com.lhzkml.jasmine.core.data.PluginRuntime
import com.lhzkml.jasmine.core.data.PluginRuntimeState
import com.lhzkml.jasmine.core.data.TitleService
import com.lhzkml.jasmine.core.kernel.Context
import com.lhzkml.jasmine.core.kernel.PluginSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginRuntimeTest {

    private fun row(id: String, disabled: Boolean = false) =
        PluginRow(id = id, name = id, group = false, disabled = disabled)

    private fun runtime(holder: KernelHolder) = PluginRuntime(
        holder,
        mapOf(
            "session-title" to PluginSpec("session-title", emptyList()) { ctx: Context ->
                ctx.provide(TitleService, "jasmine-title")
            },
            "skill" to PluginSpec("skill", listOf(TitleService)) { ctx: Context ->
                ctx.await(TitleService)
                ctx.effect { { } }
            },
        ),
    )

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `enabled rows with code mount and provision services`() = runBlocking {
        val holder = KernelHolder()
        val runtime = runtime(holder)
        runtime.sync(listOf(row("session-title")))
        // The mounted plugin actually provided its service into the kernel.
        waitFor { holder.kernel.registry.get(TitleService) == "jasmine-title" }
        assertEquals(PluginRuntimeState.MOUNTED, runtime.states.value["session-title"])
    }

    @Test
    fun `disabling a row unmounts the live fiber`() = runBlocking {
        val holder = KernelHolder()
        val runtime = runtime(holder)
        runtime.sync(listOf(row("session-title")))
        waitFor { holder.kernel.registry.get(TitleService) == "jasmine-title" }
        assertEquals(PluginRuntimeState.MOUNTED, runtime.states.value["session-title"])

        runtime.sync(listOf(row("session-title", disabled = true)))
        waitFor { holder.kernel.registry.get(TitleService) == null }
        assertEquals(PluginRuntimeState.UNMOUNTED, runtime.states.value["session-title"])
    }

    @Test
    fun `rows without code are marked and never mount`() = runBlocking {
        val runtime = runtime(KernelHolder())
        runtime.sync(listOf(row("tool-bash")))
        assertEquals(PluginRuntimeState.NO_CODE, runtime.states.value["tool-bash"])
    }

    @Test
    fun `a failing plugin startup surfaces as FAILED`() = runBlocking {
        val holder = KernelHolder()
        val runtime = failingRuntime(holder)
        runtime.sync(listOf(row("broken")))
        waitFor { runtime.states.value["broken"] == PluginRuntimeState.FAILED }
    }

    @Test
    fun `dependent plugins activate only after their dependency`() = runBlocking {
        val holder = KernelHolder()
        val runtime = runtime(holder)
        // Mount the dependent first: it suspends until its dependency exists.
        runtime.sync(listOf(row("skill"), row("session-title")))
        // The service lands only when the session-title plugin activates.
        waitFor { holder.kernel.registry.get(TitleService) == "jasmine-title" }
    }
}

private fun PluginRuntimeTest.failingRuntime(holder: KernelHolder) = PluginRuntime(
    holder,
    mapOf(
        "broken" to com.lhzkml.jasmine.core.kernel.PluginSpec("broken", emptyList()) {
            error("startup boom")
        },
    ),
)
