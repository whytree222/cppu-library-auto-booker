package cn.edu.cppu.libraryautobooker

import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Only synthetic input scheduling; this is not a Chromium/real school login test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebViewLoginTapTest {
    private class FakeView : WebView(RuntimeEnvironment.getApplication()) {
        var address = "https://sso.cppu.edu.cn/tpass/login"
        var consume = true
        var throwOnDown = false
        val events = mutableListOf<List<Number>>()
        override fun getUrl() = address
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            events.add(listOf(event.actionMasked, event.x, event.y, event.source, event.getToolType(0), event.downTime))
            if (throwOnDown && event.actionMasked == MotionEvent.ACTION_DOWN) throw IllegalStateException("synthetic")
            return consume
        }
    }
    private val point = WebViewLoginTap.Target(100.0, 200.0, 360.0, 640.0)
    private fun withTap(block: (FakeView, WebViewLoginTap) -> Unit) {
        val view = FakeView()
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 1920)
        // The stub WebView provider does not lay out Chromium. Model bounds with
        // public View setters; do not change the production nonzero-layout guard.
        view.left = 0; view.top = 0; view.right = 1080; view.bottom = 1920
        val tap = WebViewLoginTap(view)
        try { block(view, tap) } finally { tap.close(); view.destroy() }
    }

    @Test fun oneDownUpUsesWebViewLocalPixelsFingerAndTouchscreen() = withTap { view, tap ->
        val completed = mutableListOf<Boolean>()
        tap.tap(point, { true }, completed::add)
        assertEquals("layout=${view.width}x${view.height}, enabled=${view.isEnabled}, visibility=${view.visibility}, scroll=${view.scrollX},${view.scrollY}", 1, view.events.size)
        assertTrue(completed.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), view.events.map { it[0] })
        assertEquals(300f, view.events[0][1]); assertEquals(600f, view.events[0][2])
        assertEquals(InputDevice.SOURCE_TOUCHSCREEN, view.events[0][3])
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, view.events[0][4])
        assertEquals(view.events[0][5], view.events[1][5]); assertEquals(listOf(true), completed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(2, view.events.size)
    }

    @Test fun untrustedUrlInvalidCoordinatesAndAmbiguousScaleNeverDispatchInput() {
        val invalid = listOf(point.copy(x = Double.NaN), point.copy(y = -1.0), point.copy(x = 360.0),
            point.copy(viewportHeight = 0.0), point.copy(viewportHeight = 400.0))
        invalid.forEach { target -> withTap { view, tap ->
            var result: Boolean? = null; tap.tap(target, { true }, { result = it })
            assertEquals(false, result); assertTrue(view.events.isEmpty())
        } }
        withTap { view, tap ->
            view.address = "https://other.example/tpass/login"
            tap.tap(point, { true }) { assertFalse(it) }; assertTrue(view.events.isEmpty())
        }
    }

    @Test fun lifecycleUrlLayoutScrollOrAccountChangesCancelInsteadOfLiftingTheFinger() {
        repeat(5) { change -> withTap { view, tap ->
            var valid = true; val results = mutableListOf<Boolean>()
            tap.tap(point, { valid }, results::add)
            when (change) {
                0 -> valid = false
                1 -> view.address += "?changed=synthetic"
                2 -> { view.right = 900; view.bottom = 1600 }
                3 -> view.scrollTo(0, 1)
                else -> tap.cancel()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), view.events.map { it[0] })
            assertEquals(listOf(false), results)
        } }
    }

    @Test fun downRejectionOrExceptionNeverCausesASecondGestureOrFallback() {
        repeat(2) { mode -> withTap { view, tap ->
            view.consume = mode != 0; view.throwOnDown = mode == 1
            val results = mutableListOf<Boolean>(); tap.tap(point, { true }, results::add)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), view.events.map { it[0] })
            assertEquals(listOf(false), results)
        } }
    }

    @Test fun closeCancelsExactlyOnceAndRejectsAnyFurtherInput() = withTap { view, tap ->
        val results = mutableListOf<Boolean>(); tap.tap(point, { true }, results::add)
        tap.close(); tap.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(false), results); assertEquals(2, view.events.size)
        tap.tap(point, { true }, results::add)
        assertEquals(listOf(false, false), results); assertEquals(2, view.events.size)
    }
}
