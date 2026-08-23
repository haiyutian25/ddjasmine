package com.lhzkml.jasmine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lhzkml.jasmine.core.plugin.PluginHost

/**
 * Settings: the container every management page (models, providers, MCP
 * servers, permissions) will live in. Plugin menu entries declared by
 * loaded plugins appear dynamically below the plugin manager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenPlugins: () -> Unit = {},
    onOpenProviderSettings: () -> Unit = {},
    onOpenPluginContent: (String) -> Unit = {},
) {
    val menuEntries by PluginHost.loadedMenuEntries.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onOpenProviderSettings)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("模型供应商", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onOpenPlugins)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("插件管理", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            // 插件激活后动态出现的菜单入口
            items(menuEntries.entries.toList(), key = { it.key }) { (pluginId, entry) ->
                Card(Modifier.fillMaxWidth().clickable(onClick = { onOpenPluginContent(pluginId) })) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            entry.subtitle?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
            items(SECTIONS) { (title, subtitle) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

private val SECTIONS = listOf(
    "MCP 服务器" to "添加与启停 MCP 服务器、查看工具（即将推出）",
    "权限预设" to "read-only / workspace-write / danger-full-access（即将推出）",
    "遥测" to "开关与上报地址（即将推出）",
)
