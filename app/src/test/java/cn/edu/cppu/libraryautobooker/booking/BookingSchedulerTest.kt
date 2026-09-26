package cn.edu.cppu.libraryautobooker.booking

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BookingSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun schedulesLaterToday() {
        val now = ZonedDateTime.of(2026, 9, 26, 5, 50, 0, 0, zone)
        val result = BookingScheduler.nextTrigger(6, 0, now)
        assertEquals(26, result.dayOfMonth)
        assertEquals(6, result.hour)
    }

    @Test fun schedulesTomorrowAfterReleaseTime() {
        val now = ZonedDateTime.of(2026, 9, 26, 6, 0, 1, 0, zone)
        val result = BookingScheduler.nextTrigger(6, 0, now)
        assertEquals(27, result.dayOfMonth)
    }
}
