package jasmine.sample.example.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lhzkml.jasmine.core.plugin.component.BasePluginActivity

/** 纯 Jetpack Compose 构建的插件 Activity，覆盖多种 Material3 组件。 */
class ComposeActivity : BasePluginActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proxy?.setContent { ComposeContent() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ComposeContent() {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Compose 页面", fontWeight = FontWeight.Bold) }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { toast("点击了悬浮按钮") }) {
                    Icon(Icons.Filled.Favorite, contentDescription = "喜欢")
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Section("输入框 OutlinedTextField") {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("请输入内容") },
                        placeholder = { Text("这里输入文字") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("输入内容: $text", fontSize = 13.sp)
                }

                Section("按钮 Button 系列") {
                    var count by remember { mutableStateOf(0) }
                    Button(onClick = { count++; toast("Button: 点击 $count 次") }) { Text("Button") }
                    ElevatedButton(onClick = { toast("ElevatedButton 点击") }) { Text("ElevatedButton") }
                    FilledTonalButton(onClick = { toast("FilledTonalButton 点击") }) { Text("FilledTonalButton") }
                    OutlinedButton(onClick = { toast("OutlinedButton 点击") }) { Text("OutlinedButton") }
                    TextButton(onClick = { toast("TextButton 点击") }) { Text("TextButton") }
                }

                Section("开关 / 复选框 / 单选") {
                    var switchOn by remember { mutableStateOf(true) }
                    var checked by remember { mutableStateOf(false) }
                    var selected by remember { mutableStateOf("A") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = switchOn, onCheckedChange = { switchOn = it })
                        Text("开关: ${if (switchOn) "开" else "关"}", modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text("复选框: $checked", modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == "A", onClick = { selected = "A" })
                        Text("选项A", modifier = Modifier.padding(start = 4.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        RadioButton(selected = selected == "B", onClick = { selected = "B" })
                        Text("选项B", modifier = Modifier.padding(start = 4.dp))
                    }
                    Text("单选选中: $selected", fontSize = 13.sp)
                }

                Section("滑块 Slider") {
                    var sliderValue by remember { mutableFloatStateOf(50f) }
                    Slider(value = sliderValue, onValueChange = { sliderValue = it })
                    Text("滑块值: ${sliderValue.toInt()}%", fontSize = 13.sp)
                }

                Section("进度条 ProgressIndicator") {
                    LinearProgressIndicator(
                        progress = { 0.6f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }

                Section("标签 Chip 系列") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { toast("AssistChip 点击") }, label = { Text("Assist") })
                        FilterChip(
                            selected = true,
                            onClick = { toast("FilterChip 点击") },
                            label = { Text("Filter") },
                        )
                    }
                }

                Section("图标 Icon / 徽章 Badge") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Home, contentDescription = "首页")
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                        Icon(Icons.Filled.Star, contentDescription = "星标")
                        Icon(Icons.Filled.Notifications, contentDescription = "通知")
                        Icon(Icons.Filled.Person, contentDescription = "用户")
                        BadgedBox(
                            badge = { Badge { Text("3") } },
                        ) {
                            Icon(Icons.Filled.Notifications, contentDescription = "带徽章的通知")
                        }
                    }
                    IconButton(onClick = { toast("IconButton 点击") }) {
                        Icon(Icons.Filled.Favorite, contentDescription = "喜欢")
                    }
                }

                Section("卡片 Card") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("这是一张卡片", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("卡片内可以放置任意内容，用于分组展示信息。", fontSize = 13.sp)
                        }
                    }
                }

                HorizontalDivider()
                Text("🚀 滚动到底部了", modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            content()
        }
        HorizontalDivider()
    }

    private fun toast(message: String) {
        Toast.makeText(proxy, message, Toast.LENGTH_SHORT).show()
    }
}
