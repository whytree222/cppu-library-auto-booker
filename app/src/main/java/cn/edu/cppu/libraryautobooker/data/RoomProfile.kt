package cn.edu.cppu.libraryautobooker.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Public room identity/rules only. Never stores page signatures or authentication material. */
data class RoomSlot(val begin: String, val end: String) {
    val label get() = "$begin–$end"
    fun toJson() = JSONObject().put("begin", begin).put("end", end)
    fun valid() = seconds(begin)?.let { first -> seconds(end)?.let { it > first } } == true

    companion object {
        fun seconds(value: String): Int? {
            if (!Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$").matches(value)) return null
            val parts = value.split(':').map(String::toInt)
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    }
}

data class RoomProfile(
    val id: String,
    val name: String,
    val groupId: String,
    val groupName: String,
    val timeMode: String,
    val slots: List<RoomSlot> = emptyList(),
    val minMinutes: Int = 0,
    val maxMinutes: Int = 0,
    val stepMinutes: Int = 0,
    /** Empty only for old, undated cached rules; never an assertion about either usage day. */
    val slotsDate: String = ""
) {
    val appendCompatible get() = id == "26" && timeMode == "fixed" && slots == DEFAULT.slots
    val entryPath get() = "/selectreadingroom?groupid=$groupId"

    fun validationError(): String? = when {
        !Regex("^[1-9]\\d{0,8}$").matches(id) || !Regex("^(?:0|[1-9]\\d{0,8})$").matches(groupId) -> "阅览室或楼层编号无效，请从学校重新同步"
        name.isBlank() || name.length > 80 || groupName.isBlank() || groupName.length > 80 ||
            (name + groupName).any { it.isISOControl() || it in "<>" } -> "阅览室名称无效，请从学校重新同步"
        timeMode !in setOf("fixed", "free") -> "此阅览室的时间选择方式尚未识别"
        slotsDate.isNotEmpty() && !validSlotsDate(slotsDate) -> "阅览室时段日期无效，请重新同步"
        listOf(minMinutes, maxMinutes, stepMinutes).any { it !in 0..1440 } ||
            (maxMinutes > 0 && maxMinutes < minMinutes) -> "阅览室的时间规则异常，请重新同步"
        timeMode == "fixed" && (slots.isEmpty() || slots.size > 48 || slots.any { !it.valid() } ||
            slots.distinct().size != slots.size || slots.zipWithNext().any { (a, b) ->
                RoomSlot.seconds(a.begin)!! >= RoomSlot.seconds(b.begin)!! || RoomSlot.seconds(a.end)!! > RoomSlot.seconds(b.begin)!!
            }) -> "阅览室固定时段未同步或规则异常，请重新同步"
        timeMode == "free" && slots.isNotEmpty() -> "自选时间阅览室不应使用固定时段"
        else -> null
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("groupId", groupId).put("groupName", groupName).put("timeMode", timeMode)
        .put("slots", JSONArray().apply { slots.forEach { put(it.toJson()) } })
        .put("minMinutes", minMinutes).put("maxMinutes", maxMinutes).put("stepMinutes", stepMinutes)
        .put("slotsDate", slotsDate)

    companion object {
        fun validSlotsDate(value: String): Boolean = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value) &&
            runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)

        val DEFAULT = RoomProfile("26", "过刊阅览室", "1", "一楼", "fixed", listOf(
            RoomSlot("08:10:01", "10:00:00"), RoomSlot("10:01:00", "11:29:00"),
            RoomSlot("11:31:00", "14:29:00"), RoomSlot("14:31:00", "16:30:30"),
            RoomSlot("16:31:30", "18:00:30"), RoomSlot("18:01:30", "19:29:30"),
            RoomSlot("19:31:00", "22:01:00")
        ))

        fun fromJson(json: JSONObject): RoomProfile? = runCatching {
            val array = json.optJSONArray("slots") ?: JSONArray()
            require(array.length() <= 48)
            require(!json.has("slotsDate") || json.opt("slotsDate") is String)
            RoomProfile(json.getString("id"), json.getString("name"), json.getString("groupId"),
                json.getString("groupName"), json.getString("timeMode"),
                (0 until array.length()).map { index -> array.getJSONObject(index).let {
                    RoomSlot(it.getString("begin"), it.getString("end"))
                } }, json.optInt("minMinutes", 0), json.optInt("maxMinutes", 0), json.optInt("stepMinutes", 0),
                json.optString("slotsDate", ""))
                .also { require(it.validationError() == null) }
        }.getOrNull()
    }
}
