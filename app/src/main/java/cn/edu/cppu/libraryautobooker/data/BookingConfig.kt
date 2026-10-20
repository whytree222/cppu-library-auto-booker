package cn.edu.cppu.libraryautobooker.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class BookingConfig(
    val enabled: Boolean = false,
    val dryRun: Boolean = true,
    val reserveTomorrow: Boolean = true,
    val releaseHour: Int = 6,
    val releaseMinute: Int = 0,
    val roomKeywords: String = "",
    val seatKeywords: String = "",
    val seatChoices: List<SeatChoice> = emptyList(),
    val seatNumbers: List<String> = emptyList(),
    val selectedSlots: List<Int> = emptyList(),
    val appendLastThree: Boolean = false,
    val startTime: String = "08:00",
    val endTime: String = "22:00",
    val entryPath: String = "/selectreadingroom",
    val scheduledAtMillis: Long = 0L,
    val releaseTimes: List<Int> = emptyList(),
    val scheduledTimes: List<Long> = emptyList(),
    val waitForRelease: Boolean = false,
    val webVpnBackup: Boolean = true,
    val room: RoomProfile = RoomProfile.DEFAULT
) {
    /** Binds an already-queued alarm handoff to this exact room/seat/time draft. */
    fun reservationFingerprint(): String {
        val snapshot = JSONObject().put("room", room.toJson()).put("seats", JSONArray(seatNumbers))
            .put("slots", JSONArray(selectedSlots)).put("tomorrow", reserveTomorrow)
            .put("start", startTime).put("end", endTime).put("append", appendLastThree)
            .put("dryRun", dryRun).put("wait", waitForRelease).put("vpn", webVpnBackup).toString()
        return MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun validationError(): String? {
        room.validationError()?.let { return it }
        if (seatNumbers.isEmpty() || seatNumbers.size > 10 || seatNumbers.distinct().size != seatNumbers.size)
            return "请输入 1 到 10 个不重复的座位号，每行一个"
        SeatCatalog.error(seatNumbers, room)?.let { return it }
        if (room.timeMode == "fixed") {
            val indexes = selectedSlots.distinct().sorted()
            if (indexes.isEmpty() || indexes.size > 4 || indexes != selectedSlots ||
                indexes.any { it !in room.slots.indices } || indexes.last() - indexes.first() + 1 != indexes.size)
                return "请选择当前阅览室的 1–4 个连续使用时段"
        } else {
            if (selectedSlots.isNotEmpty()) return "自选时间模式不能同时指定固定时段"
            val pattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
            if (!pattern.matches(startTime) || !pattern.matches(endTime)) return "请按 HH:mm 填写开始和结束时间，例如 08:00"
            fun minutes(value: String) = value.substring(0, 2).toInt() * 60 + value.substring(3).toInt()
            val begin = minutes(startTime)
            val end = minutes(endTime)
            val duration = end - begin
            if (duration <= 0) return "结束时间必须晚于开始时间，不支持跨午夜预约"
            if (room.minMinutes > 0 && duration < room.minMinutes) return "此阅览室至少预约 ${room.minMinutes} 分钟"
            if (room.maxMinutes > 0 && duration > room.maxMinutes) return "此阅览室最多预约 ${room.maxMinutes} 分钟"
            if (room.stepMinutes > 0 && (begin % room.stepMinutes != 0 || end % room.stepMinutes != 0))
                return "开始和结束时间须按 ${room.stepMinutes} 分钟步长选择"
        }
        if (appendLastThree && (!room.appendCompatible || selectedSlots != listOf(0, 1, 2, 3)))
            return "追加后三段仅适用于已核对的过刊阅览室前四段"
        return null
    }

    fun times(): List<Int> = (if (releaseTimes.isEmpty()) listOf(releaseHour * 60 + releaseMinute)
        else releaseTimes).distinct()

    fun pendingTimes(): List<Long> = (if (scheduledTimes.isEmpty() && enabled && scheduledAtMillis > 0)
        listOf(scheduledAtMillis) else scheduledTimes).filter { it > 0 }.distinct().sorted()

    fun withPending(times: List<Long>): BookingConfig {
        val pending = times.distinct().sorted()
        return copy(enabled = pending.isNotEmpty(), scheduledTimes = pending,
            scheduledAtMillis = pending.firstOrNull() ?: 0L)
    }
}
