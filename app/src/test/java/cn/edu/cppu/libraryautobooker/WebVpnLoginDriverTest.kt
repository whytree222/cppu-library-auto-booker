package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.ocr.CaptchaPagePolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

/** Synthetic accounts and fake JS/server callbacks only; no real requests or OCR inference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebVpnLoginDriverTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearFixturePreferences() {
        Kind.entries.forEach { app.getSharedPreferences(it.storageName, Context.MODE_PRIVATE).edit().clear().commit() }
        app.getSharedPreferences("webvpn_ocr", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class FakeView(context: Context) : WebView(context) {
        data class Call(val script: String, val callback: ValueCallback<String>?, var answered: Boolean = false)
        var address = SSO_URL
        val calls = mutableListOf<Call>()
        val navigations = mutableListOf<String>()
        val touches = mutableListOf<Int>()
        var acceptTouch = true
        var throwAfterDown = false
        override fun getUrl(): String = address
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            calls.add(Call(script, resultCallback))
        }
        override fun loadUrl(url: String) { navigations.add(url); address = url }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touches.add(event.actionMasked)
            if (throwAfterDown && event.actionMasked == MotionEvent.ACTION_DOWN) throw IllegalStateException("Synthetic input failure")
            return acceptTouch
        }
        fun answerFirst(match: (String) -> Boolean, json: String) {
            val call = calls.firstOrNull { !it.answered && it.callback != null && match(it.script) }
                ?: throw AssertionError("No matching pending fake JavaScript callback")
            call.answered = true
            call.callback?.onReceiveValue(json)
        }
        fun activationCount() = calls.count { it.script.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") }
    }

    private inner class Fixture(saveSso: Boolean = true, saveLibrary: Boolean = true) : AutoCloseable {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val sso = WebVpnCredentialStore(app, Kind.SSO, { key })
        val library = WebVpnCredentialStore(app, Kind.LIBRARY, { key })
        val view = FakeView(app)
        val statuses = mutableListOf<String>()
        val results = mutableListOf<SessionChecker.Result>()
        val probes = ArrayDeque<(SessionChecker.Result) -> Unit>()
        val driver: WebVpnLoginDriver

        init {
            if (saveSso) sso.save(SSO_USER, SSO_PASSWORD)
            if (saveLibrary) library.save(LIBRARY_USER, LIBRARY_PASSWORD)
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1920)
            // Supply bounds for the fake provider via public View setters only.
            view.left = 0; view.top = 0; view.right = 1080; view.bottom = 1920
            driver = WebVpnLoginDriver(app, view, { statuses.add(it) }, { results.add(it) },
                ssoStore = sso, libraryStore = library, probe = { probes.addLast(it) })
        }

        fun start(url: String) {
            view.address = url
            driver.onPageStarted(url)
            driver.onResume()
            driver.onPageFinished(url)
        }

        fun finish(url: String) {
            view.address = url
            driver.onPageStarted(url)
            driver.onPageFinished(url)
        }

        fun answerProbe(state: String, auth: String?, networkFailure: Boolean = false) {
            check(probes.isNotEmpty()) { "Expected a fake school session probe" }
            probes.removeFirst().invoke(SessionChecker.Result(state, "Synthetic school result", networkFailure = networkFailure, vpnAuthState = auth))
        }

        fun answerRetire(kind: String = "ready") {
            view.answerFirst({ it.contains("?.abandonUnsubmittedActivation()") }, """{"kind":"$kind"}""")
        }

        fun answerRearm(kind: String = "activation-ready", detailCode: String? = null) {
            view.answerFirst({ it.contains("?.rearmActivation()") },
                if (detailCode == null) """{"kind":"$kind"}""" else """{"kind":"$kind","detailCode":"$detailCode"}""")
        }

        fun answerSchoolHandler(kind: String = "submitted", detailCode: String? = null) {
            view.answerFirst({ it.contains("?.schoolHandlerSubmit()") },
                if (detailCode == null) """{"kind":"$kind"}""" else """{"kind":"$kind","detailCode":"$detailCode"}""")
        }

        fun answerOutcome(detailCode: String, reason: String? = null) {
            view.answerFirst({ it.contains("?.activationOutcome()") },
                if (reason == null) """{"kind":"activation-observed","detailCode":"$detailCode"}"""
                else """{"kind":"activation-observed","detailCode":"$detailCode","reason":"$reason"}""")
        }

        fun answerUnsubmittedRestart(kind: String = "ready") {
            view.answerFirst({ it.contains("?.unsubmittedRestartState()") }, """{"kind":"$kind"}""")
        }

        fun prepareSso() {
            view.answerFirst({ it.contains("sso-helper-ready") }, "{\"kind\":\"installed\"}")
            view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepare(") }, "{\"kind\":\"prepared\"}")
        }

        fun completeCaptcha() {
            val expectedSubmissions = view.activationCount() + 1
            view.answerFirst({ it.contains("Object.defineProperty(window, 'LibraryCaptchaOcr'") }, "{\"kind\":\"installed\"}")
            shadowOf(Looper.getMainLooper()).idle()
            view.answerFirst({ it.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") }, "{\"kind\":\"filled\"}")
            assertEquals(expectedSubmissions, view.activationCount())
        }

        fun completeCaptchaAndSubmitSso() {
            completeCaptcha()
            activateOriginalButton()
        }

        fun activateOriginalButton(answerOutcome: Boolean = true) {
            view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") }, "{\"kind\":\"activation-ready\"}")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (answerOutcome) view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.activationOutcome()") },
                "{\"kind\":\"activation-observed\",\"detailCode\":\"original-submit-observed\"}")
        }

        fun answerActivationPoint(json: String = "{\"kind\":\"ready\",\"x\":540,\"y\":1000,\"viewportWidth\":1080,\"viewportHeight\":1920}") {
            view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.activationPoint()") }, json)
        }

        fun answerReturnedSsoOutcome(kind: String, guard: String = "armed") {
            view.answerFirst({ it.contains("sso-helper-ready") }, "{\"kind\":\"installed\"}")
            view.answerFirst({ it.contains("const r = h?.outcome()") },
                """{"kind":"$kind","restartGuard":"$guard"}""")
        }

        fun answerRestartGuard(kind: String = "ready") {
            view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.captchaRestartState()") },
                """{"kind":"$kind"}""")
        }

        fun submitLibrary() {
            view.answerFirst({ it.contains("__libraryVpnLoginSubmitted") && !it.contains(LIBRARY_PASSWORD) }, "{\"kind\":\"ready\"}")
            view.answerFirst({ it.contains("__libraryVpnLoginSubmitted") && it.contains(LIBRARY_PASSWORD) }, "{\"kind\":\"submitted\"}")
        }

        override fun close() { driver.close(); view.destroy() }
    }

    @Test fun navigationAllowsOnlyVerifiedSchoolSsoLibraryAndPortalResources() {
        listOf(SSO_URL, "https://sso.cppu.edu.cn:443/tpass/login", SSO_PROXY_URL,
            LIBRARY_LOGIN, LibraryRoute.WEBVPN.url("/selectreadingroom"), "https://webvpn.cppu.edu.cn/").forEach {
            assertTrue("Expected trusted school resource: $it", WebVpnLoginDriver.allowsNavigation(it))
        }
        listOf("http://sso.cppu.edu.cn/tpass/login", "https://sso.cppu.edu.cn:444/tpass/login",
            "https://sso.cppu.edu.cn.attacker.example/tpass/login", "https://someone@sso.cppu.edu.cn/tpass/login",
            "https://other.cppu.edu.cn/tpass/login", "https://webvpn.cppu.edu.cn:444/",
            "https://someone@webvpn.cppu.edu.cn/", "https://webvpn.cppu.edu.cn/http/other/login",
            "https://webvpn.cppu.edu.cn/https/other/tpass/login", "javascript:alert(1)").forEach {
            assertFalse("Unexpected navigation allowed: $it", WebVpnLoginDriver.allowsNavigation(it))
        }
    }

    @Test fun ssoPreparationUsesOnlyExplicitSsoAccountAndDoesNotSubmitWithoutCaptcha() {
        Fixture().use { f ->
            f.start(SSO_URL)
            f.prepareSso()
            val credentialCall = f.view.calls.single { it.script.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepare(") }.script
            assertTrue(credentialCall.contains(SSO_USER))
            assertTrue(credentialCall.contains(SSO_PASSWORD))
            assertTrue(credentialCall.contains("\"rememberMe\":true"))
            assertFalse(credentialCall.contains(LIBRARY_USER))
            assertFalse(credentialCall.contains(LIBRARY_PASSWORD))
            assertEquals(0, f.view.activationCount())
            assertTrue(f.results.isEmpty())
            assertTrue(f.probes.isEmpty())
            assertFalse(f.sso.verified)
            assertFalse(f.library.verified)
        }
    }

    @Test fun firstResumeDoesNotTreatAnAvailableUrlAsAFinishedDocument() {
        listOf(SSO_URL, LIBRARY_LOGIN, "about:blank").forEach { address ->
            Fixture().use { f ->
                f.view.address = address
                f.driver.onResume()
                assertTrue(f.view.calls.isEmpty())
                assertTrue(f.probes.isEmpty())
                assertTrue(f.results.isEmpty())
                f.driver.openLoginPage()
                assertEquals(listOf(LIBRARY_LOGIN), f.view.navigations)
                assertTrue(f.view.calls.isEmpty())
                f.finish(SSO_PROXY_URL)
                f.prepareSso()
                f.completeCaptchaAndSubmitSso()
                assertEquals(1, f.view.activationCount())
                assertTrue(f.results.isEmpty())
            }
        }
    }

    @Test fun initialEntryAutomaticallyCompletesBothStagesWithoutExplicitRestart() {
        Fixture().use { f ->
            // Activity may resume before WebView delivers its first start callback.
            f.driver.openLoginPage()
            f.driver.onResume()
            f.finish(SSO_PROXY_URL)
            f.prepareSso()
            f.completeCaptchaAndSubmitSso()
            f.finish(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            f.submitLibrary()
            f.finish(LibraryRoute.WEBVPN.url("/selectreadingroom"))
            f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertEquals(1, f.view.activationCount())
            assertEquals(1, f.view.calls.count { it.script.contains(LIBRARY_PASSWORD) })
            assertTrue(f.sso.verified)
            assertTrue(f.library.verified)
        }
    }

    @Test fun loadingBeforeFirstResumeWaitsAndSkipsExpiredSsoWhenAlreadyAuthenticated() {
        Fixture().use { f ->
            f.driver.openLoginPage()
            f.driver.onResume()
            assertTrue(f.probes.isEmpty())
            assertTrue(f.view.calls.isEmpty())
            f.driver.onPageFinished(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            f.submitLibrary()
            assertEquals(0, f.view.activationCount())
            assertEquals(1, f.view.calls.count { it.script.contains(LIBRARY_PASSWORD) })
        }
    }

    @Test fun duplicateFinishDoesNotRestartCaptchaPreparationOrRejectAnInFlightSsoPost() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL)
            val before = f.view.calls.size
            f.driver.onPageFinished(SSO_PROXY_URL)
            assertEquals(before, f.view.calls.size)
            f.prepareSso()
            f.completeCaptchaAndSubmitSso()
            val after = f.view.calls.size
            f.driver.onPageFinished(SSO_PROXY_URL)
            assertEquals(after, f.view.calls.size)
            assertTrue(f.results.isEmpty())
            assertFalse(f.sso.paused)
            assertEquals(1, f.view.activationCount())
            // A real subsequent load returning to the login page still counts as rejection.
            f.finish(SSO_PROXY_URL)
            f.answerReturnedSsoOutcome("unknown")
            assertEquals("vpn_login_required", f.results.single().state)
            assertTrue(f.sso.paused)
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun duplicateLibraryFinishDoesNotProbeAgainWhileSubmissionIsPending() {
        Fixture().use { f ->
            f.start(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            f.submitLibrary()
            f.driver.onPageFinished(LIBRARY_LOGIN)
            assertTrue(f.probes.isEmpty())
            assertTrue(f.results.isEmpty())
            assertFalse(f.library.paused)
            assertEquals(1, f.view.calls.count { it.script.contains(LIBRARY_PASSWORD) })
            // Read-only verification still occurs even if the school does not navigate.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
        }
    }

    @Test fun redirectBeforeRealStartCallbackInvalidatesOldDocumentImmediately() {
        Fixture().use { f ->
            f.start("https://webvpn.cppu.edu.cn/")
            assertEquals(listOf(LIBRARY_LOGIN), f.view.navigations)
            f.driver.onPause()
            f.driver.onResume()
            assertTrue(f.probes.isEmpty())
            assertTrue(f.view.calls.isEmpty())
            assertTrue(f.results.isEmpty())
            f.driver.onPageFinished(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            f.submitLibrary()
            assertEquals(1, f.view.calls.count { it.script.contains(LIBRARY_PASSWORD) })
        }
    }

    @Test fun automaticEntryNeverReactivatesAPausedOrDisabledSsoAccount() {
        repeat(2) { variant ->
            Fixture().use { f ->
                if (variant == 0) f.sso.pause() else f.sso.setEnabled(false)
                f.driver.openLoginPage()
                f.driver.onResume()
                f.finish(SSO_URL)
                assertEquals("vpn_login_required", f.results.single().state)
                assertFalse(f.view.calls.any { it.script.contains(SSO_PASSWORD) })
                assertEquals(0, f.view.activationCount())
                f.driver.onResume()
                f.driver.onPageFinished(SSO_URL)
                assertEquals(1, f.results.size)
            }
        }
    }

    @Test fun absentSsoAccountNeverFallsBackToLibraryPassword() {
        Fixture(saveSso = false).use { f ->
            f.start(SSO_URL)
            assertEquals("vpn_login_required", f.results.single().state)
            assertFalse(f.view.calls.any { it.script.contains(SSO_PASSWORD) || it.script.contains(LIBRARY_PASSWORD) })
            assertNotNull(f.library.read())
            assertFalse(f.library.paused)
        }
    }

    @Test fun lostJavaScriptCallbackStillStartsReadOnlyOutcomeVerificationWithoutResubmission() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            // Deliberately lose the read-only prepareActivation callback. No input
            // may be sent, and the conservative watchdog still must not resubmit.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            assertEquals(1, f.probes.size)
            assertTrue(f.results.isEmpty())
            f.answerProbe("vpn_login_required", "expired")
            assertEquals(1, f.results.size)
            assertTrue(f.results.single().detail.contains("不重复提交"))
            assertTrue(f.sso.paused)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun lostCallbackButVerifiedSsoContinuesAutomaticallyToLibraryWithoutAnotherSsoPost() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            f.answerProbe("expired", "valid")
            assertEquals(listOf(LIBRARY_LOGIN), f.view.navigations)
            assertTrue(f.sso.verified)
            f.finish(LIBRARY_LOGIN); f.answerProbe("expired", "valid"); f.submitLibrary()
            f.finish(LibraryRoute.WEBVPN.url("/selectreadingroom")); f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun scriptReadinessWaitAutomaticallyContinuesAndOldWatchdogCannotPrematurelyFailTheNextCall() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"waiting","detailCode":"school-scripts-loading"}""")
            assertTrue(f.statuses.last().contains("密码加密脚本"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertEquals(2, f.view.activationCount())
            f.activateOriginalButton()
            assertTrue(f.statuses.last().contains("学校原登录按钮触发表单"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11300))
            assertTrue(f.probes.isEmpty())
            assertTrue(f.results.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertEquals(1, f.probes.size)
        }
    }

    @Test fun provenLocalValidationFailureReportsNoSubmissionWithoutPausingTheAccount() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"blocked","detailCode":"school-validation-rejected"}""")
            assertTrue(f.results.single().detail.contains("本地校验未通过"))
            assertFalse(f.sso.paused)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertTrue(f.probes.isEmpty())
            assertEquals(1, f.results.size)
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun originalButtonUsesExactlyOneNativeTouchAndNeverTheLegacyJavaScriptSubmit() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            assertTrue(f.view.touches.isEmpty())
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertTrue(f.view.touches.isEmpty())
            f.answerActivationPoint()
            assertEquals(listOf(MotionEvent.ACTION_DOWN), f.view.touches)
            f.driver.onPageFinished(SSO_PROXY_URL)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), f.view.touches)
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.activationOutcome()") },
                """{"kind":"activation-observed","detailCode":"original-click-observed"}""")
            assertTrue(f.statuses.last().contains("不重复点击"))
            assertFalse(f.view.calls.any { it.script.contains("SchoolSsoAutoLogin?.submit()") ||
                it.script.contains("requestSubmit(") && !it.script.contains("sso-helper-ready") })
            assertEquals(1, f.view.activationCount())
            assertTrue(f.results.isEmpty())
        }
    }

    @Test fun invalidOrChangedActivationTargetNeverDispatchesInputOrPausesThePassword() {
        val points = listOf(
            """{"kind":"blocked"}""",
            """{"kind":"ready","x":"NaN","y":1000,"viewportWidth":1080,"viewportHeight":1920}""",
            """{"kind":"ready","x":540,"y":1000,"viewportWidth":0,"viewportHeight":1920}""",
            """{"kind":"ready","x":1080,"y":1000,"viewportWidth":1080,"viewportHeight":1920}"""
        )
        for (point in points) Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            f.answerActivationPoint(point)
            assertTrue(f.view.touches.isEmpty())
            assertTrue(f.results.single().detail.contains("未发送登录触控"))
            assertFalse(f.sso.paused); assertFalse(f.library.paused)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertTrue(f.probes.isEmpty()); assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun accountOrOcrChangeAfterReadinessCancelsBeforeAnyNativeInput() {
        repeat(2) { change -> Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            if (change == 0) f.sso.save("changed-synthetic-student", "changed-synthetic-password")
            else f.driver.setOcrEnabled(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertTrue(f.view.touches.isEmpty())
            assertEquals(1, f.results.size); assertFalse(f.sso.paused)
            assertFalse(f.view.calls.any { it.script.contains("?.activationPoint()") })
        } }
    }

    @Test fun changedAccountWhilePointCallbackWasPendingNeverDispatchesNativeInput() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            f.sso.save("changed-synthetic-student", "changed-synthetic-password")
            f.answerActivationPoint()
            assertTrue(f.view.touches.isEmpty()); assertEquals(1, f.results.size)
            assertFalse(f.sso.paused)
        }
    }

    @Test fun zeroSizedWebViewStopsBeforeDispatchingAnyInput() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            f.view.right = 0; f.view.bottom = 0
            f.answerActivationPoint()
            assertTrue(f.view.touches.isEmpty()); assertFalse(f.sso.paused)
            assertEquals(1, f.results.size)
        }
    }

    @Test fun pauseOrCloseAfterDownCancelsTheGestureWithoutAnUpOrFallback() {
        repeat(2) { cause -> Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            f.answerActivationPoint()
            assertEquals(listOf(MotionEvent.ACTION_DOWN), f.view.touches)
            if (cause == 0) f.driver.onPause() else f.driver.close()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), f.view.touches)
            assertEquals(1, f.view.activationCount()); assertTrue(f.results.isEmpty())
            assertFalse(f.view.calls.any { it.script.contains("?.activationOutcome()") })
            if (cause == 1) assertTrue(f.sso.paused)
        } }
    }

    @Test fun rejectedOrThrowingNativeDownStopsOnNetworkFailureWithoutPausingThePassword() {
        repeat(2) { cause -> Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (cause == 0) f.view.acceptTouch = false else f.view.throwAfterDown = true
            f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.view.answerFirst({ it.contains("?.activationOutcome()") },
                """{"kind":"activation-observed","detailCode":"activation-unobserved"}""")
            f.answerProbe("unknown", null, networkFailure = true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertEquals(1, f.view.touches.count { it == MotionEvent.ACTION_DOWN })
            assertFalse(f.view.touches.contains(MotionEvent.ACTION_UP))
            assertFalse(f.sso.paused); assertFalse(f.library.paused)
            assertEquals(1, f.results.size); assertEquals(1, f.view.activationCount())
            assertTrue(f.probes.isEmpty())
            assertFalse(f.view.calls.any { it.script.contains("SchoolSsoAutoLogin?.submit()") })
        } }
    }

    @Test fun falseNativeDispatchWithObservedSubmitDoesNotClickAgainAndCanStillVerifySuccess() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha()
            f.view.acceptTouch=false
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.view.answerFirst({ it.contains("?.activationOutcome()") },
                """{"kind":"activation-observed","detailCode":"original-submit-observed"}""")
            assertTrue(f.results.isEmpty());assertTrue(f.probes.isEmpty());assertFalse(f.sso.paused)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12));f.answerProbe("valid","valid")
            assertEquals("valid",f.results.single().state)
            assertEquals(1,f.view.touches.count { it==MotionEvent.ACTION_DOWN })
        }
    }

    @Test fun unobservedFirstTapRearmsTheSameDocumentAndThenUsesTheSchoolHandlerOnce() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha();f.view.acceptTouch=false
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.answerOutcome("activation-unobserved")
            assertTrue(f.results.isEmpty())
            f.answerProbe("vpn_login_required","expired")
            // The same still-current document is retired only after the bounded local
            // recoveries; it is not reloaded while the CAPTCHA and one-shot window survive.
            f.answerRearm()
            assertTrue(f.view.navigations.isEmpty())
            assertFalse(f.view.calls.any { it.script.contains("?.abandonUnsubmittedActivation()") })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(2,f.view.touches.count { it==MotionEvent.ACTION_DOWN })
            f.answerOutcome("activation-unobserved")
            f.answerProbe("vpn_login_required","expired")
            // Only the re-armed gesture may consume the local recovery; the fallback is next.
            assertEquals(1,f.view.calls.count { it.script.contains("?.rearmActivation()") })
            f.answerSchoolHandler("submitted","school-handler-submit-observed")
            assertTrue(f.statuses.last().contains("学校自身校验处理器"))
            assertTrue(f.view.navigations.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12));f.answerProbe("valid","valid")
            assertEquals("valid",f.results.single().state);assertFalse(f.sso.paused)
            assertEquals(2,f.view.touches.count { it==MotionEvent.ACTION_DOWN })
            assertEquals(1,f.view.calls.count { it.script.contains("?.schoolHandlerSubmit()") })
        }
    }

    @Test fun anUnobservedRunStillReachesAFreshPageRestartWhenNoLocalRecoveryIsAvailable() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha();f.view.acceptTouch=false
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.answerOutcome("activation-unobserved")
            f.answerProbe("vpn_login_required","expired");f.answerRearm("blocked")
            // The page does not expose the school's own handler, so nothing was sent and the
            // conservative restart path may still retire this unsubmitted document.
            f.answerSchoolHandler("blocked","school-handler-unavailable")
            f.answerRetire()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1499))
            assertTrue(f.view.navigations.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            f.answerUnsubmittedRestart()
            assertEquals(listOf(LIBRARY_LOGIN),f.view.navigations)
            f.view.acceptTouch=true;f.finish(SSO_PROXY_URL);f.prepareSso();f.completeCaptchaAndSubmitSso()
            assertEquals(2,f.view.touches.count { it==MotionEvent.ACTION_DOWN })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12));f.answerProbe("valid","valid")
            assertEquals("valid",f.results.single().state);assertFalse(f.sso.paused)
        }
    }

    @Test fun aProvenPointMissUsesTheBoundedLocalRecoveryAndTheSchoolHandlerExactlyOnce() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha()
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            // The reserved gesture hit a node on top of the button: no click, no submit.
            f.answerOutcome("activation-blocked","point-miss")
            assertTrue(f.results.isEmpty())
            f.answerProbe("vpn_login_required","expired");f.answerRearm("blocked")
            // A refused local re-arm falls straight through to exactly one school-handler call.
            f.answerSchoolHandler("submitted","school-handler-click-unconfirmed")
            assertEquals(1,f.view.calls.count { it.script.contains("?.schoolHandlerSubmit()") })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            f.answerProbe("vpn_login_required","expired")
            assertEquals("vpn_login_required",f.results.single().state)
            assertEquals(1,f.view.calls.count { it.script.contains("?.schoolHandlerSubmit()") })
        }
    }

    @Test fun aClosedGuardWithoutAProvenMissNeverRearmsAndNeverUsesTheSchoolHandler() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha()
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            f.answerOutcome("activation-blocked","binding-user-edited")
            f.answerProbe("vpn_login_required","expired")
            // A changed field or CAPTCHA proves nothing about submission, so the document is
            // retired atomically first and the fallback is never fired on it.
            assertFalse(f.view.calls.any { it.script.contains("?.rearmActivation()") })
            assertFalse(f.view.calls.any { it.script.contains("?.schoolHandlerSubmit()") })
            f.answerRetire("blocked")
            assertEquals(1,f.results.size);assertTrue(f.sso.paused);assertTrue(f.view.navigations.isEmpty())
        }
    }

    @Test fun everyNoEventTouchCountsAgainstOneSharedGestureBudgetThatIsNeverReset() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.view.acceptTouch=false
            // First round: one reserved gesture, at most one re-armed gesture, then the
            // school-handler fallback and the conservative full-page restart.
            f.prepareSso();f.completeCaptcha()
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.answerOutcome("activation-unobserved")
            f.answerProbe("vpn_login_required","expired")
            f.answerRearm()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.answerOutcome("activation-unobserved")
            f.answerProbe("vpn_login_required","expired")
            f.answerSchoolHandler("blocked","school-handler-unavailable")
            f.answerRetire()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            f.answerUnsubmittedRestart();f.finish(SSO_PROXY_URL)
            // Second round: the third gesture is the last the shared budget allows, and a
            // re-arm that would need a fourth is refused before any input is dispatched.
            f.prepareSso();f.completeCaptcha()
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.answerOutcome("activation-unobserved")
            f.answerProbe("vpn_login_required","expired")
            f.answerRearm()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertTrue(f.results.single().detail.contains("三次"));assertFalse(f.sso.paused)
            assertEquals(3,f.view.touches.count { it==MotionEvent.ACTION_DOWN })
            assertEquals(1,f.view.navigations.size)
            assertEquals(2,f.view.calls.count { it.script.contains("?.rearmActivation()") })
            // The exhausted gesture budget stops the run before any further fallback.
            assertEquals(1,f.view.calls.count { it.script.contains("?.schoolHandlerSubmit()") })
        }
    }

    @Test fun aPausedOrLateRetryNeverDispatchesAnotherGestureOrRestartsThePage() {
        repeat(2){ change -> Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha();f.view.acceptTouch=false
            f.view.answerFirst({ it.contains("?.prepareActivation()") }, """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            f.answerOutcome("activation-unobserved")
            f.answerProbe("vpn_login_required","expired")
            if(change==0){
                f.answerRearm()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));f.answerActivationPoint()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
                assertEquals(2,f.view.touches.count { it==MotionEvent.ACTION_DOWN })
                // The re-armed document now proves a changed field: no fallback, no restart.
                f.answerOutcome("activation-blocked","binding-user-edited")
                f.answerProbe("vpn_login_required","expired");f.answerRetire("blocked")
                assertEquals(1,f.results.size)
                assertFalse(f.view.calls.any { it.script.contains("?.schoolHandlerSubmit()") })
            }else{
                f.driver.onPause();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertFalse(f.view.calls.any { it.script.contains("?.unsubmittedRestartState()") })
                assertFalse(f.view.calls.any { it.script.contains("?.schoolHandlerSubmit()") })
                assertEquals(1,f.view.touches.count { it==MotionEvent.ACTION_DOWN })
                assertTrue(f.results.isEmpty())
            }
            assertTrue(f.view.navigations.isEmpty())
        } }
    }

    @Test fun unknownProbeRetiresTheDocumentOnlyAfterEveryLocalRecoveryWasProvenUnsent() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha();f.activateOriginalButton(answerOutcome=false)
            f.answerOutcome("activation-unobserved")
            f.answerProbe("unknown",null)
            assertTrue(f.view.navigations.isEmpty());assertTrue(f.results.isEmpty())
            // Both bounded local recoveries run first and report that nothing was sent, so
            // the document stays eligible for the atomic retire and the full-page restart.
            f.answerRearm("blocked");f.answerSchoolHandler("blocked","school-handler-unavailable")
            assertTrue(f.view.calls.any { it.script.contains("?.rearmActivation()") })
            assertTrue(f.view.calls.any { it.script.contains("?.schoolHandlerSubmit()") })
            assertTrue(f.view.calls.any { it.script.contains("?.abandonUnsubmittedActivation()") })
            f.answerRetire();assertTrue(f.statuses.last().contains("Synthetic school result"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));f.answerUnsubmittedRestart()
            assertEquals(listOf(LIBRARY_LOGIN),f.view.navigations);assertFalse(f.sso.paused)
        }
    }

    @Test fun freshPageRecoveryDoesNotExtendTheOriginalNinetySecondDeadline() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha();f.activateOriginalButton(answerOutcome=false)
            f.answerOutcome("activation-unobserved")
            f.answerProbe("unknown",null);f.answerRearm("blocked")
            f.answerSchoolHandler("blocked","school-handler-unavailable");f.answerRetire()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));f.answerUnsubmittedRestart()
            f.finish(SSO_PROXY_URL);f.prepareSso()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
            assertEquals(1,f.results.size);assertTrue(f.results.single().detail.contains("90 秒"))
            assertEquals(1,f.view.navigations.size);assertEquals(1,f.view.activationCount())
            assertFalse(f.sso.paused)
        }
    }

    @Test fun aClosedGuardWhileTheProbeWasPendingNeverRestartsThePageOrResubmits() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL);f.prepareSso();f.completeCaptcha();f.activateOriginalButton(answerOutcome=false)
            f.answerOutcome("activation-unobserved")
            f.answerProbe("unknown",null)
            // The page proves a click, a submit or a manual takeover: both bounded local
            // recoveries refuse, and the document is never retired or reloaded.
            f.answerRearm("blocked")
            f.answerSchoolHandler("blocked","activation-may-have-submitted")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertTrue(f.view.navigations.isEmpty());assertEquals(1,f.results.size)
            assertTrue(f.sso.paused);assertEquals(1,f.view.activationCount())
            assertFalse(f.view.calls.any { it.script.contains("?.abandonUnsubmittedActivation()") })
        }
    }

    @Test fun newDocumentAfterDownCancelsTheOldGestureAndIgnoresItsCompletion() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation()") },
                """{"kind":"activation-ready"}""")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            f.answerActivationPoint()
            f.view.address = LIBRARY_LOGIN
            f.driver.onPageStarted(LIBRARY_LOGIN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), f.view.touches)
            assertTrue(f.results.isEmpty()); assertTrue(f.probes.isEmpty())
            assertFalse(f.view.calls.any { it.script.contains("?.activationOutcome()") })
            f.driver.onPageFinished(LIBRARY_LOGIN)
            f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertFalse(f.sso.paused); assertTrue(f.sso.verified)
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun lostActivationObservationAfterNativeTouchStillChecksTheSchoolWithoutAnotherTouch() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptcha()
            f.activateOriginalButton(answerOutcome = false)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), f.view.touches)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertEquals(1, f.view.activationCount())
            assertEquals(2, f.view.touches.size); assertFalse(f.sso.paused)
        }
    }

    @Test fun repeatedSsoFailureStopsAfterOneSubmitAndPausesOnlySsoAccount() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL)
            f.prepareSso()
            f.completeCaptchaAndSubmitSso()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertEquals(1, f.view.calls.count { it.script.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") })
            assertEquals(1, f.view.activationCount())
            f.finish(SSO_PROXY_URL)
            f.answerReturnedSsoOutcome("other-rejected")
            assertEquals("vpn_login_required", f.results.single().state)
            assertTrue(f.sso.paused)
            assertFalse(f.library.paused)
            assertEquals(1, f.view.activationCount())
            assertFalse(f.view.calls.any { it.script.contains(LIBRARY_PASSWORD) })
            f.driver.onPageFinished(SSO_PROXY_URL)
            assertEquals(1, f.results.size)
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun explicitCaptchaRejectionAutomaticallyRestartsWithFreshGetThenCompletesBothStages() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            val oldSnapshots = f.view.calls.count { it.script.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") }
            f.finish(SSO_PROXY_URL); f.answerReturnedSsoOutcome("captcha-rejected")
            assertTrue(f.results.isEmpty()); assertFalse(f.sso.paused)
            assertTrue(f.view.navigations.isEmpty())
            val calls = f.view.calls.size
            f.driver.onPageFinished(SSO_PROXY_URL)
            assertEquals(calls, f.view.calls.size)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1499))
            assertTrue(f.view.navigations.isEmpty())
            assertEquals(oldSnapshots, f.view.calls.count { it.script.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertTrue(f.view.navigations.isEmpty()) // Last-moment interaction guard must still approve.
            f.answerRestartGuard()
            assertEquals(listOf(LIBRARY_LOGIN), f.view.navigations)
            assertEquals(1, f.view.activationCount())
            f.finish(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            assertEquals(2, f.view.activationCount())
            f.finish(LIBRARY_LOGIN); f.answerProbe("expired", "valid"); f.submitLibrary()
            f.finish(LibraryRoute.WEBVPN.url("/selectreadingroom")); f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertTrue(f.sso.verified); assertTrue(f.library.verified)
        }
    }

    @Test fun explicitCaptchaRejectionsStopAfterTwoFreshRestartsWithoutPausingPasswordStore() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL)
            repeat(3) { round ->
                if (round > 0) f.finish(SSO_PROXY_URL)
                f.prepareSso(); f.completeCaptchaAndSubmitSso()
                assertEquals(round + 1, f.view.activationCount())
                f.finish(SSO_PROXY_URL); f.answerReturnedSsoOutcome("captcha-rejected")
                if (round < 2) {
                    assertTrue(f.results.isEmpty())
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
                    f.answerRestartGuard()
                }
            }
            assertEquals(2, f.view.navigations.size)
            assertEquals(3, f.view.activationCount())
            assertTrue(f.results.single().detail.contains("两次自动重新开始上限"))
            assertFalse(f.sso.paused); assertFalse(f.library.paused)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(3, f.view.activationCount()); assertEquals(1, f.results.size)
        }
    }

    @Test fun unknownOrNonCaptchaReturnNeverRestartsEvenWhenThePageReloaded() {
        for (kind in listOf("unknown", "other-rejected", "manual")) Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            f.finish(SSO_PROXY_URL); f.answerReturnedSsoOutcome(kind)
            assertEquals("vpn_login_required", f.results.single().state)
            assertTrue(f.sso.paused); assertFalse(f.library.paused)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertTrue(f.view.navigations.isEmpty()); assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun oldDocumentAfterResumeCanOnlyVerifyUnknownOutcomeNeverUseCaptchaRejectionRetry() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            f.driver.onPause(); f.driver.onResume()
            f.driver.onPageFinished(SSO_PROXY_URL)
            assertFalse(f.view.calls.any { it.script.contains("const r = h?.outcome()") })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            f.answerProbe("vpn_login_required", "expired")
            assertTrue(f.sso.paused); assertTrue(f.view.navigations.isEmpty())
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun changedPausedDisabledOrOcrOptedOutCancelsThePendingFreshRestart() {
        repeat(4) { change -> Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            f.finish(SSO_PROXY_URL); f.answerReturnedSsoOutcome("captcha-rejected")
            when (change) {
                0 -> f.sso.save("changed-synthetic-account", "changed-synthetic-password")
                1 -> f.sso.pause()
                2 -> f.sso.setEnabled(false)
                else -> f.driver.setOcrEnabled(false)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            assertEquals(1, f.results.size); assertTrue(f.view.navigations.isEmpty())
            assertEquals(1, f.view.activationCount())
            assertEquals(change == 1, f.sso.paused); assertFalse(f.library.paused)
        } }
    }

    @Test fun manualInteractionBeforeArmOrBeforeDelayedGetKeepsThePageAndDoesNotPausePasswordStore() {
        repeat(2) { whenEdited -> Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            f.finish(SSO_PROXY_URL)
            f.answerReturnedSsoOutcome("captcha-rejected", if (whenEdited == 0) "manual" else "armed")
            if (whenEdited == 1) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
                f.answerRestartGuard("manual")
            }
            assertEquals(1, f.results.size); assertTrue(f.view.navigations.isEmpty())
            assertFalse(f.sso.paused); assertFalse(f.library.paused)
            assertEquals(1, f.view.activationCount())
        } }
    }

    @Test fun pauseDuringRestartDelayNeverResumesOcrOnRejectedDocumentAndContinuesOnlyAfterFreshGet() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            f.finish(SSO_PROXY_URL); f.answerReturnedSsoOutcome("captcha-rejected")
            val oldSnapshots = f.view.calls.count { it.script.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") }
            f.driver.onPause()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
            assertTrue(f.view.navigations.isEmpty())
            f.driver.onResume(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(oldSnapshots, f.view.calls.count { it.script.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") })
            f.answerRestartGuard(); assertEquals(listOf(LIBRARY_LOGIN), f.view.navigations)
            assertFalse(f.sso.paused); assertTrue(f.results.isEmpty())
        }
    }

    @Test fun staleOriginalWatchdogCannotFailTheNextCaptchaRoundPrematurely() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            f.finish(SSO_PROXY_URL); f.answerReturnedSsoOutcome("captcha-rejected")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500)); f.answerRestartGuard()
            f.finish(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10500))
            assertTrue(f.probes.isEmpty()); assertTrue(f.results.isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
            assertEquals(1, f.probes.size)
            f.answerProbe("vpn_login_required", "expired")
            assertTrue(f.sso.paused); assertEquals(2, f.view.activationCount())
        }
    }

    @Test fun automaticCaptchaRestartNeverExtendsTheOriginalNinetySecondDeadline() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL); f.prepareSso(); f.completeCaptchaAndSubmitSso()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(89))
            f.finish(SSO_PROXY_URL); f.answerReturnedSsoOutcome("captcha-rejected")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(f.results.single().detail.contains("超过 90 秒"))
            assertTrue(f.view.navigations.isEmpty()); assertFalse(f.sso.paused)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertTrue(f.view.navigations.isEmpty()); assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun validSsoWithExpiredLibraryUsesOnlyIndependentLibraryCredentials() {
        Fixture().use { f ->
            f.start(LIBRARY_LOGIN)
            assertTrue(f.view.calls.isEmpty())
            f.answerProbe("expired", "valid")
            val inspection = f.view.calls.single().script
            assertFalse(inspection.contains(SSO_PASSWORD))
            assertFalse(inspection.contains(LIBRARY_PASSWORD))
            f.submitLibrary()
            val login = f.view.calls.single { it.script.contains(LIBRARY_PASSWORD) }.script
            assertTrue(login.contains(LIBRARY_USER))
            assertFalse(login.contains(SSO_USER))
            assertFalse(login.contains(SSO_PASSWORD))
            assertEquals(0, f.view.activationCount())
            assertFalse(f.library.verified)
            f.finish(LIBRARY_LOGIN)
            f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertTrue(f.library.verified)
            assertFalse(f.sso.verified)
            assertFalse(f.sso.paused)
        }
    }

    @Test fun ssoAndLibraryBecomeVerifiedOnlyAfterSeparateSchoolSessionResults() {
        Fixture().use { f ->
            f.start(SSO_URL)
            f.prepareSso()
            f.completeCaptchaAndSubmitSso()
            assertFalse(f.sso.verified)
            f.finish(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            assertTrue(f.sso.verified)
            assertFalse(f.library.verified)
            f.submitLibrary()
            f.finish(LIBRARY_LOGIN)
            f.answerProbe("valid", "valid")
            assertTrue(f.sso.verified)
            assertTrue(f.library.verified)
            assertEquals("valid", f.results.single().state)
            assertEquals(1, f.view.activationCount())
        }
    }

    @Test fun libraryRejectionPausesOnlyLibraryAndDoesNotResubmit() {
        Fixture().use { f ->
            f.start(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            f.submitLibrary()
            f.finish(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            assertEquals("expired", f.results.single().state)
            assertEquals("valid", f.results.single().vpnAuthState)
            assertTrue(f.library.paused)
            assertFalse(f.sso.paused)
            val calls = f.view.calls.size
            f.driver.onPageFinished(LIBRARY_LOGIN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertEquals(calls, f.view.calls.size)
            assertEquals(1, f.results.size)
            assertTrue(f.probes.isEmpty())
        }
    }

    @Test fun unknownResultAfterLibrarySubmissionStopsWithoutRetryOrSsoFallback() {
        Fixture().use { f ->
            f.start(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            f.submitLibrary()
            f.finish(LIBRARY_LOGIN)
            f.answerProbe("unknown", null)
            assertEquals("unknown", f.results.single().state)
            assertTrue(f.library.paused)
            assertFalse(f.sso.paused)
            assertEquals(1, f.view.calls.count { it.script.contains(LIBRARY_PASSWORD) })
            assertEquals(0, f.view.activationCount())
            assertTrue(f.view.navigations.isEmpty())
        }
    }

    @Test fun pauseNavigationAndCloseDiscardStaleSchoolSessionResults() {
        repeat(3) { cause ->
            Fixture().use { f ->
                f.start(LIBRARY_LOGIN)
                val pending = f.probes.removeFirst()
                when (cause) {
                    0 -> f.driver.onPause()
                    1 -> { f.view.address = SSO_URL; f.driver.onPageStarted(SSO_URL) }
                    else -> f.driver.close()
                }
                pending(SessionChecker.Result("expired", "Synthetic delayed result", vpnAuthState = "valid"))
                assertTrue(f.view.calls.isEmpty())
                assertTrue(f.results.isEmpty())
                assertFalse(f.library.paused)
            }
        }
    }

    @Test fun accountChangeBeforePreparedCallbackPreventsSubmission() {
        Fixture().use { f ->
            f.start(SSO_URL)
            f.view.answerFirst({ it.contains("sso-helper-ready") }, "{\"kind\":\"installed\"}")
            f.sso.save("new-synthetic-student", "new-synthetic-password")
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepare(") }, "{\"kind\":\"prepared\"}")
            assertEquals("vpn_login_required", f.results.single().state)
            assertEquals(0, f.view.activationCount())
            assertFalse(f.sso.paused)
            assertFalse(f.library.paused)
            assertFalse(f.statuses.any { it.contains(SSO_PASSWORD) || it.contains(LIBRARY_PASSWORD) })
        }
    }

    @Test fun restoredValidSessionsDoNotFillOrSubmitEitherAccount() {
        Fixture().use { f ->
            f.start(LibraryRoute.WEBVPN.url("/selectreadingroom"))
            f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertTrue(f.view.calls.isEmpty())
            assertTrue(f.view.navigations.isEmpty())
            assertFalse(f.sso.verified)
            assertFalse(f.library.verified)
        }
    }

    @Test fun libraryPageFinishedWhilePausedIsRecheckedAndLoggedInOnResume() {
        Fixture().use { f ->
            f.view.address = LIBRARY_LOGIN
            f.driver.onPageStarted(LIBRARY_LOGIN)
            f.driver.onResume()
            f.driver.onPause()
            f.driver.onPageFinished(LIBRARY_LOGIN)
            assertTrue(f.probes.isEmpty())
            assertTrue(f.view.calls.isEmpty())
            assertTrue(f.results.isEmpty())

            f.driver.onResume()
            assertEquals(1, f.probes.size)
            f.answerProbe("expired", "valid")
            f.submitLibrary()
            assertEquals(1, f.view.calls.count { it.script.contains(LIBRARY_PASSWORD) })
            assertEquals(0, f.view.activationCount())
            f.finish(LIBRARY_LOGIN)
            f.answerProbe("valid", "valid")
            assertEquals("valid", f.results.single().state)
            assertTrue(f.library.verified)
        }
    }

    @Test fun captchaReadFailureBeforePreparationReturnsStopsImmediatelyWithoutSubmission() {
        Fixture().use { f ->
            f.start(SSO_URL)
            f.view.answerFirst({ it.contains("Object.defineProperty(window, 'LibraryCaptchaOcr'") }, "{\"kind\":\"installed\"}")
            shadowOf(Looper.getMainLooper()).idle()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") },
                "{\"kind\":\"stopped\",\"detailCode\":\"unsupported-image\"}")
            assertTrue(f.results.isEmpty())
            f.prepareSso()
            assertEquals("vpn_login_required", f.results.single().state)
            assertEquals(0, f.view.activationCount())
            assertFalse(f.sso.paused)
            assertFalse(f.library.paused)
            assertFalse(f.statuses.any { it.contains("90 秒") })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, f.results.size)
            assertEquals(0, f.view.activationCount())
        }
    }

    @Test fun closeAfterUnconfirmedSsoOrLibrarySubmissionPausesOnlyCorrespondingAccount() {
        repeat(2) { account ->
            Fixture().use { f ->
                // A previous successful login must not masquerade as this POST's outcome.
                if (account == 0) f.sso.markVerified() else f.library.markVerified()
                if (account == 0) {
                    f.start(SSO_URL)
                    f.prepareSso()
                    f.completeCaptchaAndSubmitSso()
                    assertEquals(1, f.view.activationCount())
                } else {
                    f.start(LIBRARY_LOGIN)
                    f.answerProbe("expired", "valid")
                    f.submitLibrary()
                    assertEquals(1, f.view.calls.count { it.script.contains(LIBRARY_PASSWORD) })
                }
                f.driver.close()
                assertEquals(account == 0, f.sso.paused)
                assertEquals(account == 1, f.library.paused)
                assertTrue(f.results.isEmpty())
                val calls = f.view.calls.size
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
                assertEquals(calls, f.view.calls.size)
                assertTrue(f.results.isEmpty())
                assertFalse(f.sso.verified)
                assertFalse(f.library.verified)
            }
        }
    }

    @Test fun captchaCompletedBeforeAccountPreparationSubmitsImmediatelyAfterPreparationOnce() {
        Fixture().use { f ->
            f.start(SSO_URL)
            f.view.answerFirst({ it.contains("Object.defineProperty(window, 'LibraryCaptchaOcr'") }, "{\"kind\":\"installed\"}")
            shadowOf(Looper.getMainLooper()).idle()
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") }, "{\"kind\":\"filled\"}")
            assertEquals(0, f.view.activationCount())
            f.prepareSso()
            assertEquals(1, f.view.activationCount())
            f.activateOriginalButton()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            assertEquals(1, f.view.calls.count { it.script.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") })
            assertEquals(1, f.view.activationCount())
            assertTrue(f.results.isEmpty())
        }
    }

    @Test fun rememberPreferenceIsPassedWithoutChangingEitherAccount() {
        app.getSharedPreferences("webvpn_ocr", Context.MODE_PRIVATE).edit().putBoolean("remember_me", false).commit()
        Fixture().use { f ->
            f.start(SSO_URL)
            f.prepareSso()
            val call = f.view.calls.single { it.script.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepare(") }.script
            assertTrue(call.contains("\"rememberMe\":false"))
            assertEquals(0, f.view.activationCount())
            assertFalse(f.library.paused)
        }
    }

    @Test fun libraryStructureFailureIsNotMislabeledAsChangedAccountSettings() {
        Fixture().use { f ->
            f.start(LIBRARY_LOGIN)
            f.answerProbe("expired", "valid")
            f.view.answerFirst({ it.contains("__libraryVpnLoginSubmitted") }, "{\"kind\":\"stopped\",\"detailCode\":\"unsupported-structure\"}")
            assertEquals("expired", f.results.single().state)
            assertTrue(f.results.single().detail.contains("主登录表单"))
            assertFalse(f.results.single().detail.contains("账号设置已改变"))
            assertFalse(f.library.paused)
            assertFalse(f.view.calls.any { it.script.contains(LIBRARY_PASSWORD) })
        }
    }

    companion object {
        private const val SSO_URL = "https://sso.cppu.edu.cn/tpass/login"
        private const val SSO_PROXY_URL = "https://webvpn.cppu.edu.cn${CaptchaPagePolicy.SSO_PROXY_PREFIX}/tpass/login"
        private val LIBRARY_LOGIN = LibraryRoute.WEBVPN.url("/login")
        private const val SSO_USER = "synthetic-student"
        private const val SSO_PASSWORD = "sso-fixture-password"
        private const val LIBRARY_USER = "synthetic-reader"
        private const val LIBRARY_PASSWORD = "library-fixture-password"
    }
}
