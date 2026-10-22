package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.MapSeat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FixedBookingPlanTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun oneButtonForcesOneReal0058PlanAndTenMinuteWaitWithoutChangingSeatOrUsage() {
        val draft = BookingConfig(seatNumbers = listOf("G015A"), mapSeats = listOf(MapSeat("G015A", 1, 2)),
            selectedSlots = listOf(0, 1), reserveTomorrow = false, dryRun = true,
            releaseTimes = listOf(60, 90), waitForRelease = false, scheduledTimes = listOf(1000L))
        val result = FixedBookingPlan.prepare(draft)
        assertTrue(result.enabled); assertFalse(result.dryRun); assertTrue(result.waitForRelease)
        assertEquals(listOf(58), result.times()); assertEquals(0, result.releaseHour); assertEquals(58, result.releaseMinute)
        assertTrue(result.pendingTimes().isEmpty())
        assertEquals(draft.seatNumbers, result.seatNumbers); assertEquals(draft.mapSeats, result.mapSeats)
        assertEquals(draft.selectedSlots, result.selectedSlots); assertEquals(draft.reserveTomorrow, result.reserveTomorrow)
        assertEquals(draft.room, result.room)
    }
    @Test fun beforeAtAndAfter0058UseTheNextOccurrenceAndShowTheCorrectUseDate() {
        val before = ZonedDateTime.of(2026, 12, 31, 0, 57, 59, 0, zone)
        assertEquals(before.toLocalDate(), FixedBookingPlan.nextTrigger(before).toLocalDate())
        val exact = before.plusSeconds(1)
        assertEquals("2027-01-01", FixedBookingPlan.nextTrigger(exact).toLocalDate().toString())
        assertEquals("2027-01-01", FixedBookingPlan.targetDate(BookingConfig(reserveTomorrow = false), exact).toString())
        assertEquals("2027-01-02", FixedBookingPlan.targetDate(BookingConfig(reserveTomorrow = true), exact).toString())
        assertEquals(0, FixedBookingPlan.nextTrigger(before).hour)
        assertEquals(58, FixedBookingPlan.nextTrigger(before).minute)
    }
    @Test fun schedulerRegistersOneTaskAndConsumingItDoesNotRepeatTomorrow() {
        val app = RuntimeEnvironment.getApplication()
        val scheduler = BookingScheduler(app)
        val trigger = scheduler.schedule(BookingConfig(releaseTimes = listOf(60, 90), dryRun = true))
        val planned = ConfigStore(app).load()
        assertEquals(0, trigger.hour); assertEquals(58, trigger.minute)
        assertEquals(1, planned.pendingTimes().size); assertFalse(planned.dryRun); assertTrue(planned.waitForRelease)
        assertTrue(scheduler.consume(planned.scheduledAtMillis))
        assertFalse(ConfigStore(app).load().enabled)
        assertTrue(ConfigStore(app).load().pendingTimes().isEmpty())
        assertFalse(scheduler.consume(planned.scheduledAtMillis))
    }
}
