package jasmine.sample.guide.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * 技术架构卡片。
 */
@Composable
fun ArchitectureCard() {
    GuideSectionCard(
        title = "架构概览",
        icon = Icons.Rounded.Settings,
        iconTint = MaterialTheme.colorScheme.primary,
    ) {
        val archText = buildAnnotatedString {
            val points = mapOf(
                "Rust 决策核心:" to " 注册表(ledger)、依赖拓扑(topology)、权限章程(charter)、组件分发(dispatch)，经 UniFFI 跨边界。\n",
                "PluginHost:" to " 框架中心门面，负责插件的安装、卸载、重启和生命周期编排。\n",
                "InstallExecutor:" to " 负责插件的安装、更新与合法性校验（裁决由 Rust 完成）。\n",
                "PluginResourcesLoader:" to " 负责插件资源加载，兼容新旧 Android 版本。\n",
                "代理组件:" to " 负责 Android 四大组件的代理与生命周期分发。\n",
                "PluginClassLoader:" to " 负责跨插件类查找与依赖委托（索引由 Rust 维护）。",
            )
            points.forEach { (keyword, description) ->
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    append("• ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(keyword)
                    }
                }
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(description)
                }
            }
        }
        Text(archText, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
    }
}
