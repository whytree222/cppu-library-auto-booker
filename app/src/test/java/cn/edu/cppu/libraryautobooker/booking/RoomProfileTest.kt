package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import cn.edu.cppu.libraryautobooker.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomProfileTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val second = RoomProfile("31", "测试二层阅览室", "2", "二楼", "fixed",
        listOf(RoomSlot("09:00:00", "11:00:00"), RoomSlot("11:01:00", "13:00:00")))
    private val free = RoomProfile("42", "测试四层阅览室", "4", "四楼", "free",
        minMinutes = 30, maxMinutes = 180, stepMinutes = 15)

    @Before fun reset() {
        listOf("booking_config", "room_catalog_v1").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun oldConfigMigratesToKnownRoomButCorruptNewIdentityNeverSilentlyFallsBack() {
        assertEquals(RoomProfile.DEFAULT, ConfigStore(app).load().room)
        app.getSharedPreferences("booking_config", Context.MODE_PRIVATE).edit()
            .putString("room_profile", "malformed").commit()
        assertNotEquals("26", ConfigStore(app).load().room.id)
        assertNotNull(ConfigStore(app).load().validationError())
    }

    @Test fun completeRoomAndTimesPersistInEachSavedTask() {
        val config = BookingConfig(room = second, seatNumbers = listOf("B-001"), selectedSlots = listOf(1))
        ConfigStore(app).save(config)
        assertEquals(config, ConfigStore(app).load())
        val js = JSONObject(AutomationScript.build("__BOOKING_CONFIG__", config, "2026-10-21"))
        assertEquals("31", js.getJSONObject("room").getString("id"))
        assertEquals("2", js.getJSONObject("room").getString("groupId"))
        assertEquals(2, js.getJSONObject("room").getJSONArray("slots").length())
        assertEquals(0, js.getJSONArray("seatCatalog").length())
        assertEquals("B-001", js.getJSONArray("seatNumbers").getString(0))
    }

    @Test fun fixedTimesAreSpecificToTheSelectedRoomAndNeverUseDefaultSeven() {
        val config = BookingConfig(room = second, seatNumbers = listOf("B001"), selectedSlots = listOf(0, 1))
        assertNull(config.validationError())
        assertNotNull(config.copy(selectedSlots = listOf(2)).validationError())
        assertNotNull(config.copy(selectedSlots = listOf(1, 0)).validationError())
        assertNotNull(config.copy(appendLastThree = true).validationError())
        assertFalse(BookingSequence(config.copy(appendLastThree = true)).hasSecondBatch)
    }

    @Test fun freeTimeRulesValidateFormatOrderingDurationAndStep() {
        val config = BookingConfig(room = free, seatNumbers = listOf("401"), startTime = "09:00", endTime = "11:00")
        assertNull(config.validationError())
        listOf(config.copy(startTime = "9:00"), config.copy(endTime = "25:00"),
            config.copy(endTime = "08:00"), config.copy(endTime = "09:15"),
            config.copy(endTime = "13:00"), config.copy(startTime = "09:01"),
            config.copy(selectedSlots = listOf(0)), config.copy(appendLastThree = true)).forEach {
            assertNotNull(it.validationError())
        }
        ConfigStore(app).save(config)
        assertEquals(config, ConfigStore(app).load())
    }

    @Test fun catalogueUpsertsByIdentityAndRejectsConflictingDuplicates() {
        val store = RoomCatalogStore(app)
        store.merge(listOf(second, free))
        assertEquals(3, store.load().size)
        val changed = second.copy(name = "测试新名称")
        store.merge(listOf(changed))
        assertEquals(changed, store.find(second.id))
        assertTrue(runCatching { store.merge(listOf(second, changed)) }.isFailure)
        assertEquals(changed, store.find(second.id))
        assertTrue(runCatching { store.merge(listOf(second.copy(id = "31&roomno=26"))) }.isFailure)
    }

    @Test fun invalidUnknownOrOverlappingRulesAreNotParsed() {
        listOf(second.copy(id = ""), second.copy(groupId = "1?x"), second.copy(timeMode = "unknown"),
            second.copy(slots = emptyList()), second.copy(slots = second.slots.reversed()),
            second.copy(slots = listOf(RoomSlot("09:00:00", "11:00:00"), RoomSlot("10:00:00", "12:00:00"))),
            second.copy(slots = listOf(RoomSlot("23:00:00", "24:00:00"))),
            free.copy(maxMinutes = 10), free.copy(slots = second.slots)).forEach {
            assertNotNull(it.validationError())
            assertNull(RoomProfile.fromJson(it.toJson()))
        }
        assertEquals(second.copy(groupId = "0"), RoomProfile.fromJson(second.copy(groupId = "0").toJson()))
    }

    @Test fun genericSeatFormatsDoNotAssumeGPrefixAndNeverAcceptSelectorsOrMarkup() {
        assertNull(SeatCatalog.error(listOf("A001", "4-01", "001", "阅001", "B.1", "B_1"), second))
        listOf("A 01", "#A01", "A, B", "A\n01", "<A01>", "A/01", "A'01", "A".repeat(65)).forEach {
            assertNotNull(SeatCatalog.error(listOf(it), second))
        }
        assertNotNull(SeatCatalog.error(listOf("B001")))
    }

    @Test fun nextBatchRetainsTheExactOriginalRoomIdentity() {
        val first = BookingConfig(room = RoomProfile.DEFAULT, dryRun = false, appendLastThree = true,
            selectedSlots = listOf(0, 1, 2, 3), seatNumbers = listOf("G015A"))
        val sequence = BookingSequence(first)
        assertTrue(sequence.success(1, "G015A") is BookingSequence.Result.Next)
        assertEquals(first.room, sequence.config.room)
        assertEquals(first.room, sequence.configForDate(java.time.LocalDate.now())!!.room)
    }

    @Test fun queuedAlarmBindingSurvivesQueueConsumptionButNotTargetChanges() {
        val initial = BookingConfig(room = second, seatNumbers = listOf("B001"), selectedSlots = listOf(0))
        val id = initial.reservationFingerprint()
        assertEquals(id, initial.withPending(listOf(1L, 2L)).reservationFingerprint())
        assertEquals(id, initial.withPending(emptyList()).reservationFingerprint())
        listOf(initial.copy(room = free), initial.copy(seatNumbers = listOf("B002")),
            initial.copy(selectedSlots = listOf(1)), initial.copy(reserveTomorrow = !initial.reserveTomorrow),
            initial.copy(dryRun = !initial.dryRun)).forEach { assertNotEquals(id, it.reservationFingerprint()) }
    }

    @Test fun corruptSlotIndicesAreNeverSilentlyReducedToADifferentBooking() {
        ConfigStore(app).save(BookingConfig(room = second, seatNumbers = listOf("B001"), selectedSlots = listOf(0)))
        app.getSharedPreferences("booking_config", Context.MODE_PRIVATE).edit().putString("selected_slots", "[0,48]").commit()
        assertEquals(listOf(0, 48), ConfigStore(app).load().selectedSlots)
        assertNotNull(ConfigStore(app).load().validationError())
    }
}
