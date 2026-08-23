package jasmine.sample.example.receiver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI 与后台 Receiver 之间共享广播日志。 */
object BroadcastLog {
    const val DYNAMIC_ACTION = "jasmine.sample.example.action.DYNAMIC_BROADCAST"
    const val STATIC_ACTION = "jasmine.sample.example.action.STATIC_BROADCAST"
    private val _logFlow = MutableSharedFlow<String>(replay = 20)
    val logFlow = _logFlow.asSharedFlow()

    fun add(source: String, action: String?, data: String?) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val message = "[$time] [$source] 收到广播\n   - Action: $action\n   - Data: ${data ?: "无"}"
        _logFlow.tryEmit(message)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clear() {
        _logFlow.resetReplayCache()
    }
}
