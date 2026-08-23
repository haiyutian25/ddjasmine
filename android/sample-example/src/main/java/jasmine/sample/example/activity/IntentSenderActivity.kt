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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lhzkml.jasmine.core.plugin.component.BasePluginActivity
import com.lhzkml.jasmine.core.plugin.proxy.startPluginActivity

/** 通过 Intent 向另一个插件 Activity 传递数据。 */
class IntentSenderActivity : BasePluginActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proxy?.setContent {
            IntentSenderScreen(
                onSendData = { str, int, bool ->
                    proxy?.startPluginActivity(IntentReceiverActivity::class.java) {
                        putExtra("EXTRA_STRING", str)
                        putExtra("EXTRA_INT", int)
                        putExtra("EXTRA_BOOLEAN", bool)
                    }
                },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun IntentSenderScreen(onSendData: (String, Int, Boolean) -> Unit) {
        val context = LocalContext.current
        var textState by remember { mutableStateOf("来自插件的消息") }
        var numberState by remember { mutableStateOf("2025") }
        var switchState by remember { mutableStateOf(true) }

        Scaffold(topBar = { TopAppBar(title = { Text("Intent 数据发送", fontWeight = FontWeight.Bold) }) }) { paddingValues ->
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "请在下方输入或修改要传递的数据，然后点击发送按钮。",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
                OutlinedTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    label = { Text("要发送的字符串") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = numberState,
                    onValueChange = { numberState = it },
                    label = { Text("要发送的整数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("要发送的布尔值")
                    Switch(checked = switchState, onCheckedChange = { switchState = it })
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    onClick = {
                        val number = numberState.toIntOrNull()
                        if (number == null) {
                            Toast.makeText(context, "请输入一个有效的整数！", Toast.LENGTH_SHORT).show()
                        } else {
                            onSendData(textState, number, switchState)
                        }
                    },
                ) { Text("发送数据到下一页") }
            }
        }
    }
}
