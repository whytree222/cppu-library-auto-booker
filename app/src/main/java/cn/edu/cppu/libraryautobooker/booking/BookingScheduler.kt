package cn.edu.cppu.libraryautobooker.booking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import java.time.ZonedDateTime

class BookingScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun exactAlarmSettingsIntent() = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = android.net.Uri.parse("package:${context.packageName}")
    }

    fun schedule(config: BookingConfig): ZonedDateTime {
        val trigger = nextTrigger(config.releaseHour, config.releaseMinute)
        val planned = config.copy(enabled = true, scheduledAtMillis = trigger.toInstant().toEpochMilli())
        ConfigStore(context).save(planned)
        try {
            scheduleAt(planned.scheduledAtMillis)
            RuntimeStore(context).record("SCHEDULED", "已安排${if (config.dryRun) "演练（不提交）" else "真实预约"}：${RuntimeStore.format(planned.scheduledAtMillis)}")
        } catch (error: RuntimeException) {
            ConfigStore(context).save(planned.copy(enabled = false))
            RuntimeStore(context).record("FAILED", "安排闹钟失败：${error.javaClass.simpleName}，请检查精确闹钟权限")
            throw error
        }
        return trigger
    }

    fun scheduleAt(epochMillis: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BookingAlarmReceiver::class.java).putExtra("expected_at", epochMillis),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMillis, pending)
    }

    fun scheduleWakeTest() {
        val planned = ConfigStore(context).load()
        require(!planned.enabled || planned.scheduledAtMillis - System.currentTimeMillis() !in 0..1_200_000L) {
            "正式任务将在 20 分钟内运行，请在任务结束后测试"
        }
        val due = System.currentTimeMillis() + 60_000L
        val pending = PendingIntent.getBroadcast(context, 4404,
            Intent(context, BookingAlarmReceiver::class.java).setAction(ACTION_WAKE_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        RuntimeStore(context).apply {
            prefs.edit().putLong("test_due", due).commit()
            test("SCHEDULED", "唤醒测试已安排：${RuntimeStore.format(due)}，不会预约")
        }
    }

    fun cancel() {
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BookingAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    companion object {
        const val ACTION_WAKE_TEST = "cn.edu.cppu.libraryautobooker.WAKE_TEST"
        private const val REQUEST_CODE = 4401

        fun nextTrigger(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
            var result = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!result.isAfter(now)) result = result.plusDays(1)
            return result
        }
    }
}
