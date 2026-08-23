package jasmine.sample.example.screen

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import jasmine.sample.example.common.update.PluginVersionInfo
import jasmine.sample.example.common.update.RemotePlugin
import jasmine.sample.example.state.PluginUpdateState
import jasmine.sample.example.viewmodel.PluginUpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginHotUpdateScreen() {
    val context = LocalContext.current
    val viewModel: PluginUpdateViewModel = viewModel {
        PluginUpdateViewModel(context.applicationContext as Application)
    }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("插件热更新", fontWeight = FontWeight.Bold) }) }) { paddingValues ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { InfoCard() }
                items(uiState.remotePlugins, key = { it.id }) { plugin ->
                    PluginUpdateCard(
                        plugin = plugin,
                        uiState = uiState,
                        onDownload = { version -> viewModel.downloadAndInstallPlugin(plugin.id, plugin.name, version) },
                    )
                }
            }
        }
        if (uiState.showInstallSuccessDialog && uiState.restartRequired) {
            val installedPlugin = uiState.recentlyInstalledPluginId
            if (installedPlugin != null) {
                InstallSuccessDialog(
                    pluginName = installedPlugin,
                    onConfirm = { viewModel.restartApp() },
                    onDismiss = { viewModel.dismissRestartDialog() },
                )
            }
        }
    }
}

@Composable
private fun InfoCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Info, contentDescription = "Info", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(16.dp))
            Text("选择插件版本进行动态更新或安装。更新将覆盖现有版本。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun PluginUpdateCard(plugin: RemotePlugin, uiState: PluginUpdateState, onDownload: (PluginVersionInfo) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val installedVersion = uiState.installedPlugins[plugin.id]
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { expanded = !expanded }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("ID: ${plugin.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(plugin.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(16.dp))
                    val rotationAngle by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "rotation")
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Expand", modifier = Modifier.rotate(rotationAngle))
                }
                if (installedVersion != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text("已安装 v$installedVersion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    plugin.versions.sortedByDescending { it.version }.forEach { version ->
                        VersionItem(
                            pluginId = plugin.id, versionInfo = version, uiState = uiState,
                            isInstalled = version.version == installedVersion, onDownload = { onDownload(version) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionItem(pluginId: String, versionInfo: PluginVersionInfo, uiState: PluginUpdateState, isInstalled: Boolean, onDownload: () -> Unit) {
    val downloadIdentifier = "$pluginId-${versionInfo.version}"
    val downloadProgress = uiState.downloadingPlugins[downloadIdentifier]
    val isInstalling = uiState.installingPlugins.contains(downloadIdentifier)
    ListItem(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        headlineContent = { Text("版本 ${versionInfo.version}", fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                Text("发布于: ${versionInfo.releaseDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                versionInfo.changelog.forEach { log -> Text("• $log", style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(80.dp).height(40.dp)) {
                when {
                    downloadProgress != null -> {
                        val animatedProgress by animateFloatAsState(targetValue = downloadProgress, label = "progress")
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(progress = { animatedProgress })
                            Text("${(animatedProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    isInstalling -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text("安装中", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    isInstalled -> Text("已安装", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    else -> Button(onClick = onDownload) { Text("获取") }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Unspecified),
    )
}

@Composable
fun InstallSuccessDialog(pluginName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新完成") },
        text = { Text("插件 '$pluginName' 已热更新。为确保稳定性并避免潜在的内存冲突，建议重启应用。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("立即重启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不重启") } },
    )
}
