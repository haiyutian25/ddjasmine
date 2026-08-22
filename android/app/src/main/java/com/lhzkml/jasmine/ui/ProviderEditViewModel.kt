package com.lhzkml.jasmine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.jasmine.core.agent.ProviderProtocol
import com.lhzkml.jasmine.core.data.ConfigurableLlmService
import com.lhzkml.jasmine.core.data.ProviderEntry
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

/** State of one provider's edit page: fields, available models, selection. */
@HiltViewModel
class ProviderEditViewModel @Inject constructor(
    private val store: ProviderSettingsStore,
    private val llmService: ConfigurableLlmService,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val providerId: String? = null,
        val name: String = "",
        val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS,
        val apiAddress: String = "",
        val apiKey: String = "",
        val models: List<String> = emptyList(),
        val available: List<String> = emptyList(),
        val contextLength: String = "",
        val maxOutputTokens: String = "",
        val testing: Boolean = false,
        val result: String? = null,
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Loads an existing provider, or seeds a fresh entry for creation. */
    fun load(providerId: String?) {
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                if (providerId == null) {
                    val fresh = store.newEntry()
                    UiState(
                        providerId = fresh.id,
                        name = fresh.name,
                    )
                } else {
                    val entry = store.providers().firstOrNull { it.id == providerId }
                        ?: return@withContext UiState(loading = false, error = "供应商不存在")
                    UiState(
                        providerId = entry.id,
                        name = entry.name,
                        protocol = entry.protocol,
                        apiAddress = entry.apiAddress,
                        apiKey = entry.apiKey,
                        models = entry.models,
                        contextLength = entry.contextLength.takeIf { it > 0 }?.toString().orEmpty(),
                        maxOutputTokens = entry.maxOutputTokens?.toString().orEmpty(),
                    )
                }
            }
            _uiState.update { state.copy(loading = false) }
        }
    }

    fun updateName(value: String) = edit { copy(name = value) }
    fun selectProtocol(value: ProviderProtocol) = edit { copy(protocol = value) }
    fun updateApiAddress(value: String) = edit { copy(apiAddress = value) }
    fun updateApiKey(value: String) = edit { copy(apiKey = value) }
    fun updateContextLength(value: String) = edit { copy(contextLength = value.filter(Char::isDigit)) }
    fun updateMaxOutputTokens(value: String) = edit { copy(maxOutputTokens = value.filter(Char::isDigit)) }

    /** Toggles one model in the provider's selection. */
    fun toggleModel(model: String) = edit {
        copy(models = if (model in models) models - model else models + model)
    }

    fun selectAllModels() = edit { copy(models = available) }
    fun clearModels() = edit { copy(models = emptyList()) }

    private fun edit(transform: UiState.() -> UiState) =
        _uiState.update { it.transform().copy(saved = false, result = null, error = null) }

    /** Probes the connection: only address (and optional key) are needed. */
    fun testAndFetchModels() {
        val state = _uiState.value
        if (state.apiAddress.isBlank()) {
            _uiState.update { it.copy(error = "请先填写 API 地址") }
            return
        }
        val probe = ProviderEntry(
            id = state.providerId ?: "",
            name = state.name,
            protocol = state.protocol,
            apiAddress = state.apiAddress.trim(),
            apiKey = state.apiKey.trim(),
        )
        _uiState.update { it.copy(testing = true, result = null, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { llmService.testConnection(probe) }
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { available ->
                        state.copy(
                            testing = false,
                            available = available,
                            result = "连接成功，共 ${available.size} 个模型，请选择要使用的模型",
                        )
                    },
                    onFailure = { failure ->
                        state.copy(testing = false, error = "连接失败：${failure.message}")
                    },
                )
            }
        }
    }

    /** Saves (create or update) and returns true on success. */
    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.apiAddress.isBlank()) {
            _uiState.update { it.copy(error = "API 地址不能为空") }
            return
        }
        val context = state.contextLength.toIntOrNull()
        val output = state.maxOutputTokens.takeIf(String::isNotBlank)?.toIntOrNull()
        if (state.contextLength.isNotBlank() && context == null) {
            _uiState.update { it.copy(error = "请填写有效的上下文长度") }
            return
        }
        if (state.maxOutputTokens.isNotBlank() && (output == null || output <= 0 || (context != null && output >= context))) {
            _uiState.update { it.copy(error = "最大输出长度无效（需小于上下文长度，或留空）") }
            return
        }
        val entry = ProviderEntry(
            id = state.providerId ?: "",
            name = state.name.ifBlank { "未命名" },
            protocol = state.protocol,
            apiAddress = state.apiAddress.trim(),
            apiKey = state.apiKey.trim(),
            models = state.models,
            contextLength = context ?: 0,
            maxOutputTokens = output,
        )
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val all = store.providers()
                val exists = all.any { it.id == entry.id }
                val updated = if (exists) all.map { if (it.id == entry.id) entry else it } else all + entry
                store.save(updated)
                // A freshly created provider becomes active immediately.
                if (!exists && store.activeProviderId().isBlank()) store.setActive(entry.id)
                true
            }
            _uiState.update { it.copy(saved = true, result = "已保存") }
            if (saved) onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val providerId = _uiState.value.providerId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val remaining = store.providers().filterNot { it.id == providerId }
                store.save(remaining)
                if (store.activeProviderId() == providerId) {
                    store.setActive(remaining.firstOrNull()?.id.orEmpty())
                }
            }
            onDeleted()
        }
    }
}
