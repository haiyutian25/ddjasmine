package jasmine.sample.guide.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 框架介绍卡片。
 */
@Composable
fun IntroductionCard() {
    GuideSectionCard(
        title = "框架介绍",
        icon = Icons.Rounded.Info,
        iconTint = MaterialTheme.colorScheme.primary,
    ) {
        val tags = listOf(
            "Platform-Android", "API-24+", "Kotlin", "Jetpack Compose",
            "Rust 决策核心", "FFI (UniFFI)", "Apache 2.0",
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tags.forEach { tag ->
                AssistChip(onClick = {}, label = { Text(tag, fontSize = 12.sp) })
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(
            text = "随着 Android 生态的不断演进，众多诞生于 View 时代的经典插件化框架在如今的开发场景中已显得力不从心。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Jasmine 插件框架从源头上抛弃了充满风险的非公开 API 反射调用，以公开 API 为基石实现 0 Hook 的纯净架构，" +
                "原生为 Jetpack Compose 设计，并把可并发的决策逻辑（注册表、依赖拓扑、权限章程、组件分发）下沉到 Rust，" +
                "通过 UniFFI 与宿主交互，开创性地引入去中心化的管理哲学。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp,
        )
    }
}
