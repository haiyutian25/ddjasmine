package com.lhzkml.jasmine.feature.session.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lhzkml.jasmine.core.agent.AgentLoop
import com.lhzkml.jasmine.core.agent.SessionStore
import com.lhzkml.jasmine.core.data.ProviderSettingsStore
import com.lhzkml.jasmine.core.data.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One selectable model across all providers (provider name shown in the picker). */
data class ModelOption(
    val providerId: String,
    val providerName: String,
    val model: String,
)

/** One settled chat line in the live transcript. */
data class ChatEntry(
    val id: String,
    val fromUser: Boolean,
    val content: String,
    val timeMs: Long,
    /** Thinking text of an assistant entry; `null` when the model had none. */
    val reasoning: String? = null,
)

/**
 * State of one chat. The transcript is what was actually displayed:
 * user messages are optimistic, assistant messages are the streamed
 * output kept in place — the Rust log stays the durable record, but the UI
 * never re-extracts a second copy of the final content.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val agentLoop: AgentLoop,
    private val sessionStore: SessionStore,
    private val providerStore: ProviderSettingsStore,
) : ViewModel() {

    data class UiState(
        val sessionId: String? = null,
        val entries: List<ChatEntry> = emptyList(),
        val sending: Boolean = false,
        /** All models selected across all providers (chat model picker). */
        val modelOptions: List<ModelOption> = emptyList(),
        /** The model the chat currently uses. */
        val activeModel: String? = null,
        /** Assistant final text accumulated from the live stream. */
        val streamingText: String? = null,
        /** Thinking text streamed before the final content (reasoner models). */
        val streamingReasoning: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val idCounter = AtomicLong(0)

    init {
        newSession()
        loadModelOptions()
    }

    /** Loads every provider's selected models plus the pinned chat model. */
    fun loadModelOptions() {
        viewModelScope.launch {
            val (options, activeModel) = withContext(Dispatchers.IO) {
                val providers = providerStore.providers()
                val options = providers.flatMap { provider ->
                    provider.models.map { ModelOption(provider.id, provider.name, it) }
                }
                val pinned = providerStore.activeModelId()
                // No fallback: an unpinned or vanished selection stays
                // unselected so the input bar shows "选择模型".
                val active = pinned?.takeIf { model -> options.any { it.model == model } }
                options to active
            }
            _uiState.update { it.copy(modelOptions = options, activeModel = activeModel) }
        }
    }

    /** Pins the selected model for the chat; every provider stays active. */
    fun selectModel(option: ModelOption) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                providerStore.setActiveModel(option.model)
            }
            _uiState.update { it.copy(activeModel = option.model) }
        }
    }

    /** Starts a fresh session log. */
    fun newSession() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { sessionRepository.create() }
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { id -> UiState(sessionId = id) },
                    onFailure = { t -> state.copy(error = t.message) },
                )
            }
        }
    }

    /** Sends one user message; the assistant reply is a real agent turn. */
    fun send(text: String) {
        val sessionId = _uiState.value.sessionId ?: return
        if (text.isBlank() || _uiState.value.sending) return
        val trimmed = text.trim()
        val now = System.currentTimeMillis()
        _uiState.update { state ->
            state.copy(
                sending = true,
                entries = state.entries + ChatEntry(
                    id = nextId(),
                    fromUser = true,
                    content = trimmed,
                    timeMs = now,
                ),
                streamingText = "",
                streamingReasoning = "",
                error = null,
            )
        }
        viewModelScope.launch {
            // Deltas run on the provider's I/O thread; StateFlow.update is
            // thread-safe, and Compose renders appended text live.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    agentLoop.runTurn(
                        store = sessionStore,
                        sessionId = sessionId,
                        userText = trimmed,
                        onDelta = { delta ->
                            _uiState.update { state ->
                                state.copy(streamingText = state.streamingText.orEmpty() + delta)
                            }
                        },
                        onReasoning = { delta ->
                            _uiState.update { state ->
                                state.copy(streamingReasoning = state.streamingReasoning.orEmpty() + delta)
                            }
                        },
                    )
                }
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = {
                        // The streamed output is kept as the final assistant
                        // entry — no second extraction from the log.
                        state.copy(
                            sending = false,
                            entries = state.entries + ChatEntry(
                                id = nextId(),
                                fromUser = false,
                                content = state.streamingText.orEmpty(),
                                timeMs = System.currentTimeMillis(),
                                reasoning = state.streamingReasoning.orEmpty().takeIf(String::isNotEmpty),
                            ),
                            streamingText = null,
                            streamingReasoning = null,
                        )
                    },
                    onFailure = { t ->
                        state.copy(
                            sending = false,
                            streamingText = null,
                            streamingReasoning = null,
                            error = t.message,
                        )
                    },
                )
            }
        }
    }

    private fun nextId(): String = "e${idCounter.incrementAndGet()}"
}
