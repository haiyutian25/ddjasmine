package com.lhzkml.jasmine.feature.plugin.ui

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 插件管理（在线管理）：列出运行时里已安装的动态插件，支持启用开关、
 * 启动/卸载、检查更新、卸载、安装内置插件。所有裁决都在 Rust 决策核心。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreen(
    modifier: Modifier = Modifier,
    viewModel: PluginViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("插件管理") },
                actions = {
                    OutlinedButton(onClick = { viewModel.installBundled() }) {
                        Text("安装内置")
                    }
                    OutlinedButton(onClick = { viewModel.refresh() }) {
                        Text("刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.plugins.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                state.plugins.isEmpty() -> Text(
                    "还没有安装插件。点右上角「安装内置」安装随包分发的插件。",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.plugins, key = { it.record.pluginId }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onToggle = { enabled -> viewModel.setEnabled(plugin.record.pluginId, enabled) },
                            onToggleRun = { viewModel.toggleRun(plugin.record.pluginId) },
                            onUpdate = { viewModel.checkUpdate(plugin.record.pluginId) },
                            onUninstall = { viewModel.uninstall(plugin.record.pluginId) },
                        )
                    }
                }
            }
            state.message?.let {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) { Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginUi,
    onToggle: (Boolean) -> Unit,
    onToggleRun: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    val record = plugin.record
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(record.name, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            record.pluginId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            "v${record.versionName}(${record.versionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Switch(checked = record.enabled, onCheckedChange = onToggle)
            }
            record.description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "入口 ${record.entryClass} · ${if (plugin.loaded) "已加载" else "未加载"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (plugin.loaded) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.outline,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleRun) {
                    Text(if (plugin.loaded) "卸载" else "启动")
                }
                OutlinedButton(onClick = onUpdate, enabled = !plugin.updating) {
                    Text(if (plugin.updating) "检查中…" else "检查更新")
                }
                OutlinedButton(onClick = onUninstall) {
                    Text("卸载")
                }
            }
        }
    }
}
