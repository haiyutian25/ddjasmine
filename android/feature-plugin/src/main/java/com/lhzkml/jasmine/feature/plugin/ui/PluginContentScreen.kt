package com.lhzkml.jasmine.feature.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lhzkml.jasmine.core.plugin.PluginHost

/**
 * 通用插件内容屏：渲染某个已加载插件的 `Content()`。插件未加载（例如
 * 卸载后仍停留在该页）时给出兜底提示。
 */
@Composable
fun PluginContentScreen(
    pluginId: String,
    modifier: Modifier = Modifier,
) {
    val entry = PluginHost.entryOf(pluginId)
    if (entry == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "插件未加载: $pluginId",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    } else {
        entry.Content()
    }
}
