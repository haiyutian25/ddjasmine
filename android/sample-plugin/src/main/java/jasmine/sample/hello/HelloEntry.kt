package jasmine.sample.hello

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
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
