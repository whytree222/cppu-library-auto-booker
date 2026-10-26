package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import android.content.Intent
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.MapSeat
import cn.edu.cppu.libraryautobooker.data.RoomProfile
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** An immutable manual request; never saves over the pending scheduled plan. */
object ImmediateBookingPlan {
    const val EXTRA_REQUEST = "immediate_booking_request"
    const val EXTRA_ACTION_ID = "immediate_booking_action_id"
    fun actionId(intent: Intent?): String? = intent?.getStringExtra(EXTRA_ACTION_ID)?.takeIf {
        Regex("^manual:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(it)
    }
    data class Request(val config: BookingConfig, val anchorDate: LocalDate)
    fun targetDate(draft: BookingConfig, today: LocalDate = LocalDate.now()): LocalDate =
        today.plusDays(if (draft.reserveTomorrow) 1 else 0)
    fun prepare(draft: BookingConfig) = draft.copy(enabled = false, dryRun = false, waitForRelease = false,
        scheduledAtMillis = 0L, scheduledTimes = emptyList(), seatChoices = emptyList())

    fun intent(context: Context, draft: BookingConfig, today: LocalDate = LocalDate.now()) =
        Intent(context, BookingService::class.java).setAction(BookingService.ACTION_RUN_ONCE)
            .putExtra(EXTRA_REQUEST, encode(draft, today))
            .putExtra(EXTRA_ACTION_ID, "manual:${UUID.randomUUID()}")

    fun encode(draft: BookingConfig, today: LocalDate = LocalDate.now()): String {
        val config = prepare(draft)
        require(config.validationError() == null && config.mapSeats.isNotEmpty())
        return JSONObject().put("anchorDate", today.toString()).put("room", config.room.toJson())
            .put("reserveTomorrow", config.reserveTomorrow).put("seats", JSONArray(config.seatNumbers))
            .put("mapSeats", JSONArray().apply { config.mapSeats.forEach {
                put(JSONObject().put("id", it.id).put("row", it.row).put("column", it.column))
            } }).put("slots", JSONArray(config.selectedSlots)).put("append", config.appendLastThree)
            .put("start", config.startTime).put("end", config.endTime)
            .put("webVpnBackup", config.webVpnBackup)
            .put("roomKeywords", config.roomKeywords).put("seatKeywords", config.seatKeywords).toString()
            .also { require(it.length <= 32_000) }
    }

    fun decode(payload: String?): Request? = runCatching {
        require(payload != null && payload.length <= 32_000)
        val json = JSONObject(payload)
        val room = requireNotNull(RoomProfile.fromJson(json.getJSONObject("room")))
        val seats = json.getJSONArray("seats")
        val maps = json.getJSONArray("mapSeats")
        val slots = json.getJSONArray("slots")
        require(seats.length() in 1..10 && maps.length() == seats.length() && slots.length() <= 4)
        val config = prepare(BookingConfig(room = room, reserveTomorrow = json.getBoolean("reserveTomorrow"),
            seatNumbers = (0 until seats.length()).map(seats::getString),
            mapSeats = (0 until maps.length()).map { maps.getJSONObject(it).let { seat ->
                MapSeat(seat.getString("id"), seat.getInt("row"), seat.getInt("column"))
            } }, selectedSlots = (0 until slots.length()).map(slots::getInt),
            appendLastThree = json.getBoolean("append"), startTime = json.getString("start"),
            endTime = json.getString("end"), webVpnBackup = json.optBoolean("webVpnBackup", true),
            roomKeywords = json.getString("roomKeywords"), seatKeywords = json.getString("seatKeywords")))
        require(config.validationError() == null)
        Request(config, LocalDate.parse(json.getString("anchorDate")))
    }.getOrNull()
}
