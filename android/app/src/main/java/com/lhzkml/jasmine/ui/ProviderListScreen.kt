package com.lhzkml.jasmine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lhzkml.jasmine.core.data.ProviderEntry

/**
 * The model-provider list: one card per provider. Every provider is active
 * at once (no switch) — the chat model picker shows all of their models;
 * each card only offers delete, plus an add button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onOpenProvider: (String) -> Unit,
    onCreateProvider: () -> Unit,
    viewModel: ProviderListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Reload whenever this destination re-enters composition (returning from
    // the edit page), so a freshly saved provider shows immediately.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(title = { Text("模型供应商") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = onCreateProvider, modifier = Modifier.fillMaxWidth()) {
                    Text("＋ 添加供应商")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                state.providers.isEmpty() -> Text(
                    "还没有供应商，点下方“添加供应商”开始配置。",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.providers, key = { it.id }) { provider ->
                        ProviderCard(
                            provider = provider,
                            onOpen = { onOpenProvider(provider.id) },
                            onDelete = { viewModel.delete(provider.id) },
                        )
                    }
                }
            }
            state.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${provider.protocol.name} · ${provider.apiAddress}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "${provider.models.size} 个模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
