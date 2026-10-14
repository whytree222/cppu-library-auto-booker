package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Looper
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
        override fun getUrl(): String = address
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            calls.add(Call(script, resultCallback))
        }
        override fun loadUrl(url: String) { navigations.add(url); address = url }
        fun answerFirst(match: (String) -> Boolean, json: String) {
            val call = calls.firstOrNull { !it.answered && it.callback != null && match(it.script) }
                ?: throw AssertionError("No matching pending fake JavaScript callback")
            call.answered = true
            call.callback?.onReceiveValue(json)
        }
        fun submissionCount() = calls.count { it.script.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.submit()") }
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

        fun answerProbe(state: String, auth: String?) {
            check(probes.isNotEmpty()) { "Expected a fake school session probe" }
            probes.removeFirst().invoke(SessionChecker.Result(state, "Synthetic school result", vpnAuthState = auth))
        }

        fun prepareSso() {
            view.answerFirst({ it.contains("sso-helper-ready") }, "{\"kind\":\"installed\"}")
            view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.prepare(") }, "{\"kind\":\"prepared\"}")
        }

        fun completeCaptchaAndSubmitSso() {
            view.answerFirst({ it.contains("Object.defineProperty(window, 'LibraryCaptchaOcr'") }, "{\"kind\":\"installed\"}")
            shadowOf(Looper.getMainLooper()).idle()
            view.answerFirst({ it.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") }, "{\"kind\":\"filled\"}")
            assertEquals(1, view.submissionCount())
            view.answerFirst({ it.startsWith("JSON.stringify(window.SchoolSsoAutoLogin?.submit()") }, "{\"kind\":\"submitted\"}")
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
            assertFalse(credentialCall.contains(LIBRARY_USER))
            assertFalse(credentialCall.contains(LIBRARY_PASSWORD))
            assertEquals(0, f.view.submissionCount())
            assertTrue(f.results.isEmpty())
            assertTrue(f.probes.isEmpty())
            assertFalse(f.sso.verified)
            assertFalse(f.library.verified)
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

    @Test fun repeatedSsoFailureStopsAfterOneSubmitAndPausesOnlySsoAccount() {
        Fixture().use { f ->
            f.start(SSO_PROXY_URL)
            f.prepareSso()
            f.completeCaptchaAndSubmitSso()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            f.view.answerFirst({ it.startsWith("JSON.stringify(window.LibraryCaptchaOcr?.snapshot()") }, "{\"kind\":\"filled\"}")
            assertEquals(1, f.view.submissionCount())
            f.finish(SSO_PROXY_URL)
            assertEquals("vpn_login_required", f.results.single().state)
            assertTrue(f.sso.paused)
            assertFalse(f.library.paused)
            assertEquals(1, f.view.submissionCount())
            assertFalse(f.view.calls.any { it.script.contains(LIBRARY_PASSWORD) })
            f.driver.onPageFinished(SSO_PROXY_URL)
            assertEquals(1, f.results.size)
            assertEquals(1, f.view.submissionCount())
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
            assertEquals(0, f.view.submissionCount())
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
            assertEquals(1, f.view.submissionCount())
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
            assertEquals(0, f.view.submissionCount())
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
            assertEquals(0, f.view.submissionCount())
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
            assertEquals(0, f.view.submissionCount())
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
            assertEquals(0, f.view.submissionCount())
            assertFalse(f.sso.paused)
            assertFalse(f.library.paused)
            assertFalse(f.statuses.any { it.contains("90 秒") })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, f.results.size)
            assertEquals(0, f.view.submissionCount())
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
                    assertEquals(1, f.view.submissionCount())
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
