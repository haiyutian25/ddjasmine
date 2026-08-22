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
 * The bound provider seam over the stored provider list: the chat uses the
 * pinned model only — no selection is an explicit "choose a model" error,
 * never a silent fallback. Every provider is active at once; there is no
 * active provider switch. Jasmine has no built-in provider and no mock
 * fallback — an unconfigured or model-less provider fails with an
 * actionable error.
 */
@Singleton
class ConfigurableLlmService @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsStore: ProviderSettingsStore,
) : LlmService {

    init {
        ProviderTrace.start(context)
    }

    /** The provider plus the model the chat currently uses. */
    data class ActiveModel(val provider: ProviderEntry, val model: String)

    /**
     * Resolves the chat's model from the pinned selection only: no selection
     * (or a selection whose model was removed) is an error, never a silent
     * fallback to some other model.
     */
    suspend fun activeModel(): ActiveModel {
        val all = settingsStore.providers()
        if (all.isEmpty()) throw LlmProviderException("请先在设置中添加模型供应商")
        val pinned = settingsStore.activeModelId()
            ?: throw LlmProviderException("尚未选择模型，请在聊天输入框中选择一个模型")
        val provider = all.firstOrNull { it.models.contains(pinned) }
            ?: throw LlmProviderException("已选择的模型「$pinned」不存在，请在聊天输入框中重新选择")
        return ActiveModel(provider, pinned)
    }

    private fun configOf(provider: ProviderEntry, model: String): CustomProviderConfig {
        if (provider.apiAddress.isBlank()) throw LlmProviderException("供应商「${provider.name}」缺少 API 地址")
        if (provider.contextLength <= 0) throw LlmProviderException("供应商「${provider.name}」缺少上下文长度")
        return CustomProviderConfig(
            apiAddress = provider.apiAddress.trim(),
            apiKey = provider.apiKey.trim(),
            model = model.trim(),
            protocol = provider.protocol,
            contextLength = provider.contextLength,
            maxOutputTokens = provider.maxOutputTokens,
        )
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val active = activeModel()
        ProviderTrace.request("complete ${active.provider.protocol} ${active.model}")
        return try {
            val response = CustomLlmService(configOf(active.provider, active.model), rawSink = ProviderTrace::raw)
                .complete(request)
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
        val active = activeModel()
        ProviderTrace.request("stream ${active.provider.protocol} ${active.model}")
        return try {
            val response = CustomLlmService(configOf(active.provider, active.model), rawSink = ProviderTrace::raw)
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
