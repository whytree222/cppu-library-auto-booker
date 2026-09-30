package cn.edu.cppu.libraryautobooker.booking

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackgroundStatusTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun scheduledAlarmAndDisplayedTimeAgree() {
        val scheduler = BookingScheduler(app)
        val trigger = scheduler.schedule(BookingConfig(enabled = true, dryRun = false))
        val saved = ConfigStore(app).load()
        assertEquals(trigger.toInstant().toEpochMilli(), saved.scheduledAtMillis)
        val alarm = Shadows.shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextScheduledAlarm
        assertNotNull("An exact alarm must be registered", alarm)
        assertEquals(saved.scheduledAtMillis, requireNotNull(alarm).triggerAtTime)
        assertEquals("SCHEDULED", RuntimeStore(app).prefs.getString("task_state", ""))
        assertTrue(RuntimeStore(app).prefs.getString("task_detail", "")!!.contains("真实预约"))
    }

    @Test fun receiverRetainsTriggerEvidenceAndPlannedDate() {
        val due = System.currentTimeMillis()
        ConfigStore(app).save(BookingConfig(enabled = true, scheduledAtMillis = due))
        BookingAlarmReceiver().onReceive(app, Intent().putExtra("expected_at", due))
        assertFalse(ConfigStore(app).load().enabled)
        assertEquals(due, ConfigStore(app).load().scheduledAtMillis)
        assertEquals("TRIGGERED", RuntimeStore(app).prefs.getString("task_state", ""))
        val service = Shadows.shadowOf(app).nextStartedService
        assertEquals(BookingService.ACTION_SCHEDULED, service.action)
        assertEquals(due, service.getLongExtra("expected_at", 0))
    }

    @Test fun staleAlarmCannotLaunchChangedTask() {
        ConfigStore(app).save(BookingConfig(enabled = true, scheduledAtMillis = 2000L))
        BookingAlarmReceiver().onReceive(app, Intent().putExtra("expected_at", 1000L))
        assertNull(Shadows.shadowOf(app).nextStartedService)
        assertTrue(ConfigStore(app).load().enabled)
    }

    @Test fun testAlarmDoesNotConsumeRealBooking() {
        val config = BookingConfig(enabled = true, scheduledAtMillis = System.currentTimeMillis() + 3_600_000L)
        ConfigStore(app).save(config)
        BookingScheduler(app).scheduleWakeTest()
        BookingAlarmReceiver().onReceive(app, Intent().setAction(BookingScheduler.ACTION_WAKE_TEST))
        assertEquals(config, ConfigStore(app).load())
        assertEquals(BookingService.ACTION_WAKE_TEST, Shadows.shadowOf(app).nextStartedService.action)
        assertEquals("TRIGGERED", RuntimeStore(app).prefs.getString("test_state", ""))
    }

    @Test fun testCannotCompeteWithImminentRealAlarm() {
        ConfigStore(app).save(BookingConfig(enabled = true, scheduledAtMillis = System.currentTimeMillis() + 120_000L))
        assertThrows(IllegalArgumentException::class.java) { BookingScheduler(app).scheduleWakeTest() }
    }

    @Test fun networkFailureAndMalformedResponseDoNotClaimLogout() {
        assertEquals("unknown", SessionChecker.decode(503, null, "").first)
        assertEquals("unknown", SessionChecker.decode(200, null, "<html>portal</html>").first)
        assertEquals("unknown", SessionChecker.decode(200, null, "{}").first)
        assertEquals("expired", SessionChecker.decode(302, "/login", "").first)
        assertEquals("expired", SessionChecker.decode(200, null, "{\"ReturnValue\":0}").first)
        assertEquals("valid", SessionChecker.decode(200, null, "{\"ReturnValue\":1}").first)
    }
}

