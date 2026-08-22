package com.lhzkml.jasmine.feature.session.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lhzkml.jasmine.core.ui.InkBlack
import com.lhzkml.jasmine.feature.session.R
import java.text.DateFormat
import java.util.Date

/**
 * The chat page over the live transcript. User messages read right,
 * assistant messages left; thinking is its own collapsible block that folds
 * automatically once the reply finishes (tap to expand). The Rust log stays
 * the durable record — the UI never re-extracts the final content.
 *
 * IME contract (the one that bit us): the activity declares
 * `windowSoftInputMode="adjustResize"` and the input bar is the ONLY place
 * carrying [Modifier.imePadding] — applying it elsewhere double-counts the
 * inset and floats the bar mid-screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit = {},
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Reload the model picker whenever this screen re-enters composition,
    // so providers added in settings appear without restarting the app.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadModelOptions() }

    val scrollTarget = state.entries.size + if (state.sending) 1 else 0
    LaunchedEffect(scrollTarget) {
        if (scrollTarget > 0) listState.animateScrollToItem(scrollTarget - 1)
    }

    // Follow the live reply: each streaming delta grows the text, so scroll
    // to the newest line whenever the stream advances — but only when the
    // user is already at the tail, never yanking them out of history.
    LaunchedEffect(state.streamingText, state.streamingReasoning) {
        val hasLive = state.sending &&
            (!state.streamingText.isNullOrEmpty() || !state.streamingReasoning.isNullOrEmpty())
        if (!hasLive) return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return@LaunchedEffect
        val atTail = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            ?.let { it.index >= total - 1 } ?: true
        if (!atTail) return@LaunchedEffect
        listState.scrollToItem(total - 1)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.chat_title)) },
                    actions = {
                        TextButton(onClick = { viewModel.newSession() }) {
                            Text(stringResource(R.string.chat_new))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.chat_settings),
                            )
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            ChatInputBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                sendEnabled = draft.isNotBlank() && !state.sending,
                activeModel = state.activeModel,
                modelOptions = state.modelOptions,
                onSelectModel = viewModel::selectModel,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    ChatEntryItem(entry)
                }
                if (state.sending) {
                    item(key = "streaming") {
                        StreamingAssistantBlock(
                            reasoning = state.streamingReasoning.orEmpty(),
                            text = state.streamingText.orEmpty(),
                        )
                    }
                }
            }
            val waitingForFirstToken = state.sending &&
                state.streamingReasoning.isNullOrEmpty() &&
                state.streamingText.isNullOrEmpty()
            if (waitingForFirstToken) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ThreeDotPulse()
                }
            }
            state.error?.let { message ->
                Text(
                    stringResource(R.string.chat_error, message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
        }
    }
}

/**
 * Centered waiting animation shown while the provider has not delivered its
 * first token yet: three pulsing dots in the secondary color — visible,
 * distinct from the stream itself, and independent of the message list.
 */
@Composable
private fun ThreeDotPulse() {
    val transition = rememberInfiniteTransition(label = "waiting")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 140),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .size(16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = alpha),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun ChatEntryItem(entry: ChatEntry) {
    if (entry.fromUser) {
        MessageBlock(fromUser = true, content = entry.content, timeMs = entry.timeMs)
    } else {
        Column(Modifier.fillMaxWidth()) {
            entry.reasoning?.let { reasoning ->
                CollapsibleReasoning(reasoning = reasoning)
            }
            MessageBlock(fromUser = false, content = entry.content, timeMs = entry.timeMs)
        }
    }
}

/**
 * The thinking block. It arrives already finished (the assistant entry is
 * added when the turn completes), so it starts collapsed; tapping the
 * header expands or re-folds the full reasoning text.
 */
@Composable
private fun CollapsibleReasoning(reasoning: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            stringResource(R.string.chat_reasoning_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
    if (expanded) {
        Text(
            reasoning,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The live assistant output while the provider streams. Thinking deltas
 * render above the final content. Before the first delta arrives a small
 * waiting animation shows, so a slow first token never looks stuck.
 */
@Composable
private fun StreamingAssistantBlock(reasoning: String, text: String) {
    // Waiting is a centered overlay, not a message-list row: before the
    // first token nothing renders here so the page-level animation shows.
    if (reasoning.isEmpty() && text.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        if (reasoning.isNotEmpty()) {
            Text(
                reasoning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    activeModel: String?,
    modelOptions: List<ModelOption>,
    onSelectModel: (ModelOption) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // navigationBarsPadding keeps the bar above the gesture area;
            // imePadding keeps it above the keyboard. Inset consumers take
            // the max of both — they never stack, so the bar never floats.
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        // 12dp: a light rounding, not a pill; the box keeps a clear edge.
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // Bottom padding is a hairline only, so the action row hugs the box's
        // bottom edge; a small top inset keeps the text just off the top edge
        // without dropping it toward the middle of the taller area.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)) {
            // Taller text area; the box itself spans the full width. The text
            // stays top-aligned with a hair of offset — centering it in the
            // taller area pushed it too far down.
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 69.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Box(Modifier.padding(top = 4.dp)) { inner() }
                    }
                },
            )
            // Bottom row inside the box, right side: model selector then send.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.clickable { menuOpen = true },
                    ) {
                        Text(
                            activeModel ?: "选择模型",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.secondary
                            else InkBlack,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("${option.providerName} · ${option.model}") },
                                onClick = {
                                    onSelectModel(option)
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                // Send mirrors the model chip exactly (same radius, type scale
                // and padding) so the two sit flush on one baseline; the only
                // difference is the fill, which is pure white when enabled so
                // it pops against the warm surface, and greys out when not.
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (sendEnabled) Color.White
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(enabled = sendEnabled) { onSend() },
                ) {
                    Text(
                        stringResource(R.string.chat_send),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBlock(fromUser: Boolean, content: String, timeMs: Long) {
    // No bubble, but sides are kept distinct: the user reads right-aligned,
    // the model left-aligned. textAlign needs the full row width.
    Column(Modifier.fillMaxWidth()) {
        Text(
            content,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = if (fromUser) TextAlign.End else TextAlign.Start,
            color = if (fromUser) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            DateFormat.getTimeInstance().format(Date(timeMs)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = if (fromUser) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}
