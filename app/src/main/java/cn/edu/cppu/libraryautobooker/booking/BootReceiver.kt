package cn.edu.cppu.libraryautobooker.booking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val config = ConfigStore(context).load()
        val scheduler = BookingScheduler(context)
        if (config.enabled && config.scheduledAtMillis > System.currentTimeMillis() && scheduler.canScheduleExact()) {
            try {
                scheduler.scheduleAt(config.scheduledAtMillis)
                RuntimeStore(context).record("SCHEDULED", "系统事件后恢复任务：${RuntimeStore.format(config.scheduledAtMillis)}", RuntimeStore.scheduledAction(config.scheduledAtMillis), "定时任务")
            } catch (error: RuntimeException) {
                RuntimeStore(context).record("FAILED", "恢复定时任务失败：${error.javaClass.simpleName}", RuntimeStore.scheduledAction(config.scheduledAtMillis))
            }
        } else if (config.enabled && config.scheduledAtMillis <= System.currentTimeMillis()) {
            ConfigStore(context).save(config.copy(enabled = false))
            RuntimeStore(context).record("MISSED", "恢复应用时放号时间已过，未补交预约；请重新安排", RuntimeStore.scheduledAction(config.scheduledAtMillis))
        }
    }
}
