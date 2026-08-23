package jasmine.sample.guide

import androidx.compose.runtime.Composable
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
import com.lhzkml.jasmine.core.plugin.PluginMenuEntry

/**
 * 开发指南插件的入口类，由 `jasmine.plugin.entryClass` 元数据声明。
 * 激活后在宿主设置列表动态出现菜单入口，点击渲染 [MainScreen]。
 */
class GuideEntry : PluginEntry {

    override val menuEntry: PluginMenuEntry = PluginMenuEntry(
        title = "开发指南",
        subtitle = "Jasmine 插件化框架使用向导",
    )

    override fun onLoad(context: PluginContext) {
        // 无初始化需求
    }

    override fun onUnload() {
        // 无资源需释放
    }

    @Composable
    override fun MainScreen() {
        GuideMainScreen()
    }
}
