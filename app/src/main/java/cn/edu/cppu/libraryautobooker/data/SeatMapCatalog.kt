package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Public layout snapshot only: never a cache of availability or reservation signatures. */
data class MapSeat(val id: String, val row: Int, val column: Int)

data class RoomSeatMap(
    val room: RoomProfile,
    val rows: Int,
    val columns: Int,
    val seats: List<MapSeat>,
    val capturedDate: String
) {
    private val grouped = seats.filter { it.id.isNotBlank() }.groupBy { it.id.uppercase(Locale.ROOT) }

    fun issue(seat: MapSeat): String? = when {
        seat.id.isBlank() -> "学校网页未提供编号，无法精确预约"
        !Regex("^[A-Za-z0-9\\u4e00-\\u9fff][A-Za-z0-9\\u4e00-\\u9fff_.-]{0,63}$").matches(seat.id) || ".." in seat.id ->
            "学校网页编号异常，需人工核对"
        grouped[seat.id.uppercase(Locale.ROOT)]?.size != 1 -> "学校网页编号重复，无法唯一定位"
        else -> null
    }

    fun selectionError(numbers: List<String>): String? = numbers.firstNotNullOfOrNull { number ->
        val matches = seats.filter { it.id == number }
        if (matches.isEmpty()) "$number 不在当前座位图中，请重新选座"
        else matches.firstNotNullOfOrNull { issue(it) }?.let { "$number：$it" }
    }

    fun bind(config: BookingConfig): BookingConfig {
        require(config.room.id == room.id && config.room.name == room.name && config.room.groupId == room.groupId)
        require(selectionError(config.seatNumbers) == null)
        return config.copy(mapSeats = config.seatNumbers.map { number -> seats.single { it.id == number } }, seatChoices = emptyList())
    }
}

class SeatMapCatalog private constructor(val maps: List<RoomSeatMap>) {
    fun forRoom(room: RoomProfile) = maps.singleOrNull {
        it.room.id == room.id && it.room.name == room.name && it.room.groupId == room.groupId
    }

    companion object {
        fun load(context: Context): SeatMapCatalog = context.assets.open("seat-maps.json").bufferedReader()
            .use { parse(it.readText()) }

        fun parse(source: String): SeatMapCatalog {
            require(source.length <= 500_000)
            val json = JSONObject(source)
            val date = json.getString("capturedDate")
            require(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date))
            val items = json.getJSONArray("rooms")
            require(items.length() in 1..200)
            val maps = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val id = item.getInt("roomNo").toString()
                val floor = item.getString("floor")
                val groupId = mapOf("一楼" to "1", "二楼" to "2", "三楼" to "3", "四楼" to "4")[floor]
                    ?: error("Unknown floor")
                val slots = item.getJSONArray("slots")
                val room = RoomProfile(id, item.getString("name"), groupId, floor, "fixed",
                    (0 until slots.length()).map { slot -> slots.getJSONObject(slot).let {
                        RoomSlot(it.getString("begin"), it.getString("end"))
                    } })
                require(room.validationError() == null)
                val matrix = item.getJSONArray("rows")
                require(matrix.length() in 1..200)
                val rows = (0 until matrix.length()).map { matrix.getString(it).split('|') }
                val columns = rows.maxOf { it.size }
                require(columns in 1..200)
                val seats = rows.flatMapIndexed { row, cells -> cells.mapIndexedNotNull { column, value ->
                    value.takeIf { it.isNotEmpty() }?.let {
                        require(it == it.trim() && it.length <= 64 && it.none(Char::isISOControl))
                        MapSeat(it, row, column)
                    }
                } }.toMutableList()
                val unnumbered = item.optJSONArray("unnumbered")
                if (unnumbered != null) for (n in 0 until unnumbered.length()) {
                    val position = unnumbered.getJSONObject(n)
                    val row = position.getInt("row") - 1
                    val column = position.getInt("column") - 1
                    require(row in rows.indices && column in 0 until columns &&
                        seats.none { it.row == row && it.column == column })
                    seats.add(MapSeat("", row, column))
                }
                require(seats.size == item.getInt("seatCount") && seats.size in 1..5_000)
                require(seats.map { it.row to it.column }.distinct().size == seats.size)
                RoomSeatMap(room, rows.size, columns, seats.toList(), date)
            }
            require(maps.map { it.room.id }.distinct().size == maps.size)
            return SeatMapCatalog(maps.sortedWith(compareBy({ it.room.groupId.toInt() }, { it.room.id.toInt() })))
        }
    }
}

