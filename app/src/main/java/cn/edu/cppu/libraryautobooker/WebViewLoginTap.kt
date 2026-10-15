package cn.edu.cppu.libraryautobooker

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import cn.edu.cppu.libraryautobooker.ocr.CaptchaPagePolicy
import kotlin.math.abs

/** One input sequence inside this application's verified SSO WebView, never system-wide input. */
internal class WebViewLoginTap(private val view: WebView) : AutoCloseable {
    data class Target(val x: Double, val y: Double, val viewportWidth: Double, val viewportHeight: Double)
    private val main = Handler(Looper.getMainLooper())
    private var current: Gesture? = null
    private var closed = false

    private class Gesture(val url: String, val width: Int, val height: Int,
        val scrollX: Int, val scrollY: Int, val x: Float, val y: Float,
        val isCurrent: () -> Boolean, val completion: (Boolean) -> Unit) {
        val downTime = SystemClock.uptimeMillis()
        lateinit var up: Runnable
        var down = false
    }

    /** `true` means DOWN/UP was delivered, not that a DOM click, POST, or login succeeded. */
    fun tap(target: Target, isCurrent: () -> Boolean, onComplete: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val url = view.url
        val numbers = listOf(target.x, target.y, target.viewportWidth, target.viewportHeight)
        if (closed || current != null || !isCurrent() || !CaptchaPagePolicy.isTrusted(url) ||
            numbers.any { !it.isFinite() } || target.viewportWidth <= 0 || target.viewportHeight <= 0 ||
            target.viewportWidth > 100_000 || target.viewportHeight > 100_000 ||
            target.x <= 0 || target.x >= target.viewportWidth || target.y <= 0 || target.y >= target.viewportHeight ||
            view.width <= 0 || view.height <= 0 || !view.isEnabled || view.visibility != View.VISIBLE) {
            onComplete(false); return
        }
        val sx = view.width / target.viewportWidth
        val sy = view.height / target.viewportHeight
        if (abs(sx - sy) / maxOf(sx, sy) > 0.025) { onComplete(false); return }
        val gesture = Gesture(url!!, view.width, view.height, view.scrollX, view.scrollY,
            (target.x * sx).toFloat(), (target.y * sy).toFloat(), isCurrent, onComplete)
        gesture.up = Runnable {
            if (current !== gesture) return@Runnable
            if (!unchanged(gesture)) { cancel(); return@Runnable }
            // Release before UP: a synchronous navigation must not cancel the new document.
            current = null
            val sent = runCatching { dispatch(gesture, MotionEvent.ACTION_UP) }.getOrDefault(false)
            gesture.completion(sent)
        }
        current = gesture
        view.requestFocus() // Normal app focus only; never fabricate window/attachment callbacks.
        if (current !== gesture) return
        if (!unchanged(gesture)) {
            current = null; gesture.completion(false); return
        }
        gesture.down = true // Any exception after dispatch starts is an uncertain input outcome.
        val consumed = runCatching { dispatch(gesture, MotionEvent.ACTION_DOWN) }.getOrDefault(false)
        if (current !== gesture) return
        if (!consumed) { cancel(); return }
        main.postDelayed(gesture.up, 100L)
    }

    private fun unchanged(g: Gesture) = !closed && g.isCurrent() && view.url == g.url &&
        CaptchaPagePolicy.isTrusted(view.url) && view.width == g.width && view.height == g.height &&
        view.scrollX == g.scrollX && view.scrollY == g.scrollY && view.isEnabled && view.visibility == View.VISIBLE

    private fun dispatch(g: Gesture, action: Int): Boolean {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = g.x; y = g.y; pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f; size = 1f
        }
        val event = MotionEvent.obtain(g.downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val gesture = current ?: return
        current = null
        main.removeCallbacks(gesture.up)
        if (gesture.down) runCatching { dispatch(gesture, MotionEvent.ACTION_CANCEL) }
        gesture.completion(false)
    }

    override fun close() { closed = true; cancel() }
}
