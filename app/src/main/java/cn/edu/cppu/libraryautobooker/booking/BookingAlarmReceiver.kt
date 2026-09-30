package cn.edu.cppu.libraryautobooker.booking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore

class BookingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val runtime = RuntimeStore(context)
        if (intent?.action == BookingScheduler.ACTION_WAKE_TEST) {
            runtime.test("TRIGGERED", "测试闹钟已触发，正在启动后台服务")
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, BookingService::class.java).setAction(BookingService.ACTION_WAKE_TEST))
            } catch (error: RuntimeException) {
                runtime.test("FAILED", "测试闹钟已触发，但后台服务启动失败：${error.javaClass.simpleName}")
            }
            return
        }
        val store = ConfigStore(context)
        val config = store.load()
        if (!config.enabled) return
        val expected = intent?.getLongExtra("expected_at", config.scheduledAtMillis) ?: config.scheduledAtMillis
        if (expected != config.scheduledAtMillis) return
        runtime.record("TRIGGERED", "定时闹钟已触发；计划 ${RuntimeStore.format(expected)}，实际 ${RuntimeStore.format(System.currentTimeMillis())}")
        try {
            ContextCompat.startForegroundService(
            context,
            Intent(context, BookingService::class.java).apply {
                action = BookingService.ACTION_SCHEDULED
                putExtra("expected_at", expected)
            }
            )
        } catch (error: RuntimeException) {
            runtime.record("FAILED", "闹钟已触发，但后台服务启动失败：${error.javaClass.simpleName}")
        }
        // Retain planned time and trigger trace instead of erasing the evidence.
        store.save(config.copy(enabled = false))
    }
}
