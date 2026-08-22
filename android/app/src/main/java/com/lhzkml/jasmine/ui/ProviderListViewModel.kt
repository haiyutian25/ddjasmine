package com.lhzkml.jasmine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** State of the provider list: every provider, all active at once. */
@HiltViewModel
class ProviderListViewModel @Inject constructor(
    private val store: ProviderSettingsStore,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val providers: List<ProviderEntry> = emptyList(),
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val providers = withContext(Dispatchers.IO) { store.providers() }
            _uiState.update {
                it.copy(loading = false, providers = providers, error = null)
            }
        }
    }

    fun delete(providerId: String) {
        viewModelScope.launch {
            val current = _uiState.value.providers
            val remaining = current.filterNot { it.id == providerId }
            withContext(Dispatchers.IO) {
                store.save(remaining)
            }
            load()
        }
    }
}
