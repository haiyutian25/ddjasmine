package com.lhzkml.jasmine.core.data

import android.content.Context
import com.lhzkml.jasmine.core.agent.CustomLlmService
import com.lhzkml.jasmine.core.agent.CustomProviderConfig
import com.lhzkml.jasmine.core.agent.LlmProviderException
import com.lhzkml.jasmine.core.agent.LlmRequest
import com.lhzkml.jasmine.core.agent.LlmResponse
import com.lhzkml.jasmine.core.agent.LlmService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bound custom provider. Jasmine has no built-in provider and no mock
 * fallback: chatting before a complete connection is configured fails with
 * an actionable error and sends no request. Every provider response is
 * traced verbatim through [ProviderTrace] so raw streaming (or its absence)
 * and thinking deltas are inspectable in Downloads.
 */
@Singleton
class ConfigurableLlmService @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsStore: ProviderSettingsStore,
) : LlmService {

    init {
        ProviderTrace.start(context)
    }

    private fun configOf(settings: ProviderSettings): CustomProviderConfig {
        if (!settings.isConfigured) {
            throw LlmProviderException("请先在设置中配置 API 地址、协议、模型和上下文长度")
        }
        return CustomProviderConfig(
            apiAddress = settings.apiAddress.trim(),
            apiKey = settings.apiKey.trim(),
            model = settings.model.trim(),
            protocol = settings.protocol,
            contextLength = settings.contextLength,
            maxOutputTokens = settings.maxOutputTokens,
        )
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val settings = settingsStore.load()
        ProviderTrace.request("complete ${settings.protocol} ${settings.model}")
        return try {
            val response = CustomLlmService(configOf(settings), rawSink = ProviderTrace::raw).complete(request)
            ProviderTrace.end("complete finished, ${response.content.length} chars")
            response
        } catch (t: Throwable) {
            ProviderTrace.end("error: ${t.message}")
            throw t
        }
    }

    override suspend fun stream(
        request: LlmRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): LlmResponse {
        val settings = settingsStore.load()
        ProviderTrace.request("stream ${settings.protocol} ${settings.model}")
        return try {
            val response = CustomLlmService(configOf(settings), rawSink = ProviderTrace::raw)
                .stream(request, onDelta, onReasoning)
            ProviderTrace.end("stream finished, ${response.content.length} chars")
            response
        } catch (t: Throwable) {
            ProviderTrace.end("error: ${t.message}")
            throw t
        }
    }

    /** Lists model ids under the given custom settings; also tests connection. */
    suspend fun listModels(settings: ProviderSettings): List<String> =
        CustomLlmService(configOf(settings)).listModels()
}
