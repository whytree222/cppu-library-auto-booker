package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("booking_config", Context.MODE_PRIVATE)

    fun load() = BookingConfig(
        enabled = prefs.getBoolean("enabled", false),
        dryRun = prefs.getBoolean("dry_run", true),
        reserveTomorrow = prefs.getBoolean("reserve_tomorrow", true),
        releaseHour = prefs.getInt("release_hour", 6),
        releaseMinute = prefs.getInt("release_minute", 0),
        roomKeywords = prefs.getString("room_keywords", "").orEmpty(),
        seatKeywords = prefs.getString("seat_keywords", "").orEmpty(),
        seatChoices = readSeats(),
        seatNumbers = readSeatNumbers(),
        startTime = prefs.getString("start_time", "08:00").orEmpty(),
        endTime = prefs.getString("end_time", "22:00").orEmpty(),
        entryPath = prefs.getString("entry_path", "/multireadingroomtablelist").orEmpty(),
        scheduledAtMillis = prefs.getLong("scheduled_at", 0L)
    )

    fun save(config: BookingConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putBoolean("dry_run", config.dryRun)
            .putBoolean("reserve_tomorrow", config.reserveTomorrow)
            .putInt("release_hour", config.releaseHour)
            .putInt("release_minute", config.releaseMinute)
            .putString("room_keywords", config.roomKeywords)
            .putString("seat_keywords", config.seatKeywords)
            .putString("seat_choices", JSONArray().apply {
                config.seatChoices.forEach { seat ->
                    put(JSONObject().apply {
                        put("label", seat.label)
                        put("selector", seat.selector)
                        put("x", seat.xFraction)
                        put("y", seat.yFraction)
                    })
                }
            }.toString())
            .putString("seat_numbers", JSONArray(config.seatNumbers).toString())
            .putString("start_time", config.startTime)
            .putString("end_time", config.endTime)
            .putString("entry_path", config.entryPath)
            .putLong("scheduled_at", config.scheduledAtMillis)
            .apply()
    }

    private fun readSeats(): List<SeatChoice> = try {
        val array = JSONArray(prefs.getString("seat_choices", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            SeatChoice(
                label = item.optString("label", "位置 ${index + 1}"),
                selector = item.optString("selector", ""),
                xFraction = item.optDouble("x", 0.5),
                yFraction = item.optDouble("y", 0.5)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun readSeatNumbers(): List<String> = try {
        val array = JSONArray(prefs.getString("seat_numbers", "[]"))
        (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}
