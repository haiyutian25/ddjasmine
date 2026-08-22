package com.lhzkml.jasmine.core.agent

import com.lhzkml.jasmine.core.kernel.EventKey
import com.lhzkml.jasmine.core.kernel.ServiceKey

/** One model-visible message in a request (role + text content). */
data class LlmMessage(val role: String, val content: String)

/** A completion request: the derived history plus caller metadata. */
data class LlmRequest(val messages: List<LlmMessage>)

/** A completed assistant turn. */
data class LlmResponse(val content: String)

/**
 * The LLM capability seam: providers implement [LlmService] and register it
 * under [LlmServiceKey]; consumers await the key. [llmRequest] is the
 * waterfall extension point — plugins may intercept or veto a request
 * before it reaches the provider (the `llm/stream` counterpart).
 */
interface LlmService {
    suspend fun complete(request: LlmRequest): LlmResponse

    /**
     * Streaming completion: the provider delivers final-content deltas
     * through [onDelta] and reasoning/thinking deltas through
     * [onReasoning] as they arrive, returning the accumulated final
     * content. Providers that cannot stream fall back to [complete] and
     * emit the whole text as one delta.
     */
    suspend fun stream(
        request: LlmRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): LlmResponse {
        val response = complete(request)
        if (response.content.isNotEmpty()) onDelta(response.content)
        return response
    }
}

/** Service token of the LLM provider. */
val LlmServiceKey = ServiceKey<LlmService>("llm/service")

/** Per-request waterfall: listeners get `[request, next]`. */
val llmRequestKey = EventKey<LlmRequest, LlmResponse>("llm/request")
