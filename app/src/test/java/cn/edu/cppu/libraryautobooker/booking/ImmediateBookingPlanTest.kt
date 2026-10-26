package cn.edu.cppu.libraryautobooker.booking

import androidx.core.content.ContextCompat
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.SeatMapCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImmediateBookingPlanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun draft(): BookingConfig {
        val map = SeatMapCatalog.load(app).maps.first()
        val seat = map.seats.first { map.issue(it) == null }
        return map.bind(BookingConfig(room = map.room, seatNumbers = listOf(seat.id),
            selectedSlots = listOf(0), reserveTomorrow = false))
    }

    @Test fun bothModesHaveDistinctDatesAndTheManualTargetCannotShiftAfterMidnight() {
        val today = LocalDate.of(2026, 10, 23)
        val now = ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        val initial = draft()
        assertEquals(today, ImmediateBookingPlan.targetDate(initial, today))
        assertEquals(today.plusDays(1), FixedBookingPlan.targetDate(initial, now))
        assertEquals(today.plusDays(1), ImmediateBookingPlan.targetDate(initial.copy(reserveTomorrow = true), today))
        val request = requireNotNull(ImmediateBookingPlan.decode(ImmediateBookingPlan.encode(initial, today)))
        assertFalse(requireNotNull(ImmediateBookingPlan.decode(ImmediateBookingPlan.encode(
            initial.copy(webVpnBackup = false), today))).config.webVpnBackup)
        val run = BookingSequence(request.config, request.anchorDate)
        assertEquals(today, run.targetDate)
        assertNull(run.configForDate(today.plusDays(1))) // Never silently change expired "today" to tomorrow.
    }

    @Test fun everyRoomUsesAnImmutableExactSeatAndUsageSnapshotWithoutATimerOrWait() {
        SeatMapCatalog.load(app).maps.forEach { map ->
            val seat = map.seats.first { map.issue(it) == null }
            val initial = map.bind(BookingConfig(room = map.room, seatNumbers = listOf(seat.id),
                selectedSlots = listOf(0), waitForRelease = true, dryRun = true,
                scheduledAtMillis = 1000L, scheduledTimes = listOf(1000L), enabled = true))
            val decoded = requireNotNull(ImmediateBookingPlan.decode(ImmediateBookingPlan.encode(initial)))
            assertEquals(initial.room, decoded.config.room)
            assertEquals(initial.mapSeats, decoded.config.mapSeats)
            assertEquals(initial.seatNumbers, decoded.config.seatNumbers)
            assertEquals(initial.selectedSlots, decoded.config.selectedSlots)
            assertFalse(decoded.config.enabled); assertFalse(decoded.config.dryRun)
            assertFalse(decoded.config.waitForRelease); assertTrue(decoded.config.pendingTimes().isEmpty())
        }
    }

    @Test fun launchingManualRequestLeavesThePending0058PlanAndItsTargetUntouched() {
        val store = ConfigStore(app)
        val scheduled = FixedBookingPlan.prepare(draft()).withPending(listOf(System.currentTimeMillis() + 3600000))
        store.save(scheduled)
        val direct = draft().copy(reserveTomorrow = true, selectedSlots = listOf(1))
        ContextCompat.startForegroundService(app, ImmediateBookingPlan.intent(app, direct))
        val sent = Shadows.shadowOf(app).nextStartedService
        assertEquals(BookingService.ACTION_RUN_ONCE, sent.action)
        val decoded = requireNotNull(ImmediateBookingPlan.decode(sent.getStringExtra(ImmediateBookingPlan.EXTRA_REQUEST)))
        assertTrue(decoded.config.reserveTomorrow); assertEquals(listOf(1), decoded.config.selectedSlots)
        assertEquals(scheduled, store.load())
    }

    @Test fun corruptOrMissingPayloadNeverFallsBackToAnotherReservationTarget() {
        assertNull(ImmediateBookingPlan.decode(null))
        assertNull(ImmediateBookingPlan.decode("{}"))
        assertNull(ImmediateBookingPlan.decode("x".repeat(32001)))
        val value = JSONObject(ImmediateBookingPlan.encode(draft()))
        value.getJSONArray("mapSeats").getJSONObject(0).put("id", "wrong")
        assertNull(ImmediateBookingPlan.decode(value.toString()))
        assertTrue(runCatching { ImmediateBookingPlan.encode(draft().copy(seatNumbers = emptyList())) }.isFailure)
    }
}
