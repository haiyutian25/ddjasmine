package com.lhzkml.jasmine.feature.plugin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.jasmine.core.data.PluginRepository
import com.lhzkml.jasmine.core.data.PluginRow
import com.lhzkml.jasmine.core.data.PluginRuntime
import com.lhzkml.jasmine.core.data.PluginRuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the plugin manager: the profile composed through the Rust
 * `compose` crate (rows plus its warnings) and enable/disable toggles.
 * Repository calls are blocking JNI and run on the IO dispatcher.
 */
@HiltViewModel
class PluginViewModel @Inject constructor(
    private val pluginRepository: PluginRepository,
    private val pluginRuntime: PluginRuntime,
) : ViewModel() {

    /** Work dispatcher for repository calls; tests swap it for a scheduler. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    constructor(
        pluginRepository: PluginRepository,
        pluginRuntime: PluginRuntime,
        ioDispatcher: CoroutineDispatcher,
    ) : this(pluginRepository, pluginRuntime) {
        this.ioDispatcher = ioDispatcher
    }

    /** Live runtime state per plugin id, reconciled on every refresh/toggle. */
    val runtimeStates: StateFlow<Map<String, PluginRuntimeState>> = pluginRuntime.states

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val rows: List<PluginRow> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { pluginRepository.rows() to pluginRepository.warnings() }
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { (rows, warnings) ->
                        pluginRuntime.sync(rows)
                        state.copy(loading = false, rows = rows, warnings = warnings)
                    },
                    onFailure = { t -> state.copy(loading = false, error = t.message) },
                )
            }
        }
    }

    fun setDisabled(id: String, disabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { pluginRepository.setDisabled(id, disabled) }
            }
            result.fold(
                onSuccess = { refresh() },
                onFailure = { t -> _uiState.update { it.copy(error = t.message) } },
            )
        }
    }
}
