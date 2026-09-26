package cn.edu.cppu.libraryautobooker.booking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.edu.cppu.libraryautobooker.data.ConfigStore

class BookingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val store = ConfigStore(context)
        val config = store.load()
        if (!config.enabled) return
        store.save(config.copy(enabled = false, scheduledAtMillis = 0L))
        ContextCompat.startForegroundService(
            context,
            Intent(context, BookingService::class.java).apply {
                action = BookingService.ACTION_SCHEDULED
            }
        )
    }
}
