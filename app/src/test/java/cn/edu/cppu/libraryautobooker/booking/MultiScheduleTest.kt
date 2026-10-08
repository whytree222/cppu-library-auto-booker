package cn.edu.cppu.libraryautobooker.booking

import android.content.Intent
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MultiScheduleTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun legacySingleTimeStillWorks() {
        val store = ConfigStore(app)
        store.save(BookingConfig(enabled = true, releaseHour = 9, releaseMinute = 30, scheduledAtMillis = 1000))
        assertEquals(listOf(570), store.load().times())
        assertEquals(listOf(1000L), store.load().pendingTimes())
    }

    @Test fun multipleTimesArePersistedAndChronologicallyScheduled() {
        val scheduler = BookingScheduler(app)
        scheduler.schedule(BookingConfig(releaseTimes = listOf(1200, 360, 360, 800)))
        val saved = ConfigStore(app).load()
        assertEquals(listOf(360, 800, 1200), saved.times())
        assertEquals(3, saved.pendingTimes().size)
        assertEquals(saved.pendingTimes().sorted(), saved.pendingTimes())
        assertEquals(saved.pendingTimes().first(), saved.scheduledAtMillis)
    }

    @Test fun consumingOneLeavesLaterTasksAndIgnoresDuplicateAndStaleAlarms() {
        val first = System.currentTimeMillis() + 60000
        val second = first + 3600000
        ConfigStore(app).save(BookingConfig().withPending(listOf(first, second)))
        val scheduler = BookingScheduler(app)
        assertFalse(scheduler.consume(second))
        assertTrue(scheduler.consume(first))
        assertTrue(ConfigStore(app).load().enabled)
        assertEquals(listOf(second), ConfigStore(app).load().pendingTimes())
        assertFalse(scheduler.consume(first))
        assertTrue(scheduler.consume(second))
        assertFalse(ConfigStore(app).load().enabled)
        assertTrue(ConfigStore(app).load().pendingTimes().isEmpty())
    }

    @Test fun receiverStartsFirstWhileKeepingSecond() {
        val first = System.currentTimeMillis()
        val second = first + 3600000
        ConfigStore(app).save(BookingConfig().withPending(listOf(first, second)))
        BookingAlarmReceiver().onReceive(app, Intent().putExtra("expected_at", first))
        assertEquals(listOf(second), ConfigStore(app).load().pendingTimes())
        assertEquals(first, Shadows.shadowOf(app).nextStartedService.getLongExtra("expected_at", 0))
    }

    @Test fun bootDiscardsOnlyExpiredTimes() {
        val now = System.currentTimeMillis()
        val later = now + 3600000
        ConfigStore(app).save(BookingConfig().withPending(listOf(now - 60000, later)))
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(listOf(later), ConfigStore(app).load().pendingTimes())
        assertTrue(ConfigStore(app).load().enabled)
    }

    @Test fun clearingQueueAndReschedulingReplacesOldTimes() {
        val scheduler = BookingScheduler(app)
        scheduler.schedule(BookingConfig(releaseTimes = listOf(360, 700)))
        scheduler.schedule(BookingConfig(releaseTimes = listOf(900)))
        assertEquals(1, ConfigStore(app).load().pendingTimes().size)
        scheduler.cancel()
        ConfigStore(app).save(ConfigStore(app).load().withPending(emptyList()))
        assertFalse(scheduler.consume(ConfigStore(app).load().scheduledAtMillis))
    }
}
