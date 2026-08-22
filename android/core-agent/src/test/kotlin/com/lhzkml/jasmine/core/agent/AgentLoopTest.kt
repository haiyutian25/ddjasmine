package com.lhzkml.jasmine.core.agent

import com.lhzkml.jasmine.core.kernel.Context
import com.lhzkml.jasmine.core.kernel.Kernel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentLoopTest {

    private class FakeStore : SessionStore {
        val events = mutableListOf<Pair<String, String>>()

        override fun append(sessionId: String, eventType: String, payloadJson: String): Long {
            events.add(eventType to payloadJson)
            return events.size.toLong() - 1
        }

        override fun eventTypes(sessionId: String): List<String> = events.map { it.first }

        override fun messages(sessionId: String): List<LlmMessage> =
            events.mapNotNull { (type, payload) ->
                when (type) {
                    "user/message" -> LlmMessage(
                        "user",
                        payload.substringAfter("content\":\"").removeSuffix("\"}"),
                    )
                    else -> null
                }
            }
    }

    @Test
    fun `a turn writes the complete replayable event sequence`() = runBlocking {
        val kernel = Kernel()
        val store = FakeStore()
        kernel.registry.provide(LlmServiceKey, MockLlmService())
        val loop = AgentLoop(kernel)

        val turn = loop.runTurn(store, "s1", "hello")

        assertEquals(
            listOf("turn/start", "step/start", "user/message", "assistant/message", "step/end", "turn/end"),
            store.events.map { it.first },
        )
        assertEquals("echo: hello", turn.content)
        assertEquals(1L, turn.turn)
    }

    @Test
    fun `turns are numbered by existing turn-starts`() = runBlocking {
        val kernel = Kernel()
        val store = FakeStore()
        kernel.registry.provide(LlmServiceKey, MockLlmService())
        val loop = AgentLoop(kernel)
        store.append("s1", "turn/start", """{"turn":1}""")
        store.append("s1", "turn/end", """{"turn":1}""")

        val second = loop.runTurn(store, "s1", "again")

        assertEquals(2L, second.turn)
    }

    @Test
    fun `the llm-request waterfall can veto the provider`() = runBlocking {
        val kernel = Kernel()
        val store = FakeStore()
        kernel.registry.provide(LlmServiceKey, MockLlmService())
        val loop = AgentLoop(kernel)
        var intercepted = 0
        val ctx = Context(kernel, kernel.root, null)
        ctx.on(llmRequestKey) { _ ->
            intercepted++
            LlmResponse(content = "vetoed")
        }

        val turn = loop.runTurn(store, "s1", "hello")

        assertEquals("vetoed", turn.content)
        assertEquals(1, intercepted)
    }

    @Test
    fun `a missing provider fails the turn loudly`() = runBlocking {
        val kernel = Kernel()
        val store = FakeStore()
        val loop = AgentLoop(kernel)
        val failure = runCatching {
            withTimeout(2_000) { loop.runTurn(store, "s1", "hi") }
        }
        assertTrue(failure.isFailure)
        assertEquals(
            listOf("turn/start", "step/start", "user/message", "step/end", "turn/end"),
            store.events.map { it.first },
        )
    }

    @Test
    fun `a provider failure closes the step and turn before propagating`() = runBlocking {
        val kernel = Kernel()
        val store = FakeStore()
        kernel.registry.provide(LlmServiceKey, object : LlmService {
            override suspend fun complete(request: LlmRequest): LlmResponse =
                throw LlmProviderException("provider failed")
        })

        val failure = runCatching { AgentLoop(kernel).runTurn(store, "s1", "hi") }

        assertTrue(failure.isFailure)
        assertEquals(
            listOf("turn/start", "step/start", "user/message", "step/end", "turn/end"),
            store.events.map { it.first },
        )
        assertTrue(store.events.last().second.contains("error"))
    }
}
