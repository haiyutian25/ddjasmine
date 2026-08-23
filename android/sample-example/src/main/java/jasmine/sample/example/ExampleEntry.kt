package jasmine.sample.example

import androidx.compose.runtime.Composable
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
import com.lhzkml.jasmine.core.plugin.PluginMenuEntry
import jasmine.sample.example.receiver.NotificationUtil
import jasmine.sample.example.screen.ExampleMainScreen

/**
 * 功能示例插件的入口类，由 `jasmine.plugin.entryClass` 元数据声明。
 */
class ExampleEntry : PluginEntry {

    override val menuEntry: PluginMenuEntry = PluginMenuEntry(
        title = "功能示例",
        subtitle = "Activity/Service/广播/ContentProvider/native so/热更新",
    )

    override fun onLoad(context: PluginContext) {
        NotificationUtil.createChannels(context.application)
    }

    override fun onUnload() {
    }

    @Composable
    override fun MainScreen() {
        ExampleMainScreen()
    }
}
