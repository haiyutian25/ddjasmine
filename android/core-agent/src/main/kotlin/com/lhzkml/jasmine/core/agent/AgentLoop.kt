package com.lhzkml.jasmine.core.agent

import com.lhzkml.jasmine.core.kernel.Kernel
import kotlinx.coroutines.withTimeout

/**
 * The M2 agent loop: one user turn becomes a complete, crash-replayable
 * event sequence — `turn/start`, `step/start`, the user message, the LLM
 * request dispatched through the `llm/request` waterfall, the assistant
 * message, `step/end`, `turn/end`. Payload shapes match the Rust spine's
 * invariant validators, so a recorded turn replays cleanly.
 */
class AgentLoop(private val kernel: Kernel) {

    /**
     * Runs one turn against [store].
     *
     * @param userText the incoming user message; appended by the loop so the
     *   derived history it prices is exactly what the model sees.
     * @param onDelta receives final-content deltas as the provider streams.
     * @param onReasoning receives thinking deltas (reasoner models).
     * @return the assistant content plus the assigned turn number.
     */
    suspend fun runTurn(
        store: SessionStore,
        sessionId: String,
        userText: String,
        onDelta: suspend (String) -> Unit = {},
        onReasoning: suspend (String) -> Unit = {},
    ): AgentTurn {
        val turn = store.eventTypes(sessionId).count { it == "turn/start" }.toLong() + 1
        val step = 1

        store.append(sessionId, "turn/start", """{"turn":$turn}""")
        store.append(sessionId, "step/start", """{"turn":$turn,"step":$step}""")
        store.append(sessionId, "user/message", """{"content":${userText.toJsonString()}}""")

        val request = LlmRequest(messages = store.messages(sessionId))
        return try {
            val response = withTimeout(REQUEST_TIMEOUT_MS) {
                kernel.events.waterfall(
                    key = llmRequestKey,
                    from = null,
                    payload = request,
                    inner = { kernel.registry.await(LlmServiceKey).stream(request, onDelta, onReasoning) },
                )
            }
            store.append(
                sessionId,
                "assistant/message",
                """{"turn":$turn,"step":$step,"content":${response.content.toJsonString()}}""",
            )
            store.append(sessionId, "step/end", """{"turn":$turn,"step":$step}""")
            store.append(sessionId, "turn/end", """{"turn":$turn}""")
            AgentTurn(turn = turn, content = response.content)
        } catch (failure: Throwable) {
            // A failed provider request should close the execution frame, but
            // cleanup failures must never replace the original provider error.
            runCatching {
                store.append(sessionId, "step/end", """{"turn":$turn,"step":$step}""")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching {
                store.append(
                    sessionId,
                    "turn/end",
                    """{"turn":$turn,"reason":{"kind":"error","message":${failure.message.orEmpty().toJsonString()}}}""",
                )
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun String.toJsonString(): String = buildString {
        append('"')
        for (ch in this@toJsonString) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 120_000L
    }
}

/** One finished turn's observable result. */
data class AgentTurn(val turn: Long, val content: String)
