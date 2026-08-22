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
 * The bound provider seam over the stored provider list: chat uses the
 * active provider's first selected model. Jasmine has no built-in provider
 * and no mock fallback — an unconfigured or model-less active provider
 * fails with an actionable error.
 */
@Singleton
class ConfigurableLlmService @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsStore: ProviderSettingsStore,
) : LlmService {

    init {
        ProviderTrace.start(context)
    }

    /** The active provider, or the first one when nothing is pinned. */
    suspend fun activeProvider(): ProviderEntry {
        val all = settingsStore.providers()
        if (all.isEmpty()) throw LlmProviderException("请先在设置中添加模型供应商")
        val activeId = settingsStore.activeProviderId()
        return all.firstOrNull { it.id == activeId } ?: all.first()
    }

    private fun configOf(entry: ProviderEntry): CustomProviderConfig {
        val model = entry.models.firstOrNull()
        if (entry.apiAddress.isBlank()) throw LlmProviderException("供应商「${entry.name}」缺少 API 地址")
        if (model.isNullOrBlank()) throw LlmProviderException("供应商「${entry.name}」未选择模型")
        if (entry.contextLength <= 0) throw LlmProviderException("供应商「${entry.name}」缺少上下文长度")
        return CustomProviderConfig(
            apiAddress = entry.apiAddress.trim(),
            apiKey = entry.apiKey.trim(),
            model = model.trim(),
            protocol = entry.protocol,
            contextLength = entry.contextLength,
            maxOutputTokens = entry.maxOutputTokens,
        )
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val entry = activeProvider()
        ProviderTrace.request("complete ${entry.protocol} ${entry.models.firstOrNull()}")
        return try {
            val response = CustomLlmService(configOf(entry), rawSink = ProviderTrace::raw).complete(request)
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
        val entry = activeProvider()
        ProviderTrace.request("stream ${entry.protocol} ${entry.models.firstOrNull()}")
        return try {
            val response = CustomLlmService(configOf(entry), rawSink = ProviderTrace::raw)
                .stream(request, onDelta, onReasoning)
            ProviderTrace.end("stream finished, ${response.content.length} chars")
            response
        } catch (t: Throwable) {
            ProviderTrace.end("error: ${t.message}")
            throw t
        }
    }

    /**
     * Connection probe: fetches the model list using only the API address
     * (and key when provided). Model and context fields are NOT required —
     * `/models` does not need them, so a user can test right after filling
     * address and key.
     */
    suspend fun testConnection(entry: ProviderEntry): List<String> {
        if (entry.apiAddress.isBlank()) throw LlmProviderException("请先填写 API 地址")
        val probe = CustomProviderConfig(
            apiAddress = entry.apiAddress.trim(),
            apiKey = entry.apiKey.trim(),
            model = "probe",
            protocol = entry.protocol,
            contextLength = 4096,
            maxOutputTokens = null,
        )
        ProviderTrace.request("probe ${entry.protocol} ${entry.apiAddress}")
        return try {
            CustomLlmService(probe).listModels()
        } catch (t: Throwable) {
            ProviderTrace.end("probe error: ${t.message}")
            throw t
        }
    }
}
