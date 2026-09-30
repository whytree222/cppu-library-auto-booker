package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RuntimeStore(context: Context) {
    val prefs = context.getSharedPreferences("runtime_status", Context.MODE_PRIVATE)

    fun session(state: String, detail: String) {
        prefs.edit().putString("session_state", state).putString("session_detail", detail)
            .putLong("session_at", System.currentTimeMillis()).apply()
    }

    fun record(state: String, detail: String) {
        val now = System.currentTimeMillis()
        val line = "${format(now)} $detail"
        val lines = (prefs.getString("events", "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(50)
        prefs.edit().putString("task_state", state).putString("task_detail", detail)
            .putLong("task_at", now).putString("events", lines.joinToString("\n")).commit()
    }

    fun test(state: String, detail: String) {
        prefs.edit().putString("test_state", state).putString("test_detail", "${format(System.currentTimeMillis())} $detail")
            .putLong("test_at", System.currentTimeMillis()).commit()
    }

    companion object {
        fun format(time: Long): String = if (time <= 0) "尚无记录" else
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }
}
