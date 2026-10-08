package cn.edu.cppu.libraryautobooker.booking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val store = ConfigStore(context)
        var config = store.load()
        val scheduler = BookingScheduler(context)
        if (config.enabled) {
            val now = System.currentTimeMillis()
            val expired = config.pendingTimes().filter { it <= now }
            if (expired.isNotEmpty()) {
                config = config.withPending(config.pendingTimes().filter { it > now })
                store.save(config)
                expired.forEach { at -> RuntimeStore(context).record("MISSED", "恢复时该抢座时间已过，未补交预约；其他未来时间保留", RuntimeStore.scheduledAction(at)) }
            }
        }
        if (config.enabled && config.scheduledAtMillis > System.currentTimeMillis() && scheduler.canScheduleExact()) {
            try {
                scheduler.scheduleAt(config.scheduledAtMillis)
                RuntimeStore(context).record("SCHEDULED", "系统事件后恢复任务：${RuntimeStore.format(config.scheduledAtMillis)}", RuntimeStore.scheduledAction(config.scheduledAtMillis), "定时任务")
            } catch (error: RuntimeException) {
                RuntimeStore(context).record("FAILED", "恢复定时任务失败：${error.javaClass.simpleName}", RuntimeStore.scheduledAction(config.scheduledAtMillis))
            }
        }
    }
}
