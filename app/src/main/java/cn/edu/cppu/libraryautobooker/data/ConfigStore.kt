package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("booking_config", Context.MODE_PRIVATE)

    init { LegacyFeatureMigration.run(context) }

    fun load() = BookingConfig(
        enabled = prefs.getBoolean("enabled", false),
        dryRun = prefs.getBoolean("dry_run", true),
        reserveTomorrow = prefs.getBoolean("reserve_tomorrow", true),
        releaseHour = prefs.getInt("release_hour", 0),
        releaseMinute = prefs.getInt("release_minute", 58),
        roomKeywords = prefs.getString("room_keywords", "").orEmpty(),
        seatKeywords = prefs.getString("seat_keywords", "").orEmpty(),
        seatChoices = readSeats(),
        seatNumbers = readSeatNumbers(),
        mapSeats = readMapSeats(),
        selectedSlots = readSlots(),
        appendLastThree = prefs.getBoolean("append_last_three", false),
        startTime = prefs.getString("start_time", "08:00").orEmpty(),
        endTime = prefs.getString("end_time", "22:00").orEmpty(),
        entryPath = prefs.getString("entry_path", "/selectreadingroom")
            .orEmpty().let { if (it == "/multireadingroomtablelist") "/selectreadingroom" else it },
        scheduledAtMillis = prefs.getLong("scheduled_at", 0L),
        releaseTimes = readLongs("release_times").filter { it in 0..1439 }.map { it.toInt() },
        scheduledTimes = readLongs("scheduled_times").filter { it > 0 },
        waitForRelease = prefs.getBoolean("wait_for_release", false),
        room = if (!prefs.contains("room_profile")) RoomProfile.DEFAULT else runCatching {
            RoomProfile.fromJson(JSONObject(prefs.getString("room_profile", "")!!))
        }.getOrNull() ?: RoomProfile("", "未识别的阅览室", "", "未知楼层", "unknown")
    )

    fun save(config: BookingConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putBoolean("dry_run", config.dryRun)
            .putBoolean("wait_for_release", config.waitForRelease)
            .putString("room_profile", config.room.toJson().toString())
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
            .putString("map_seats", JSONArray().apply { config.mapSeats.forEach {
                put(JSONObject().put("id", it.id).put("row", it.row).put("column", it.column))
            } }.toString())
            .putString("selected_slots", JSONArray(config.selectedSlots).toString())
            .putBoolean("append_last_three", config.appendLastThree)
            .putString("start_time", config.startTime)
            .putString("end_time", config.endTime)
            .putString("entry_path", config.entryPath)
            .putLong("scheduled_at", config.scheduledAtMillis)
            .putString("release_times", JSONArray(config.releaseTimes).toString())
            .putString("scheduled_times", JSONArray(config.scheduledTimes).toString())
            .apply()
    }

    private fun readLongs(key: String): List<Long> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { array.getLong(it) }.distinct().sorted()
    } catch (_: Exception) { emptyList() }

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

    private fun readMapSeats(): List<MapSeat> = try {
        val items = JSONArray(prefs.getString("map_seats", "[]"))
        require(items.length() <= 10)
        (0 until items.length()).map { items.getJSONObject(it).let { value ->
            MapSeat(value.getString("id"), value.getInt("row"), value.getInt("column"))
        } }
    } catch (_: Exception) { listOf(MapSeat("!", -1, -1)) }

    private fun readSlots(): List<Int> = try {
        val array = JSONArray(prefs.getString("selected_slots", "[]"))
        require(array.length() <= 48)
        (0 until array.length()).map { array.getInt(it) }
    } catch (_: Exception) {
        listOf(-1) // Retain an invalid marker; never silently change the user's requested slots.
    }
}
