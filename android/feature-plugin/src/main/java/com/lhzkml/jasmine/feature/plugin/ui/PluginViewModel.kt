package com.lhzkml.jasmine.feature.plugin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.rust.FfiPluginRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One installed plugin as the manager renders it. */
data class PluginUi(
    val record: FfiPluginRecord,
    val loaded: Boolean,
    val updating: Boolean = false,
)

data class PluginManagerState(
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val plugins: List<PluginUi> = emptyList(),
)

/**
 * Online management for the dynamic plugin runtime: installs (bundled +
 * network), enable/disable, launch/unload, and per-plugin update checks.
 * All decisions live in the Rust core; this view model only orchestrates.
 */
@HiltViewModel
class PluginViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(PluginManagerState())
    val uiState: StateFlow<PluginManagerState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null, message = null) }
        viewModelScope.launch {
            val result = runCatching { snapshot() }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { plugins ->
                        state.copy(loading = false, plugins = plugins)
                    },
                    onFailure = { t ->
                        state.copy(loading = false, error = t.message ?: t::class.java.simpleName)
                    },
                )
            }
        }
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { PluginHost.setPluginEnabled(pluginId, enabled) }
                .onFailure { _uiState.update { it.copy(error = it.error ?: "设置失败: ${it.message}") } }
            refresh()
        }
    }

    fun toggleRun(pluginId: String) {
        viewModelScope.launch {
            val loaded = PluginHost.isLoaded(pluginId)
            runCatching {
                if (loaded) PluginHost.unloadPlugin(pluginId) else PluginHost.launchPlugin(pluginId)
            }.onFailure { t ->
                _uiState.update { it.copy(error = "操作失败: ${t.message}") }
            }
            refresh()
        }
    }

    fun uninstall(pluginId: String) {
        viewModelScope.launch {
            runCatching { PluginHost.uninstallPlugin(pluginId) }
                .onFailure { t ->
                    _uiState.update { it.copy(error = "卸载失败: ${t.message}") }
                }
            refresh()
        }
    }

    fun installBundled() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null, error = null) }
            runCatching { PluginHost.installBundledPlugins() }
                .onSuccess { installed ->
                    _uiState.update {
                        it.copy(loading = false, message = if (installed.isEmpty()) {
                            "没有新的内置插件"
                        } else {
                            "已安装内置插件: ${installed.joinToString()}"
                        })
                    }
                }
                .onFailure { t ->
                    _uiState.update { it.copy(loading = false, error = "安装失败: ${t.message}") }
                }
            refresh()
        }
    }

    fun checkUpdate(pluginId: String) {
        val channel = PluginHost.updateChannel() ?: run {
            _uiState.update {
                it.copy(error = "更新通道未配置（updateManifestBaseUrl 为空）")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(plugins = state.plugins.map {
                    if (it.record.pluginId == pluginId) it.copy(updating = true) else it
                })
            }
            runCatching { channel.installUpdate(pluginId) }
                .onSuccess { updated ->
                    _uiState.update {
                        it.copy(message = if (updated) "插件已更新: $pluginId" else "已是最新版本: $pluginId")
                    }
                }
                .onFailure { t ->
                    _uiState.update { it.copy(error = "更新检查失败: ${t.message}") }
                }
            refresh()
        }
    }

    private fun snapshot(): List<PluginUi> =
        PluginHost.allPlugins().map { record ->
            PluginUi(record = record, loaded = PluginHost.isLoaded(record.pluginId))
        }
}
