package jasmine.sample.example.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lhzkml.jasmine.core.plugin.component.BasePluginActivity

/** 纯 Jetpack Compose 构建的插件 Activity。 */
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
        ) { paddingValues ->
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("🚀", fontSize = 96.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Hello Compose", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = {
                    Toast.makeText(
                        proxy,
                        "这是一个由插件Activity加载的 Compose 页面",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Click me") }
            }
        }
    }
}
