package jasmine.sample.example.activity

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lhzkml.jasmine.core.plugin.component.BasePluginActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 监听并展示 Activity 完整生命周期事件。 */
class LifecycleActivity : BasePluginActivity() {

    private val lifecycleEvents = mutableStateListOf<String>()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private fun addEvent(event: String) {
        val logMessage = "[${timeFormatter.format(Date())}] $event"
        lifecycleEvents.add(0, logMessage)
        Log.d("LifecycleActivity", logMessage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addEvent("onCreate")
        proxy?.setContent { LifecycleScreen(lifecycleEvents) }
    }

    override fun onStart() { super.onStart(); addEvent("onStart") }
    override fun onResume() { super.onResume(); addEvent("onResume") }
    override fun onPause() { super.onPause(); addEvent("onPause") }
    override fun onStop() { super.onStop(); addEvent("onStop") }
    override fun onDestroy() { super.onDestroy(); addEvent("onDestroy") }
    override fun onRestart() { super.onRestart(); addEvent("onRestart") }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LifecycleScreen(events: List<String>) {
        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        Scaffold(
            topBar = { TopAppBar(title = { Text("生命周期监听", fontWeight = FontWeight.Bold) }) },
        ) { paddingValues ->
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            ) {
                Text(
                    "观察下方的日志输出。可以尝试按 Home 键、返回键或旋转屏幕来触发不同的生命周期事件。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                LaunchedEffect(events) {
                    coroutineScope.launch {
                        if (events.isNotEmpty()) listState.animateScrollToItem(0)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .background(Color.LightGray.copy(alpha = 0.2f)),
                ) {
                    items(events) { event ->
                        Text(
                            text = event,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
