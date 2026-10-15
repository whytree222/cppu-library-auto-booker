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

    @Test fun temporaryImageReadFailureRetriesTwiceWithoutRefreshingThenStops() {
        Fixture().use { f ->
            f.beginSnapshot()
            f.view.answer(1, "{\"kind\":\"unavailable\"}")
            repeat(2) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
                f.view.answer(f.view.calls.lastIndex, "{\"kind\":\"unavailable\"}")
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(4, f.view.calls.size)
            assertTrue(f.statuses.last().contains("重试已达上限"))
            assertFalse(f.view.calls.any { it.script.contains(".refresh()") })
        }
    }

    private fun ready(image: String) = """{"kind":"ready","imageKey":"$image","token":"synthetic-token","data":"YQ=="}"""
    private fun awaitMain(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Expected synthetic worker result on main looper", condition())
    }

    @Test fun unreliableInferenceRefreshesOnceAndFillsTheNextReliableImageAutomatically() {
        Fixture().use { f ->
            var attempts = 0
            f.controller.recognizeForTest = {
                attempts++
                if (attempts == 1) DdddOcrEngine.Result("123", 0.9, "123", 0)
                else DdddOcrEngine.Result("1234", 0.9, "1234", 0)
            }
            f.beginSnapshot()
            f.view.answer(1, ready("image-a"))
            awaitMain { f.statuses.any { it.contains("自动刷新验证码") } }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            assertTrue(f.view.calls.last().script.contains(".refresh()"))
            f.view.answer(f.view.calls.lastIndex, """{"kind":"refreshing"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            f.view.answer(f.view.calls.lastIndex, ready("image-b"))
            awaitMain { f.view.calls.last().script.contains(".fill(") }
            f.view.answer(f.view.calls.lastIndex, """{"kind":"filled"}""")
            assertTrue(f.statuses.last().contains("已自动填写"))
            assertEquals(2, attempts)
            assertEquals(1, f.view.calls.count { it.script.contains(".refresh()") })
            assertFalse(f.view.calls.any { it.script.contains(".submit(") })
        }
    }

    @Test fun threeUnreliableImagesUseOnlyTwoRefreshesAndNeverFillOrSubmit() {
        Fixture().use { f ->
            f.controller.recognizeForTest = { DdddOcrEngine.Result("1234", 0.1, "1234", 0) }
            f.beginSnapshot()
            for (image in 0..2) {
                f.view.answer(f.view.calls.lastIndex, ready("image-$image"))
                if (image < 2) {
                    awaitMain { f.statuses.last().contains("自动刷新验证码") }
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
                    f.view.answer(f.view.calls.lastIndex, """{"kind":"refreshing"}""")
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
                } else awaitMain { f.statuses.last().contains("已达上限") }
            }
            val calls = f.view.calls.size
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertEquals(calls, f.view.calls.size)
            assertEquals(2, f.view.calls.count { it.script.contains(".refresh()") })
            assertFalse(f.view.calls.any { it.script.contains(".fill(") || it.script.contains(".submit(") })
        }
    }

    @Test fun unchangedImageAfterRefreshIsNotRecognizedAgainAndTimesOut() {
        Fixture().use { f ->
            var attempts = 0
            f.controller.recognizeForTest = { attempts++; DdddOcrEngine.Result("123", 0.9, "123", 0) }
            f.beginSnapshot(); f.view.answer(1, ready("image-a"))
            awaitMain { f.statuses.last().contains("自动刷新验证码") }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            f.view.answer(f.view.calls.lastIndex, """{"kind":"refreshing"}""")
            repeat(10) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
                f.view.answer(f.view.calls.lastIndex, ready("image-a"))
            }
            assertEquals(1, attempts)
            assertTrue(f.statuses.last().contains("图片未更新"))
        }
    }

    @Test fun pauseOptOutAndNavigationCancelTheDelayedAutomaticRefresh() {
        repeat(3) { cause ->
            Fixture().use { f ->
                f.controller.recognizeForTest = { DdddOcrEngine.Result("123", 0.9, "123", 0) }
                f.beginSnapshot(); f.view.answer(1, ready("image-a"))
                awaitMain { f.statuses.last().contains("自动刷新验证码") }
                when (cause) {
                    0 -> f.controller.onPause()
                    1 -> f.controller.setEnabled(false)
                    else -> f.controller.onPageStarted("https://other.example/")
                }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
                assertFalse(f.view.calls.any { it.script.contains(".refresh()") })
            }
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
