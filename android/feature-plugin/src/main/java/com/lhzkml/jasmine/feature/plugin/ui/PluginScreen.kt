package com.lhzkml.jasmine.feature.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lhzkml.jasmine.core.data.PluginRow
import com.lhzkml.jasmine.core.data.PluginRuntimeState
import com.lhzkml.jasmine.feature.plugin.R

/**
 * The plugin manager: every composed plugin row with its enable switch.
 * Toggling writes the home overlay and recomposes through the Rust
 * `compose` crate — the UI never holds plugin state itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(
    modifier: Modifier = Modifier,
    viewModel: PluginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val runtimeStates by viewModel.runtimeStates.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.plugin_list_title)) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.rows.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                state.rows.isEmpty() -> Text(
                    stringResource(R.string.plugin_list_empty),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        PluginRowCard(
                            row = row,
                            runtime = runtimeStates[row.id] ?: PluginRuntimeState.UNMOUNTED,
                            onToggle = { enabled -> viewModel.setDisabled(row.id, !enabled) },
                        )
                    }
                }
            }
            if (state.warnings.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.plugin_warnings_header),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        state.warnings.forEach { warning ->
                            Text(warning, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.error?.let { message ->
                Text(
                    stringResource(R.string.plugin_error, message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PluginRowCard(
    row: PluginRow,
    runtime: PluginRuntimeState,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (row.group) stringResource(R.string.plugin_group_badge) else row.id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        when (runtime) {
                            PluginRuntimeState.MOUNTED -> stringResource(R.string.plugin_runtime_mounted)
                            PluginRuntimeState.UNMOUNTED -> stringResource(R.string.plugin_runtime_unmounted)
                            PluginRuntimeState.NO_CODE -> stringResource(R.string.plugin_runtime_no_code)
                            PluginRuntimeState.FAILED -> stringResource(R.string.plugin_runtime_failed)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (runtime) {
                            PluginRuntimeState.MOUNTED -> MaterialTheme.colorScheme.secondary
                            PluginRuntimeState.UNMOUNTED -> MaterialTheme.colorScheme.tertiary
                            PluginRuntimeState.NO_CODE -> MaterialTheme.colorScheme.outline
                            PluginRuntimeState.FAILED -> MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            Switch(
                checked = !row.disabled,
                onCheckedChange = onToggle,
            )
        }
    }
}
