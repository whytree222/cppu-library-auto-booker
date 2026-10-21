package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.*
import cn.edu.cppu.libraryautobooker.toggleMapSeat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SeatMapCatalogTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val catalog get() = SeatMapCatalog.load(app)

    @Test fun allSevenActualRoomsAppearOnTheirActualFloors() {
        val maps = catalog.maps
        assertEquals(listOf("26", "20", "21", "22", "23", "24", "28"), maps.map { it.room.id })
        assertEquals(listOf("1", "2", "2", "3", "3", "4", "4"), maps.map { it.room.groupId })
        assertEquals(962, maps.sumOf { it.seats.size })
        assertEquals(3, maps.sumOf { it.seats.count { s -> s.id.isBlank() } })
        assertEquals(7, RoomCatalogStore(app).load().count { it.id in maps.map { m -> m.room.id } })
    }

    @Test fun duplicateMalformedAndEmptyNodesCannotBeSelected() {
        for (map in catalog.maps) for (seat in map.seats) if (map.issue(seat) != null) {
            assertTrue(toggleMapSeat(map, seat.row, seat.column, emptyList()).isEmpty())
            if (seat.id.isNotBlank()) assertNotNull(map.selectionError(listOf(seat.id)))
        }
        val second = catalog.maps.single { it.room.id == "23" }
        assertTrue(second.seats.any { it.id == "S2026C..S2027A" })
        assertTrue(second.seats.any { it.row == 1 && it.column == 9 && it.id.isBlank() })
    }

    @Test fun exactCaseOriginalCoordinateAndPrioritySurviveStorageAndScript() {
        val map = catalog.maps.single { it.room.id == "21" }
        val seat = map.seats.single { it.id == "K043c" }
        assertNull(map.issue(seat))
        assertNotNull(map.selectionError(listOf("K043C")))
        assertNull(SeatCatalog.error(listOf("K043c"), map.room))
        val original = map.bind(BookingConfig(room = map.room, seatNumbers = listOf("K043c"), selectedSlots = listOf(0)))
        assertNull(original.validationError())
        val store = ConfigStore(app); store.save(original)
        assertEquals(original, store.load())
        val js = JSONObject(AutomationScript.build("__BOOKING_CONFIG__", original))
        assertEquals("K043c", js.getJSONArray("mapSeats").getJSONObject(0).getString("id"))
        assertEquals(seat.row, js.getJSONArray("mapSeats").getJSONObject(0).getInt("row"))
        assertNotEquals(original.reservationFingerprint(), original.copy(mapSeats = listOf(seat.copy(column = seat.column + 1))).reservationFingerprint())
        assertNotNull(original.copy(mapSeats = listOf(seat.copy(id = "K043C"))).validationError())
        val reset = cn.edu.cppu.libraryautobooker.resetRoomDraft(original, RoomProfile.DEFAULT)
        assertTrue(reset.mapSeats.isEmpty())
    }

    @Test fun tapsTogglePreserveOrderAndNeverExceedTenCandidates() {
        val map = catalog.maps.single { it.room.id == "26" }
        val seats = map.seats.take(11)
        var selected = emptyList<String>()
        for (seat in seats) selected = toggleMapSeat(map, seat.row, seat.column, selected)
        assertEquals(seats.take(10).map { it.id }, selected)
        val first = seats.first()
        selected = toggleMapSeat(map, first.row, first.column, selected)
        assertEquals(seats.drop(1).take(9).map { it.id }, selected)
        selected = toggleMapSeat(map, seats.last().row, seats.last().column, selected)
        assertEquals(seats.last().id, selected.last())
        assertEquals(selected, toggleMapSeat(map, 199, 199, selected))
    }

    @Test fun appendBatchKeepsOnlySuccessfulSeatsCoordinate() {
        val map = catalog.maps.single { it.room.id == "26" }
        val config = map.bind(BookingConfig(seatNumbers = listOf("G015A", "G016A"),
            selectedSlots = listOf(0,1,2,3), appendLastThree = true, dryRun = false))
        val run = BookingSequence(config)
        assertTrue(run.success(1, "G016A") is BookingSequence.Result.Next)
        assertEquals(listOf("G016A"), run.config.mapSeats.map { it.id })
        assertNull(run.config.validationError())
    }

    @Test fun corruptOrWrongRoomSnapshotsFailClosed() {
        val source = app.assets.open("seat-maps.json").bufferedReader().use { it.readText() }
        val data = JSONObject(source)
        data.getJSONArray("rooms").getJSONObject(0).put("seatCount", 1)
        assertTrue(runCatching { SeatMapCatalog.parse(data.toString()) }.isFailure)
        assertNull(catalog.forRoom(RoomProfile.DEFAULT.copy(id = "99")))
        assertNull(catalog.forRoom(RoomProfile.DEFAULT.copy(name = "另一个房间")))
    }
}

