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

    @Test fun datedRoomRulesRoundTripWithoutTurningLegacyCacheIntoEitherUsageDay() {
        val dated = second.copy(slotsDate = "2026-10-24")
        assertEquals(dated, RoomProfile.fromJson(dated.toJson()))
        ConfigStore(app).save(BookingConfig(room = dated, seatNumbers = listOf("B001"), selectedSlots = listOf(0)))
        assertEquals(dated, ConfigStore(app).load().room)
        RoomCatalogStore(app).merge(listOf(dated))
        assertEquals(dated, RoomCatalogStore(app).find(second.id))

        val legacy = second.toJson().apply { remove("slotsDate") }
        assertEquals("", RoomProfile.fromJson(legacy)!!.slotsDate)
        assertEquals("", RoomProfile.DEFAULT.slotsDate)
        assertNotEquals(dated, RoomProfile.fromJson(legacy))
    }

    @Test fun malformedOrImpossibleScheduleDatesAreNeverAcceptedOrSilentlyDropped() {
        listOf("2026-1-24", "2026-10-24T00:00:00", "2026-02-30", "2026-13-01", "2026-10-24\n",
            "2026/10/24", "not-a-date").forEach { date ->
            assertNotNull(second.copy(slotsDate = date).validationError())
            assertNull(RoomProfile.fromJson(second.toJson().put("slotsDate", date)))
        }
        assertNull(RoomProfile.fromJson(second.toJson().put("slotsDate", 20261024)))
        assertNull(RoomProfile.fromJson(second.toJson().put("slotsDate", JSONObject.NULL)))
        assertNull(second.copy(slotsDate = "2028-02-29").validationError())
        assertEquals(BookingConfig(room = second).reservationFingerprint(),
            BookingConfig(room = second.copy(slotsDate = "2026-10-24")).reservationFingerprint())
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
        assertEquals(9, store.load().size) // Seven collected rooms plus the two synthetic profiles.
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
