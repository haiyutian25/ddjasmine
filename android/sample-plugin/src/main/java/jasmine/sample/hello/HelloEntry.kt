package jasmine.sample.hello

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
import com.lhzkml.jasmine.core.plugin.PluginMenuEntry
import com.lhzkml.jasmine.core.plugin.ServiceKey
import com.lhzkml.jasmine.core.plugin.ServiceTable
import com.lhzkml.jasmine.core.plugin.component.BasePluginActivity
import com.lhzkml.jasmine.core.plugin.component.PluginReceiver

/** Cross-plugin service published by this sample. */
fun interface Greeter {
    fun greet(): String

    companion object {
        val KEY = ServiceKey<Greeter>("jasmine.sample.hello.Greeter")
    }
}

/** Entry class named by the `jasmine.plugin.entryClass` manifest meta-data. */
class HelloEntry : PluginEntry {

    override val services: ServiceTable = mapOf(
        Greeter.KEY to Greeter { "来自 Hello 插件的问候" },
    )

    /** 激活后动态出现在宿主设置列表的菜单入口。 */
    override val menuEntry: PluginMenuEntry = PluginMenuEntry(
        title = "Hello 示例插件",
        subtitle = "演示插件主界面（Compose）",
    )

    override fun onLoad(context: PluginContext) {
        Log.i(TAG, "onLoad: dir=${context.pluginDir}")
        // Prove the injected per-plugin resources resolve.
        val label = runCatching {
            context.resources.getString(context.resources.getIdentifier("app_name", "string", "jasmine.sample.hello"))
        }.getOrDefault("(no resources)")
        Log.i(TAG, "plugin resources: app_name=$label")
    }

    override fun onUnload() {
        Log.i(TAG, "onUnload")
    }

    /** 点击菜单入口后由宿主渲染的插件主界面。 */
    @Composable
    override fun Content() {
        val context = LocalContext.current
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Hello 插件主界面", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "这个界面由插件自己的 Content() 提供，宿主设置列表点击后动态进入",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                Toast.makeText(context, "来自插件的按钮", Toast.LENGTH_SHORT).show()
            }) {
                Text("点击我")
            }
        }
    }

    private companion object {
        const val TAG = "HelloPlugin"
    }
}

/** Static receiver dispatched via the host's central proxy. */
class PingReceiver : PluginReceiver {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("HelloPlugin", "PING received: ${intent.action}")
        Toast.makeText(context, "Hello 插件收到 PING", Toast.LENGTH_SHORT).show()
    }
}

/** Plugin activity fronted by the host's single proxy activity. */
class HelloActivity : BasePluginActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        proxy?.setContentView(
            android.widget.TextView(proxy).apply {
                text = "Hello 插件界面（由宿主代理承载）"
                textSize = 18f
                setPadding(48, 96, 48, 48)
            },
        )
    }
}
