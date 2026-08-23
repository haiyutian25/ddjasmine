package jasmine.sample.example.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lhzkml.jasmine.core.plugin.proxy.startPluginActivity
import jasmine.sample.example.activity.ComposeActivity
import jasmine.sample.example.activity.IntentSenderActivity
import jasmine.sample.example.activity.LifecycleActivity
import jasmine.sample.example.activity.XmlActivity
import jasmine.sample.example.component.JumpButton

/** Activity 示例展示页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen() {
    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("Activity示例", fontWeight = FontWeight.Bold) }) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JumpButton(
                text = "Compose 函数示例",
                description = "演示纯 Jetpack Compose 构建的插件Activity页面",
            ) { context.startPluginActivity(ComposeActivity::class.java) }
            JumpButton(
                text = "XML UI 布局示例",
                description = "演示使用传统 XML 布局的插件Activity页面",
            ) { context.startPluginActivity(XmlActivity::class.java) }
            JumpButton(
                text = "生命周期示例",
                description = "监听并展示 Activity 的完整生命周期事件",
            ) { context.startPluginActivity(LifecycleActivity::class.java) }
            JumpButton(
                text = "Intent 传递示例",
                description = "演示如何在插件 Activity 之间传递数据",
            ) { context.startPluginActivity(IntentSenderActivity::class.java) }
        }
    }
}
