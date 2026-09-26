package cn.edu.cppu.libraryautobooker.booking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import cn.edu.cppu.libraryautobooker.data.BookingConfig
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
        scheduleAt(trigger.toInstant().toEpochMilli())
        return trigger
    }

    fun scheduleAt(epochMillis: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BookingAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMillis, pending)
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
        private const val REQUEST_CODE = 4401

        fun nextTrigger(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
            var result = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!result.isAfter(now)) result = result.plusDays(1)
            return result
        }
    }
}
