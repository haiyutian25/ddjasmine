package jasmine.sample.example.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lhzkml.jasmine.core.plugin.component.PluginReceiver
import jasmine.sample.example.receiver.NotificationUtil.BOOT_CHANNEL_ID
import jasmine.sample.example.receiver.NotificationUtil.BOOT_NOTIFICATION_ID

/** 静态广播接收器，经宿主中心化代理分发。 */
class StaticReceiver : PluginReceiver {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val message = when (action) {
            BroadcastLog.STATIC_ACTION -> "收到自定义静态广播 - ${intent.getStringExtra("data")}"
            Intent.ACTION_BOOT_COMPLETED -> {
                sendBootNotification(context)
                "设备已开机完成，已发送通知"
            }
            Intent.ACTION_USER_PRESENT -> "用户已解锁屏幕"
            BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            ) {
                BluetoothAdapter.STATE_ON -> "蓝牙已开启"
                BluetoothAdapter.STATE_OFF -> "蓝牙已关闭"
                else -> "蓝牙状态变化中..."
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                if (intent.getBooleanExtra("state", false)) "飞行模式已开启" else "飞行模式已关闭"
            Intent.ACTION_HEADSET_PLUG ->
                if (intent.getIntExtra("state", 0) == 1) "耳机已插入" else "耳机已拔出"
            Intent.ACTION_LOCALE_CHANGED -> "系统语言已变更"
            Intent.ACTION_TIMEZONE_CHANGED -> "系统时区已变更: ${intent.getStringExtra("time-zone")}"
            Intent.ACTION_DATE_CHANGED -> "系统日期已变更"
            Intent.ACTION_TIME_CHANGED -> "系统时间已被设置"
            Intent.ACTION_PACKAGE_ADDED -> "安装了新应用: ${intent.data?.schemeSpecificPart}"
            Intent.ACTION_PACKAGE_REPLACED -> "应用已更新: ${intent.data?.schemeSpecificPart}"
            Intent.ACTION_PACKAGE_REMOVED -> "应用已卸载: ${intent.data?.schemeSpecificPart}"
            else -> "收到未处理的白名单广播"
        }
        Log.d("StaticReceiver", "收到广播: $action")
        BroadcastLog.add(source = "静态接收器", action = action, data = message)
    }

    private fun sendBootNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("插件框架已启动")
            .setContentText("点击以返回应用。")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(BOOT_NOTIFICATION_ID, notification)
    }
}
