package jasmine.sample.example.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lhzkml.jasmine.core.plugin.component.BasePluginActivity

/** 从 Intent 中提取并展示传递过来的数据。 */
class IntentReceiverActivity : BasePluginActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receivedString = proxy?.intent?.getStringExtra("EXTRA_STRING") ?: "未收到字符串"
        val receivedInt = proxy?.intent?.getIntExtra("EXTRA_INT", -1) ?: -1
        val receivedBoolean = proxy?.intent?.getBooleanExtra("EXTRA_BOOLEAN", false) ?: false
        proxy?.setContent {
            IntentReceiverScreen(receivedString, receivedInt, receivedBoolean)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun IntentReceiverScreen(strValue: String, intValue: Int, boolValue: Boolean) {
        Scaffold(topBar = { TopAppBar(title = { Text("Intent 数据接收", fontWeight = FontWeight.Bold) }) }) { paddingValues ->
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("已成功从 Intent 中接收到以下数据：", style = MaterialTheme.typography.titleMedium)
                InfoRow("字符串 (EXTRA_STRING):", strValue)
                InfoRow("整数 (EXTRA_INT):", intValue.toString())
                InfoRow("布尔值 (EXTRA_BOOLEAN):", boolValue.toString())
            }
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
