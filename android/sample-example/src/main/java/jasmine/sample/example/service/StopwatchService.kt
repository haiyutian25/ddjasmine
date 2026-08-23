package jasmine.sample.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service.START_NOT_STICKY
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lhzkml.jasmine.core.plugin.component.BasePluginService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 前台秒表服务，演示服务代理池多实例。 */
class StopwatchService : BasePluginService() {

    companion object {
        private const val CHANNEL_ID = "StopwatchServiceChannel"
        const val ACTION_SERVICE_STARTED = "jasmine.sample.example.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "jasmine.sample.example.SERVICE_STOPPED"
        const val EXTRA_SERVICE_ID = "extra_service_id"
    }

    private var instanceId: String? = null
    private val notificationId get() = instanceId.hashCode()
    private var creationTime = 0L
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val binder = StopwatchBinder()
    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime = _elapsedTime.asStateFlow()
    private var uiTimerJob: Job? = null
    private var notificationUpdateJob: Job? = null

    inner class StopwatchBinder : Binder() {
        fun getService(): StopwatchService = this@StopwatchService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val receivedId = intent?.getStringExtra(EXTRA_SERVICE_ID)
        if (receivedId == null) {
            proxy?.stopSelf()
            return START_NOT_STICKY
        }
        if (this.instanceId == null) {
            this.instanceId = receivedId
            this.creationTime = System.currentTimeMillis()
            startTimers()
            sendInternal(ACTION_SERVICE_STARTED, receivedId)
        }
        val notification = createNotification("计时准备中...")
        proxy?.startForeground(notificationId, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        if (this.instanceId == null) {
            val receivedId = intent?.getStringExtra(EXTRA_SERVICE_ID)
            if (receivedId != null) {
                this.instanceId = receivedId
                if (creationTime == 0L) this.creationTime = System.currentTimeMillis()
                startTimers()
            }
        }
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        instanceId?.let { sendInternal(ACTION_SERVICE_STOPPED, it) }
    }

    private fun sendInternal(action: String, serviceId: String) {
        proxy?.sendBroadcast(
            Intent(action).apply {
                setPackage(proxy?.packageName)
                putExtra(EXTRA_SERVICE_ID, serviceId)
            },
        )
    }

    private fun startTimers() {
        if (uiTimerJob?.isActive != true) {
            uiTimerJob = serviceScope.launch {
                while (isActive) {
                    _elapsedTime.value = System.currentTimeMillis() - creationTime
                }
            }
        }
        if (notificationUpdateJob?.isActive != true) {
            notificationUpdateJob = serviceScope.launch {
                while (isActive) {
                    updateNotification(formatTime(_elapsedTime.value))
                    delay(1000)
                }
            }
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("mm:ss", Locale.getDefault()).format(Date(millis))

    private fun updateNotification(time: String) {
        if (instanceId == null) return
        val manager = proxy?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(notificationId, createNotification("计时: $time"))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "秒表服务通知", NotificationManager.IMPORTANCE_LOW)
            proxy?.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        Log.d("StopwatchService", "createNotification for #$instanceId")
        return NotificationCompat.Builder(proxy!!, CHANNEL_ID)
            .setContentTitle("插件秒表服务 [#$instanceId]")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(creationTime)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .build()
    }
}
