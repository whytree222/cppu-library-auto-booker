package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class RuntimeStore(context: Context) {
    val prefs = context.getSharedPreferences("runtime_status", Context.MODE_PRIVATE)

    data class RunRecord(val id: String, val title: String, val startedAt: Long, val state: String, val lines: List<String>)

    init { synchronized(lock) { migrateHistory() } }

    fun session(state: String, detail: String) {
        prefs.edit().putString("session_state", state).putString("session_detail", detail)
            .putLong("session_at", System.currentTimeMillis()).apply()
    }

    fun record(state: String, detail: String, actionId: String? = null, title: String? = null) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val line = "${format(now)} $detail"
        val runs = history().toMutableList()
        val id = actionId ?: prefs.getString("active_action", null) ?: UUID.randomUUID().toString()
        val index = runs.indexOfFirst { it.id == id }
        val previous = runs.getOrNull(index) ?: RunRecord(id, title ?: "运行任务", now, state, emptyList())
        val updated = previous.copy(state = state, lines = (previous.lines + line).takeLast(MAX_STEPS))
        if (index >= 0) runs[index] = updated else runs.add(updated)
        val retained = runs.takeLast(MAX_RUNS)
        check(prefs.edit().putString("task_state", state).putString("task_detail", detail)
            .putLong("task_at", now).putString("active_action", id)
            .putString("history", encode(retained)).putString("events", flatten(retained)).commit())
    }

    fun history(): List<RunRecord> = synchronized(lock) {
        runCatching {
            val array = JSONArray(prefs.getString("history", "[]"))
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val lines = item.getJSONArray("lines")
                RunRecord(item.getString("id"), item.getString("title"), item.getLong("startedAt"),
                    item.getString("state"), (0 until lines.length()).map { lines.getString(it) }.takeLast(MAX_STEPS))
            }.takeLast(MAX_RUNS)
        }.getOrDefault(emptyList())
    }

    private fun migrateHistory() {
        if (prefs.contains("history")) return
        val legacy = prefs.getString("events", "").orEmpty().lines().filter { it.isNotBlank() }.takeLast(MAX_STEPS)
        val runs = if (legacy.isEmpty()) emptyList() else listOf(RunRecord("legacy", "升级前的记录",
            prefs.getLong("task_at", 0L), prefs.getString("task_state", "").orEmpty(), legacy))
        prefs.edit().putString("history", encode(runs)).putString("events", flatten(runs))
            .apply { if (runs.isNotEmpty()) putString("active_action", "legacy") }.commit()
    }

    private fun encode(runs: List<RunRecord>) = JSONArray().apply {
        runs.forEach { run -> put(JSONObject().put("id", run.id).put("title", run.title)
            .put("startedAt", run.startedAt).put("state", run.state).put("lines", JSONArray(run.lines))) }
    }.toString()

    private fun flatten(runs: List<RunRecord>) = runs.flatMap { it.lines }.joinToString("\n")

    fun test(state: String, detail: String) {
        prefs.edit().putString("test_state", state).putString("test_detail", "${format(System.currentTimeMillis())} $detail")
            .putLong("test_at", System.currentTimeMillis()).commit()
    }

    companion object {
        const val MAX_RUNS = 3
        private const val MAX_STEPS = 50
        private val lock = Any()
        fun scheduledAction(time: Long) = "scheduled:$time"
        fun format(time: Long): String = if (time <= 0) "尚无记录" else
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }
}
