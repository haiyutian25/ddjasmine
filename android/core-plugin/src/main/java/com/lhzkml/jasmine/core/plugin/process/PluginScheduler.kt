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
        val intent = alarmIntent(context, pluginId, requestCode, serviceClassName, taskId)
        if (canScheduleExact(context, am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, intent)
        } else {
            // 无精确闹钟权限（Android 12+），降级为非精确触发
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, intent)
        }
    }

    /** 是否有精确闹钟权限（Android 12+ 需 SCHEDULE_EXACT_ALARM）。 */
    private fun canScheduleExact(context: Context, am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

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

    /**
     * 把 (pluginId, requestCode) 折叠成一个稳定的 PendingIntent requestCode。
     * 此前 `pluginId.hashCode() and 0xFFFF` 只取低 16 位：两个插件的哈希低 16
     * 位相同时，同 requestCode 的闹钟会共用同一 PendingIntent（互相覆盖/误取消）。
     * 改用完整 32 位哈希 + 乘法/异或雪崩，碰撞概率降到 2^-32 量级。
     *
     * taskId 刻意不参与身份：cancel 只拿 (pluginId, requestCode)，身份必须仅由
     * 二者导出；taskId 是标签（默认 "scheduled-$requestCode"），requestCode 即
     * 每插件内的任务标识。
     */
    private fun requestCodeOf(pluginId: String, requestCode: Int): Int {
        var h = pluginId.hashCode() * 31 + requestCode
        h = h xor (h ushr 16)
        h *= 0x7feb352d
        h = h xor (h ushr 15)
        return h
    }
}

/**
 * `AlarmManager` 触发的宿主接收器：根据 extras 里的插件 Service 类名，
 * 经 Service 池以前台服务方式启动插件 Service。
 */
class PluginAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceClassName = intent.getStringExtra(EXTRA_SERVICE_CLASS) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: "scheduled"
        // 任何失败都不能抛到 BroadcastReceiver（会崩宿主），且已占用的池
        // 槽位必须回滚。两类确定触发：① 开机后闹钟先于框架 initialize
        // 完成（acquire 里 coreHandle 未初始化会抛）；② Android 12+ 后台
        // FGS 启动限制——非精确闹钟无豁免，startForegroundService 抛
        // ForegroundServiceStartNotAllowedException。
        var instanceId: String? = null
        try {
            val (id, proxyClass) = ServiceProxyPool.acquire(serviceClassName, taskId)
                ?: return // 池耗尽，静默放弃（下次周期任务会再试）
            instanceId = id
            val serviceIntent = Intent(context, proxyClass).apply {
                putExtra(ProxyKeys.SERVICE_CLASS, serviceClassName)
                putExtra(ProxyKeys.SERVICE_INSTANCE_ID, instanceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Throwable) {
            android.util.Log.w("PluginScheduler", "闹钟启动插件服务失败: $serviceClassName", e)
            instanceId?.let { ServiceProxyPool.release(it) }
        }
    }

    companion object {
        const val EXTRA_SERVICE_CLASS = "jasmine.plugin.scheduler.serviceClass"
        const val EXTRA_TASK_ID = "jasmine.plugin.scheduler.taskId"
    }
}
