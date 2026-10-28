package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import android.os.Looper
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SelectionWatchdogTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val runtime get() = RuntimeStore(app)
    private fun advance(seconds: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Before fun clearState() {
        app.getSharedPreferences("runtime_status", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun stalledPageFailsOnNativeDeadlineWithoutAnyJavascriptCallback() {
        val controller = Robolectric.buildService(BookingService::class.java).create()
        try {
            controller.get().apply { resetSelectionWatchdog(); watchSelection(0, "date") }
            advance(24)
            assertNotEquals("FAILED", runtime.prefs.getString("task_state", ""))
            advance(1)
            assertEquals("FAILED", runtime.prefs.getString("task_state", ""))
            assertTrue(runtime.prefs.getString("task_detail", "")!!.contains("加载并选择使用日期"))
            assertTrue(runtime.prefs.getString("task_detail", "")!!.contains("未提交座位预约"))
        } finally { controller.destroy() }
    }

    @Test fun advancingStageInvalidatesEarlierTimeoutButRepeatingStageCannotExtendIt() {
        val controller = Robolectric.buildService(BookingService::class.java).create()
        try {
            val service = controller.get()
            service.resetSelectionWatchdog(); service.watchSelection(0, "date")
            advance(20)
            service.watchSelection(0, "slots")
            advance(5)
            assertNotEquals("FAILED", runtime.prefs.getString("task_state", ""))
            service.watchSelection(0, "slots")
            advance(20)
            assertEquals("FAILED", runtime.prefs.getString("task_state", ""))
            assertTrue(runtime.prefs.getString("task_detail", "")!!.contains("加载并选择使用时段"))
        } finally { controller.destroy() }
    }

    @Test fun seatMapArrivalStopsWatchdogAndIgnoresLateStageCallbacks() {
        val controller = Robolectric.buildService(BookingService::class.java).create()
        try {
            val service = controller.get()
            service.resetSelectionWatchdog(); service.watchSelection(0, "navigate")
            advance(10)
            service.stopSelectionWatchdog()
            service.watchSelection(0, "date")
            advance(30)
            assertNotEquals("FAILED", runtime.prefs.getString("task_state", ""))
        } finally { controller.destroy() }
    }

    @Test fun unknownStagesAndStaleGenerationsCannotStartOrExtendWatchdog() {
        val controller = Robolectric.buildService(BookingService::class.java).create()
        try {
            val service = controller.get()
            service.resetSelectionWatchdog()
            service.watchSelection(1, "date"); service.watchSelection(0, "untrusted-stage")
            advance(30)
            assertNotEquals("FAILED", runtime.prefs.getString("task_state", ""))
            service.watchSelection(0, "date")
            advance(20)
            service.watchSelection(1, "slots"); service.watchSelection(0, "untrusted-stage")
            advance(5)
            assertEquals("FAILED", runtime.prefs.getString("task_state", ""))
        } finally { controller.destroy() }
    }
}
