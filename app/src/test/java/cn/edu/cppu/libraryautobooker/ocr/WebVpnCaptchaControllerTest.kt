package cn.edu.cppu.libraryautobooker.ocr

import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Fake JS callbacks only. No JNI inference, network connection or actual CAPTCHA is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebVpnCaptchaControllerTest {
    private class FakeView(context: Context) : WebView(context) {
        data class Call(val script: String, val callback: ValueCallback<String>?)
        var address = "https://sso.cppu.edu.cn/tpass/login"
        val calls = mutableListOf<Call>()
        override fun getUrl(): String = address
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            calls.add(Call(script, resultCallback))
        }
        fun answer(index: Int, value: String) { calls[index].callback?.onReceiveValue(value) }
    }

    private class Fixture : AutoCloseable {
        val view = FakeView(RuntimeEnvironment.getApplication())
        val statuses = mutableListOf<String>()
        val controller = WebVpnCaptchaController(RuntimeEnvironment.getApplication(), view) { statuses.add(it) }
        fun start() {
            controller.setEnabled(true)
            controller.onPageStarted(view.address)
            controller.onPageFinished(view.address)
            controller.onResume()
        }
        fun beginSnapshot() {
            start()
            assertEquals(1, view.calls.size)
            view.answer(0, "{\"kind\":\"installed\"}")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, view.calls.size)
            assertTrue(view.calls[1].script.contains("snapshot()"))
        }
        override fun close() { controller.close(); view.destroy() }
    }

    @Test fun objectAndWrappedJsonResponsesBothSupportReadOnlyWaiting() {
        listOf("{\"kind\":\"waiting\"}", JSONObject.quote("{\"kind\":\"waiting\"}")).forEach { value ->
            Fixture().use { f ->
                f.beginSnapshot()
                f.view.answer(1, value)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
                assertEquals(3, f.view.calls.size)
                assertTrue(f.statuses.last().contains("不会额外刷新图片"))
                assertFalse(f.view.calls.any { it.script.contains(".fill(") })
            }
        }
    }

    @Test fun nullMalformedAndOversizedCallbacksStopWithoutFilling() {
        listOf("null", "not JSON", "x".repeat(400_000)).forEach { value ->
            Fixture().use { f ->
                f.beginSnapshot()
                f.view.answer(1, value)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(2, f.view.calls.size)
                assertTrue(f.statuses.last().contains("请手动输入"))
            }
        }
    }

    @Test fun blockedImageReadStopsRatherThanRepeatedlyCapturing() {
        Fixture().use { f ->
            f.beginSnapshot()
            f.view.answer(1, "{\"kind\":\"unavailable\"}")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(2, f.view.calls.size)
            assertTrue(f.statuses.last().contains("自动识别已停止"))
        }
    }

    @Test fun staleCallbackAfterPauseNavigationOrOptOutIsIgnored() {
        repeat(3) { cause ->
            Fixture().use { f ->
                f.beginSnapshot()
                when (cause) {
                    0 -> f.controller.onPause()
                    1 -> {
                        f.view.address = "https://other.cppu.edu.cn/tpass/login"
                        f.controller.onPageStarted(f.view.address)
                    }
                    else -> f.controller.setEnabled(false)
                }
                val statusCount = f.statuses.size
                // Even a malformed image result would invoke recognition if the callback were not stale.
                f.view.answer(1, "{\"kind\":\"ready\",\"imageKey\":\"a\",\"token\":\"b\",\"data\":\"!\"}")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(2, f.view.calls.size)
                assertEquals(statusCount, f.statuses.size)
            }
        }
    }

    @Test fun explicitRetryPreservesManualContentAndDoesNotSubmitLogin() {
        Fixture().use { f ->
            f.start()
            f.controller.retry()
            val install = f.view.calls.lastIndex
            f.view.answer(install, "{\"kind\":\"installed\"}")
            assertTrue(f.view.calls.last().script.contains("reset()"))
            f.view.answer(f.view.calls.lastIndex, "{\"kind\":\"manual\"}")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertTrue(f.statuses.last().contains("保留手动输入"))
            assertFalse(f.view.calls.any { it.script.contains(".submit(") || it.script.contains(".fill(") })
        }
    }

    @Test fun closedControllerDoesNotExecuteEvenWhenAQueuedCallbackReturns() {
        Fixture().use { f ->
            f.beginSnapshot()
            f.controller.close()
            val statusCount = f.statuses.size
            f.view.answer(1, "{\"kind\":\"waiting\"}")
            f.controller.retry()
            f.controller.onResume()
            f.controller.setEnabled(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(2, f.view.calls.size)
            assertEquals(statusCount, f.statuses.size)
        }
    }
}
