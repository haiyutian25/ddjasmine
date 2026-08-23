package jasmine.sample.guide.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 快速开始卡片。
 */
@Composable
fun QuickStartCard() {
    GuideSectionCard(
        title = "快速开始",
        icon = Icons.Rounded.Send,
        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
        cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        val steps = listOf(
            "1. 宿主依赖 `:core-plugin` 并继承 `PluginHostApplication`。",
            "2. 代理组件已随库 manifest 自动合并，无需手动注册。",
            "3. 初始化框架并配置签名策略与更新通道地址。",
            "4. 创建插件模块，实现 `PluginEntry` 并配置 `jasmine.plugin.entryClass` 元数据。",
            "5. 插件加载后其 `menuEntry` 动态出现在宿主设置列表，点击进入 `MainScreen`。",
        )
        steps.forEach { step ->
            CodeSnippetText(text = step, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
