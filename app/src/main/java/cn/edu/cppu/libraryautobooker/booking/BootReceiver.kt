package cn.edu.cppu.libraryautobooker.booking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.cppu.libraryautobooker.data.ConfigStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val config = ConfigStore(context).load()
        val scheduler = BookingScheduler(context)
        if (config.enabled && config.scheduledAtMillis > System.currentTimeMillis() && scheduler.canScheduleExact()) {
            scheduler.scheduleAt(config.scheduledAtMillis)
        }
    }
}
