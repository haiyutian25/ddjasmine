package com.lhzkml.jasmine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.jasmine.core.agent.ProviderProtocol
import com.lhzkml.jasmine.core.data.ConfigurableLlmService
import com.lhzkml.jasmine.core.data.ProviderSettings
import com.lhzkml.jasmine.core.data.ProviderSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State and validation of the user-defined provider connection page. */
@HiltViewModel
class ProviderSettingsViewModel @Inject constructor(
    private val settingsStore: ProviderSettingsStore,
    private val llmService: ConfigurableLlmService,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS,
        val apiAddress: String = "",
        val apiKey: String = "",
        val model: String = "",
        val contextLength: String = "",
        val maxOutputTokens: String = "",
        val models: List<String> = emptyList(),
        val testing: Boolean = false,
        val result: String? = null,
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { settingsStore.load() }
            _uiState.update {
                UiState(
                    protocol = settings.protocol,
                    apiAddress = settings.apiAddress,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    contextLength = settings.contextLength.takeIf { value -> value > 0 }?.toString().orEmpty(),
                    maxOutputTokens = settings.maxOutputTokens?.toString().orEmpty(),
                )
            }
        }
    }

    fun selectProtocol(value: ProviderProtocol) = edit { copy(protocol = value) }
    fun updateApiAddress(value: String) = edit { copy(apiAddress = value) }
    fun updateApiKey(value: String) = edit { copy(apiKey = value) }
    fun selectModel(value: String) = edit { copy(model = value) }
    fun updateContextLength(value: String) = edit { copy(contextLength = value.filter(Char::isDigit)) }
    fun updateMaxOutputTokens(value: String) = edit { copy(maxOutputTokens = value.filter(Char::isDigit)) }

    private fun edit(transform: UiState.() -> UiState) =
        _uiState.update { it.transform().copy(saved = false, result = null, error = null) }

    private fun currentSettings(): Result<ProviderSettings> {
        val state = _uiState.value
        val context = state.contextLength.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("请填写有效的上下文长度"))
        val output = state.maxOutputTokens.takeIf(String::isNotBlank)?.toIntOrNull()
        if (state.maxOutputTokens.isNotBlank() && output == null) {
            return Result.failure(IllegalArgumentException("请填写有效的最大输出长度，或留空使用供应商最大值"))
        }
        if (state.apiAddress.isBlank()) return Result.failure(IllegalArgumentException("API 地址不能为空"))
        if (state.model.isBlank()) return Result.failure(IllegalArgumentException("模型不能为空"))
        if (context <= 0) return Result.failure(IllegalArgumentException("上下文长度必须大于 0"))
        if (output != null && output <= 0) return Result.failure(IllegalArgumentException("最大输出长度必须大于 0"))
        if (output != null && output >= context) return Result.failure(IllegalArgumentException("最大输出长度必须小于上下文长度"))
        return Result.success(
            ProviderSettings(
                apiAddress = state.apiAddress.trim(),
                apiKey = state.apiKey.trim(),
                model = state.model.trim(),
                protocol = state.protocol,
                contextLength = context,
                maxOutputTokens = output,
            )
        )
    }

    fun save() {
        val settings = currentSettings().getOrElse { failure ->
            _uiState.update { it.copy(error = failure.message) }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsStore.save(settings) }
            _uiState.update { it.copy(saved = true, result = "已保存") }
        }
    }

    /** Fetches `/models`; this validates address/auth and gives the model chooser data. */
    fun testAndFetchModels() {
        val settings = currentSettings().getOrElse { failure ->
            _uiState.update { it.copy(error = failure.message) }
            return
        }
        _uiState.update { it.copy(testing = true, result = null, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { llmService.listModels(settings) }
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { models ->
                        state.copy(
                            testing = false,
                            result = "连接成功，共 ${models.size} 个模型",
                            models = models,
                        )
                    },
                    onFailure = { failure ->
                        state.copy(testing = false, error = "连接失败：${failure.message}")
                    },
                )
            }
        }
    }
}
