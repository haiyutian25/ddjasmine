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
 * 通用插件内容屏：渲染某个已加载插件的 `MainScreen()`。插件未加载（例如
 * 卸载后仍停留在该页）时给出兜底提示。
 *
 * 注：Compose 编译器禁止 try-catch 包裹 composable 调用，因此组合期异常
 * 无法在此拦截；插件的运行时崩溃由框架的崩溃熔断（CrashHook 归因 + 禁用）
 * 与进程隔离兜底，而不是在 UI 层隔离。
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
        entry.MainScreen()
    }
}
