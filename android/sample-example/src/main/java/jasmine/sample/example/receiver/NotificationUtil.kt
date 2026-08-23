package jasmine.sample.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationUtil {
    const val BOOT_CHANNEL_ID = "boot_completed_channel"
    const val BOOT_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val bootChannel = NotificationChannel(
                BOOT_CHANNEL_ID,
                "开机与启动提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "用于接收应用启动和开机完成的通知" }
            manager.createNotificationChannel(bootChannel)
        }
    }
}
