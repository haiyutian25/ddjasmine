package com.lhzkml.jasmine.core.plugin.process

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lhzkml.jasmine.core.plugin.proxy.ProxyKeys
import com.lhzkml.jasmine.core.plugin.proxy.ServiceProxyPool

/**
 * 插件级后台任务调度（轻量档）：基于 `AlarmManager` + Service 池。
 *
 * 框架没有集成 WorkManager/JobScheduler，此调度器提供插件一个可靠的后台
 * 定时/一次性任务入口：到点后由 [PluginAlarmReceiver] 经 Service 池启动插件
 * 的 Service（插件 Service 需自行 `startForeground`，参见 StopwatchService）。
 *
 * 注意：这是"轻量档"——不具备 WorkManager 的约束（网络/充电）、重试与
 * 持久化保证；如需完整保证请接入 WorkManager（完整档）。
 */
object PluginScheduler {

    /** 一次性任务：`triggerAtMillis`（`SystemClock.elapsedRealtime` 基准）触发一次。 */
    fun scheduleOnce(
        context: Context,
        pluginId: String,
        requestCode: Int,
        triggerAtMillis: Long,
        serviceClassName: String,
        taskId: String = "scheduled-$requestCode",
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis,
            alarmIntent(context, pluginId, requestCode, serviceClassName, taskId),
        )
    }

    /** 周期任务：自 `intervalMillis` 后每隔 `intervalMillis` 触发（非精确，系统批量对齐）。 */
    fun schedulePeriodic(
        context: Context,
        pluginId: String,
        requestCode: Int,
        intervalMillis: Long,
        serviceClassName: String,
        taskId: String = "scheduled-$requestCode",
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + intervalMillis,
            intervalMillis,
            alarmIntent(context, pluginId, requestCode, serviceClassName, taskId),
        )
    }

    /** 取消一个已调度任务。 */
    fun cancel(context: Context, pluginId: String, requestCode: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, pluginId, requestCode))
    }

    private fun alarmIntent(
        context: Context,
        pluginId: String,
        requestCode: Int,
        serviceClassName: String,
        taskId: String,
    ): PendingIntent {
        val intent = Intent(context, PluginAlarmReceiver::class.java).apply {
            putExtra(PluginAlarmReceiver.EXTRA_SERVICE_CLASS, serviceClassName)
            putExtra(PluginAlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        val rc = requestCodeOf(pluginId, requestCode)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, rc, intent, flags)
    }

    private fun pendingIntent(
        context: Context,
        pluginId: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, PluginAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCodeOf(pluginId, requestCode), intent, flags)
    }

    /** 把 (pluginId, requestCode) 折叠成一个稳定的 PendingIntent requestCode。 */
    private fun requestCodeOf(pluginId: String, requestCode: Int): Int =
        ((pluginId.hashCode() and 0xFFFF) shl 16) or (requestCode and 0xFFFF)
}

/**
 * `AlarmManager` 触发的宿主接收器：根据 extras 里的插件 Service 类名，
 * 经 Service 池以前台服务方式启动插件 Service。
 */
class PluginAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceClassName = intent.getStringExtra(EXTRA_SERVICE_CLASS) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: "scheduled"
        val (instanceId, proxyClass) = ServiceProxyPool.acquire(serviceClassName, taskId)
            ?: return // 池耗尽，静默放弃（下次周期任务会再试）
        val serviceIntent = Intent(context, proxyClass).apply {
            putExtra(ProxyKeys.SERVICE_CLASS, serviceClassName)
            putExtra(ProxyKeys.SERVICE_INSTANCE_ID, instanceId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val EXTRA_SERVICE_CLASS = "jasmine.plugin.scheduler.serviceClass"
        const val EXTRA_TASK_ID = "jasmine.plugin.scheduler.taskId"
    }
}
