package jasmine.sample.guide.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 核心特性卡片。
 */
@Composable
fun CoreFeaturesCard() {
    val pillars = mapOf(
        "现代化的设计" to listOf(
            FeatureItem(
                Icons.Rounded.Done,
                "原生为 Compose 而生",
                "为新一代 UI 工具包设计，插件可无缝使用 @Composable 函数构建界面。",
            ),
            FeatureItem(
                Icons.Rounded.Done,
                "拥抱主流技术栈",
                "集成 Kotlin Coroutines，并以 ServiceKey 服务注册表替代容器注入。",
            ),
        ),
        "极致的稳定与兼容" to listOf(
            FeatureItem(
                Icons.Rounded.Done,
                "0 Hook，原则上 0 反射",
                "完全基于官方 API，仅在兼容旧版系统时采纳标准反射方案，确保极致稳定。",
            ),
            FeatureItem(
                Icons.Rounded.Done,
                "崩溃熔断与自愈",
                "内置崩溃处理器，归因与分类下沉 Rust，自动禁用问题插件并优雅降级。",
            ),
        ),
        "终极的灵活与解耦" to listOf(
            FeatureItem(
                Icons.Rounded.Done,
                "去中心化架构",
                "打破传统宿主-插件模式，任何插件都可管理其他插件，架构更灵活。",
            ),
            FeatureItem(
                Icons.Rounded.Done,
                "空壳宿主支持",
                "宿主可无任何业务逻辑，所有功能、UI 均由插件动态提供。",
            ),
        ),
        "卓越的开发者体验" to listOf(
            FeatureItem(
                Icons.Rounded.Done,
                "微乎其微的侵入性",
                "通过基类和数行配置即可完成集成，无需改动项目原有结构。",
            ),
            FeatureItem(
                Icons.Rounded.Done,
                "闪电般的类查找",
                "通过全局类索引实现 O(1) 跨插件类查找，性能卓越。",
            ),
        ),
    )

    GuideSectionCard(
        title = "核心理念与优势",
        icon = Icons.Rounded.Build,
        iconTint = MaterialTheme.colorScheme.secondary,
    ) {
        pillars.entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(20.dp))
            }
            Text(
                text = entry.key,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            entry.value.forEach { feature ->
                FeatureRow(feature)
                if (feature != entry.value.last()) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}
