package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.BookingConfig
import java.time.ZonedDateTime

/** A button press opts into one real reservation at the next local 00:58, not a daily loop. */
object FixedBookingPlan {
    const val MINUTE_OF_DAY = 58
    fun nextTrigger(now: ZonedDateTime = ZonedDateTime.now()) = BookingScheduler.nextTrigger(0, 58, now)
    fun targetDate(draft: BookingConfig, now: ZonedDateTime = ZonedDateTime.now()) =
        nextTrigger(now).toLocalDate().plusDays(if (draft.reserveTomorrow) 1 else 0)
    fun prepare(draft: BookingConfig) = draft.copy(
        enabled = true, dryRun = false, waitForRelease = true,
        releaseHour = 0, releaseMinute = 58, releaseTimes = listOf(MINUTE_OF_DAY),
        scheduledAtMillis = 0L, scheduledTimes = emptyList()
    )
}
