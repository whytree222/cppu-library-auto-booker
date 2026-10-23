package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.RoomRulesTicket
import cn.edu.cppu.libraryautobooker.applyRoomRules
import cn.edu.cppu.libraryautobooker.roomRulesAffectPlan
import cn.edu.cppu.libraryautobooker.roomRulesChanged
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.MapSeat
import cn.edu.cppu.libraryautobooker.data.RoomProfile
import cn.edu.cppu.libraryautobooker.data.RoomSlot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomRulesDraftTest {
    private val room = RoomProfile.DEFAULT.copy(slotsDate = "2026-10-23")
    private val draft = BookingConfig(room = room, enabled = true, dryRun = false, reserveTomorrow = true,
        seatNumbers = listOf("G015A"), mapSeats = listOf(MapSeat("G015A", 1, 2)),
        selectedSlots = listOf(0, 1, 2, 3), appendLastThree = true, roomKeywords = "过刊",
        seatKeywords = "靠窗", startTime = "08:00", endTime = "22:00", releaseTimes = listOf(58),
        scheduledTimes = listOf(1000L), scheduledAtMillis = 1000L, waitForRelease = true)

    @Test fun changingOnlyDateKeepsExactSelectionsSeatBindingsAndPendingPlan() {
        val updated = room.copy(slotsDate = "2026-10-24")
        assertFalse(roomRulesChanged(room, updated))
        assertEquals(draft.copy(room = updated), applyRoomRules(draft, updated))
    }

    @Test fun dateProvenanceDoesNotChangeAlarmBindingButAnExactSelectedTimeChangeDoes() {
        val undated = draft.copy(room = room.copy(slotsDate = ""))
        val binding = undated.reservationFingerprint()
        assertEquals(binding, draft.reservationFingerprint())
        assertEquals(binding, applyRoomRules(draft, room.copy(slotsDate = "2026-10-24"))
            .reservationFingerprint())
        val changed = room.copy(slots = room.slots.toMutableList().apply {
            this[1] = this[1].copy(begin = "10:01:10")
        })
        assertNotEquals(binding, draft.copy(room = changed).reservationFingerprint())
        assertNotEquals(binding, applyRoomRules(draft, changed).reservationFingerprint())
    }

    @Test fun changingAnUnselectedSlotKeepsChosenRangesButNotAnUnconfirmedPlanOrAppendBatch() {
        val slots = room.slots.toMutableList().apply { this[5] = this[5].copy(end = "19:29:11") }
        val updated = room.copy(slots = slots, slotsDate = "2026-10-24")
        val result = applyRoomRules(draft, updated)
        assertTrue(roomRulesChanged(room, updated))
        assertEquals(draft.selectedSlots, result.selectedSlots)
        assertEquals(draft.seatNumbers, result.seatNumbers)
        assertEquals(draft.mapSeats, result.mapSeats)
        assertEquals(draft.seatKeywords, result.seatKeywords)
        assertEquals(draft.roomKeywords, result.roomKeywords)
        assertEquals(draft.reserveTomorrow, result.reserveTomorrow)
        assertEquals(draft.startTime, result.startTime)
        assertEquals(draft.endTime, result.endTime)
        assertEquals(draft.releaseTimes, result.releaseTimes)
        assertEquals(draft.waitForRelease, result.waitForRelease)
        assertEquals(draft.dryRun, result.dryRun)
        assertFalse(result.appendLastThree)
        assertFalse(result.enabled)
        assertTrue(result.pendingTimes().isEmpty())
        assertEquals(0L, result.scheduledAtMillis)
    }

    @Test fun oneSecondChangeInAChosenRangeClearsTheWholeSelectionWithoutMinuteOrOrdinalRemapping() {
        listOf(room.slots[1].copy(begin = "10:01:10"), room.slots[1].copy(end = "11:29:01")).forEach { changed ->
            val updated = room.copy(slots = room.slots.toMutableList().apply { this[1] = changed })
            val result = applyRoomRules(draft, updated)
            assertTrue(result.selectedSlots.isEmpty())
            assertFalse(result.appendLastThree)
            assertEquals(draft.mapSeats, result.mapSeats)
        }
    }

    @Test fun removedChosenRangeClearsAllRatherThanKeepingAPartialBooking() {
        val result = applyRoomRules(draft, room.copy(slots = room.slots.filterIndexed { index, _ -> index != 1 }))
        assertTrue(result.selectedSlots.isEmpty())
        assertFalse(result.appendLastThree)
    }

    @Test fun removingOnlyAnUnselectedEarlierRangeRemapsByExactFullRange() {
        val original = draft.copy(selectedSlots = listOf(2, 3), appendLastThree = false)
        val updated = room.copy(slots = room.slots.drop(1))
        val result = applyRoomRules(original, updated)
        assertEquals(listOf(1, 2), result.selectedSlots)
        assertEquals(original.selectedSlots.map(room.slots::get), result.selectedSlots.map(updated.slots::get))
        assertEquals(original.seatNumbers, result.seatNumbers)
        assertFalse(result.enabled)
    }

    @Test fun insertedGapBetweenChosenExactRangesRequiresReselection() {
        val original = draft.copy(selectedSlots = listOf(1, 2), appendLastThree = false)
        val updated = room.copy(slots = room.slots.toMutableList().apply {
            add(2, RoomSlot("11:29:10", "11:30:00"))
        })
        assertTrue(applyRoomRules(original, updated).selectedSlots.isEmpty())
    }

    @Test fun malformedOriginalSelectionsAreNeverSilentlyRepaired() {
        listOf(emptyList(), listOf(1, 0), listOf(0, 0), listOf(0, 2), listOf(-1), listOf(48),
            listOf(0, 1, 2, 3, 4)).forEach { indexes ->
            val result = applyRoomRules(draft.copy(selectedSlots = indexes), room)
            assertTrue(result.selectedSlots.isEmpty())
            assertFalse(result.appendLastThree)
        }
    }

    @Test fun changedIdentityModeOrMalformedRulesCannotBeAdopted() {
        listOf(room.copy(id = "20"), room.copy(name = "同层其他阅览室"), room.copy(groupId = "2"),
            room.copy(groupName = "二楼"), room.copy(timeMode = "free", slots = emptyList()),
            room.copy(slots = room.slots + room.slots.last()), room.copy(slotsDate = "2026-02-30")).forEach { updated ->
            assertTrue(runCatching { applyRoomRules(draft, updated) }.isFailure)
            assertEquals(listOf(1000L), draft.pendingTimes())
        }
    }

    @Test fun freeTimeRuleUpdatesKeepExplicitTimesAndSeatsButClearPendingPlan() {
        val free = RoomProfile("31", "自选时间测试室", "2", "二楼", "free", minMinutes = 10, maxMinutes = 600)
        val original = draft.copy(room = free, selectedSlots = emptyList(), appendLastThree = false)
        val updated = free.copy(minMinutes = 30, maxMinutes = 180, stepMinutes = 15, slotsDate = "2026-10-24")
        val result = applyRoomRules(original, updated)
        assertTrue(roomRulesChanged(free, updated))
        assertEquals("08:00", result.startTime)
        assertEquals("22:00", result.endTime)
        assertEquals(original.mapSeats, result.mapSeats)
        assertTrue(result.selectedSlots.isEmpty())
        assertFalse(result.enabled)
    }

    @Test fun ticketAcceptsOnlyTheRequestedExactRoomUsageDayAndVerifiedDate() {
        val ticket = RoomRulesTicket.create(draft, LocalDate.parse("2026-10-24"))
        val updated = room.copy(slotsDate = "2026-10-24")
        assertTrue(ticket.accepts(updated, true, "2026-10-24"))
        assertTrue(ticket.accepts(updated, 1, "2026-10-24"))
        assertFalse(ticket.accepts(updated, false, "2026-10-24"))
        assertFalse(ticket.accepts(updated, 0, "2026-10-24"))
        assertFalse(ticket.accepts(updated, 2, "2026-10-24"))
        assertFalse(ticket.accepts(updated, -1, "2026-10-24"))
        assertFalse(ticket.accepts(updated, true, "2026-10-25"))
        assertFalse(ticket.accepts(updated.copy(slotsDate = "2026-10-25"), true, "2026-10-24"))
        assertFalse(ticket.accepts(updated.copy(slotsDate = ""), true, "2026-10-24"))
        listOf(updated.copy(id = "20"), updated.copy(name = "其他室"), updated.copy(groupId = "2"),
            updated.copy(groupName = "二楼"), updated.copy(timeMode = "free", slots = emptyList()),
            updated.copy(slots = emptyList())).forEach {
            assertFalse(ticket.accepts(it, true, "2026-10-24"))
        }
    }

    @Test fun ticketCannotBeBuiltWithAnInvalidDateOrRoom() {
        assertTrue(runCatching { RoomRulesTicket("26", "过刊阅览室", "1", "一楼", "fixed", true, "2026-02-30") }.isFailure)
        assertTrue(runCatching { RoomRulesTicket.create(draft.copy(room = room.copy(id = "invalid")),
            LocalDate.parse("2026-10-24")) }.isFailure)
    }

    @Test fun changedRulesAffectOnlyTheSavedPlansAbsoluteUsageDate() {
        val zone = ZoneId.of("Asia/Shanghai")
        val scheduled = Instant.parse("2026-10-23T16:58:00Z").toEpochMilli() // Oct 24, 00:58 in school time.
        val plan = draft.copy(reserveTomorrow = false, scheduledTimes = listOf(scheduled), scheduledAtMillis = scheduled)
        val changed = room.copy(slotsDate = "2026-10-24", slots = room.slots.toMutableList().apply {
            this[5] = this[5].copy(end = "19:29:11")
        })
        assertTrue(roomRulesAffectPlan(plan, changed, zone))
        assertFalse(roomRulesAffectPlan(plan, changed.copy(slotsDate = "2026-10-23"), zone))
        assertFalse(roomRulesAffectPlan(plan, changed.copy(slotsDate = "2026-10-25"), zone))
        assertFalse(roomRulesAffectPlan(plan.copy(enabled = false), changed, zone))
        assertFalse(roomRulesAffectPlan(plan.copy(scheduledTimes = emptyList(), scheduledAtMillis = 0), changed, zone))
        // Legacy persisted single-alarm plans use their stored epoch as well.
        assertTrue(roomRulesAffectPlan(plan.copy(scheduledTimes = emptyList()), changed, zone))
    }

    @Test fun tomorrowsOffsetIsRelativeToPersistedAlarmDayRatherThanNowOrProvenanceDate() {
        val zone = ZoneId.of("Asia/Shanghai")
        val scheduled = Instant.parse("2026-10-23T16:58:00Z").toEpochMilli()
        val plan = draft.copy(room = room.copy(slotsDate = "2026-10-20"), reserveTomorrow = true,
            scheduledTimes = listOf(scheduled), scheduledAtMillis = scheduled)
        val changed = room.copy(slotsDate = "2026-10-25", slots = room.slots.drop(1))
        assertTrue(roomRulesAffectPlan(plan, changed, zone))
        assertFalse(roomRulesAffectPlan(plan, changed.copy(slotsDate = "2026-10-24"), zone))
        assertFalse(roomRulesAffectPlan(plan.copy(reserveTomorrow = false), changed, zone))
        // The same saved instant is Oct 23 in UTC, so tomorrow there is Oct 24.
        assertFalse(roomRulesAffectPlan(plan, changed, ZoneId.of("UTC")))
        assertTrue(roomRulesAffectPlan(plan, changed.copy(slotsDate = "2026-10-24"), ZoneId.of("UTC")))
    }

    @Test fun otherRoomOrMetadataOnlyRefreshNeverAffectsAnIndependentQueuedPlan() {
        val zone = ZoneId.of("Asia/Shanghai")
        val scheduled = Instant.parse("2026-10-23T16:58:00Z").toEpochMilli()
        val plan = draft.copy(reserveTomorrow = false, scheduledTimes = listOf(scheduled), scheduledAtMillis = scheduled)
        val changed = room.copy(slotsDate = "2026-10-24", slots = room.slots.dropLast(1))
        listOf(changed.copy(id = "20"), changed.copy(name = "其他阅览室"), changed.copy(groupId = "2"),
            changed.copy(groupName = "二楼"), changed.copy(timeMode = "free", slots = emptyList()),
            changed.copy(slotsDate = ""), changed.copy(slotsDate = "2026-02-30"),
            changed.copy(slots = emptyList())).forEach { updated ->
            assertFalse(roomRulesAffectPlan(plan, updated, zone))
        }
        assertFalse(roomRulesAffectPlan(plan, room.copy(slotsDate = "2026-10-24"), zone))
        assertFalse(roomRulesAffectPlan(plan.copy(room = room.copy(id = "invalid")), changed, zone))
    }
}
