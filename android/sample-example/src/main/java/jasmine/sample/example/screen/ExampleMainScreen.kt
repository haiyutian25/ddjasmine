package jasmine.sample.example.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jasmine.sample.example.component.ExampleItemGridCard

/** 示例项目。 */
data class ExampleItem(
    val title: String,
    val description: String,
    val onClick: () -> Unit = {},
)

private sealed interface ExampleScreen {
    data object Main : ExampleScreen
    data object Activity : ExampleScreen
    data object Service : ExampleScreen
    data object Broadcast : ExampleScreen
    data object ContentProvider : ExampleScreen
    data object SoLibrary : ExampleScreen
    data object HotUpdate : ExampleScreen
}

/**
 * 功能示例插件主界面：网格列出各类 Android 组件示例。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExampleMainScreen() {
    var screen by remember { mutableStateOf<ExampleScreen>(ExampleScreen.Main) }
    BackHandler(enabled = screen != ExampleScreen.Main) { screen = ExampleScreen.Main }

    when (screen) {
        ExampleScreen.Main -> ExampleGrid(
            onNavigate = { screen = it },
        )
        ExampleScreen.Activity -> ActivityScreen()
        ExampleScreen.Service -> ServiceScreen()
        ExampleScreen.Broadcast -> BroadcastReceiverScreen()
        ExampleScreen.ContentProvider -> ContentProviderScreen()
        ExampleScreen.SoLibrary -> SoLibraryScreen()
        ExampleScreen.HotUpdate -> PluginHotUpdateScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExampleGrid(onNavigate: (ExampleScreen) -> Unit) {
    val exampleItems = listOf(
        ExampleItem("Activity 示例", "生命周期、启动模式、Intent 传递等") {
            onNavigate(ExampleScreen.Activity)
        },
        ExampleItem("Service 示例", "前后台服务、绑定服务等不同类型") {
            onNavigate(ExampleScreen.Service)
        },
        ExampleItem("广播接收器", "系统广播、自定义广播的发送与接收") {
            onNavigate(ExampleScreen.Broadcast)
        },
        ExampleItem("内容提供者", "数据共享、权限控制、CRUD 操作") {
            onNavigate(ExampleScreen.ContentProvider)
        },
        ExampleItem("SO 库加载", "JNI 调用、动态库加载、native 方法") {
            onNavigate(ExampleScreen.SoLibrary)
        },
        ExampleItem("插件热更新", "无需重启应用，动态更新插件的代码和资源") {
            onNavigate(ExampleScreen.HotUpdate)
        },
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("插件示例集合", fontWeight = FontWeight.Bold) }) },
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Text(
                        text = "探索 Android 开发的各个方面，从基础组件到高级功能，每个示例都包含详细的代码演示和说明。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(exampleItems) { item -> ExampleItemGridCard(item = item) }
        }
    }
}
