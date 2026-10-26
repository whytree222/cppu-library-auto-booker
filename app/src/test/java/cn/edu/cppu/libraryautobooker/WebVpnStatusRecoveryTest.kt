package cn.edu.cppu.libraryautobooker

import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Fake session results and recovery callbacks, not school logins or valid credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebVpnStatusRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        Kind.entries.forEach { app.getSharedPreferences(it.storageName, 0).edit().clear().commit() }
        app.getSharedPreferences("webvpn_ocr", 0).edit().clear().commit()
    }
    private fun enable(kind: Kind, paused: Boolean = false) {
        app.getSharedPreferences(kind.storageName, 0).edit().putString("encrypted", "synthetic-placeholder")
            .putString("iv", "synthetic-placeholder").putBoolean("enabled", true).putBoolean("paused", paused)
            .putString("revision", java.util.UUID.randomUUID().toString()).commit()
    }

    @Test fun statusOnlyLibraryExpiryStartsRecoveryAndDeliversItsVerifiedResult() {
        for (kind in listOf(Kind.LIBRARY)) {
            clear(); enable(kind)
            val result = if (kind == Kind.SSO) SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired")
                else SessionChecker.Result("expired", "expired", vpnAuthState = "valid")
            val request = SessionCoordinator.CheckRequest()
            var starts = 0; var closes = 0
            var pending: ((SessionChecker.Result) -> Unit)? = null
            val delivered = mutableListOf<SessionChecker.Result>()
            SessionCoordinator.handleWebVpnCheck(app, result, false, request, { delivered.add(it) }, ensure = {
                starts++; pending = it; AutoCloseable { closes++ }
            })
            assertEquals(1, starts); assertTrue(delivered.isEmpty())
            pending!!.invoke(SessionChecker.Result("valid", "verified", vpnAuthState = "valid"))
            assertEquals("valid", delivered.single().state)
            assertEquals(1, closes)
        }
    }

    @Test fun validOrUnknownNetworkResultsNeverStartCredentialRecovery() {
        enable(Kind.SSO); enable(Kind.LIBRARY)
        listOf(SessionChecker.Result("valid", "valid", vpnAuthState = "valid"),
            SessionChecker.Result("unknown", "network failure", networkFailure = true)).forEach { result ->
            var starts = 0
            SessionCoordinator.handleWebVpnCheck(app, result, false, SessionCoordinator.CheckRequest(), {}, ensure = {
                starts++; AutoCloseable {}
            })
            assertEquals(0, starts)
        }
    }

    @Test fun pausedOrDisabledLibraryExplainsTheBlockInsteadOfSilentlyShowingExpired() {
        repeat(2) { variant ->
            clear()
            if (variant != 0) enable(Kind.LIBRARY, paused = variant == 1)
            var received: SessionChecker.Result? = null
            SessionCoordinator.handleWebVpnCheck(app,
                SessionChecker.Result("expired", "expired", vpnAuthState = "valid"), false,
                SessionCoordinator.CheckRequest(), { received = it }, ensure = { throw AssertionError("Must not start") })
            assertTrue(received!!.detail.contains(if (variant == 0) "保存开启" else "暂停"))
            assertEquals(received!!.detail, LoginStatusStore(app).snapshot().webVpn.detail)
        }
    }

    @Test fun ssoExpiryAlwaysRequiresNativeManualCaptchaEvenWithSavedAccountsAndOcrPreference() {
        repeat(3) { variant ->
            clear(); enable(Kind.SSO, paused = variant == 1)
            app.getSharedPreferences("webvpn_ocr", 0).edit().putBoolean("enabled", variant != 2).commit()
            val expired = SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired")
            assertFalse(WebVpnAutoLogin.canRecover(app, expired))
            var received: SessionChecker.Result? = null
            SessionCoordinator.handleWebVpnCheck(app, expired, false, SessionCoordinator.CheckRequest(),
                { received = it }, ensure = { throw AssertionError("Manual CAPTCHA must not start background SSO") })
            assertTrue(received!!.detail.contains("应用内")); assertTrue(received!!.detail.contains("手动输入验证码"))
        }
    }

    @Test fun cancelledStatusCheckCannotStartRecoveryOrDeliverItsLateResult() {
        enable(Kind.SSO)
        val request = SessionCoordinator.CheckRequest(); request.close()
        SessionCoordinator.handleWebVpnCheck(app,
            SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired"), false, request,
            { throw AssertionError("Cancelled callback") }, ensure = { throw AssertionError("Cancelled recovery") })
    }

    @Test fun secondaryRecoveryDoesNotReplaceTheDirectSessionOrPreferredRoute() {
        val runtime = RuntimeStore(app)
        runtime.session("valid", "direct-valid")
        runtime.prefs.edit().putString("active_route", "DIRECT").putString("session_route", "DIRECT").commit()
        val request = WebVpnAutoLogin.ensure(app, updateSession = false) {}
        try {
            assertEquals("valid", runtime.prefs.getString("session_state", ""))
            WebVpnAutoLogin.cancel()
            assertEquals("valid", runtime.prefs.getString("session_state", ""))
            assertEquals("DIRECT", runtime.prefs.getString("active_route", ""))
            assertEquals("DIRECT", runtime.prefs.getString("session_route", ""))
        } finally { request.close(); WebVpnAutoLogin.cancel() }
    }

    @Test fun statusFailureCoolsDownButDoesNotBlockTheScheduledRecoveryOrChangedAccount() {
        enable(Kind.LIBRARY)
        val expired = SessionChecker.Result("expired", "expired", vpnAuthState = "valid")
        val request = WebVpnAutoLogin.ensure(app, updateSession = false) {}
        try {
            WebVpnAutoLogin.cancel()
            assertFalse(WebVpnAutoLogin.canRecover(app, expired, statusOnly = true))
            assertTrue(WebVpnAutoLogin.canRecover(app, expired))
            assertTrue(WebVpnAutoLogin.recoveryUnavailableReason(app, expired)!!.contains("五分钟"))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMinutes(5))
            assertTrue(WebVpnAutoLogin.canRecover(app, expired, statusOnly = true))
            val again = WebVpnAutoLogin.ensure(app, updateSession = false) {}
            WebVpnAutoLogin.cancel(); again.close()
            enable(Kind.LIBRARY) // A new explicit save has a new revision.
            assertTrue(WebVpnAutoLogin.canRecover(app, expired, statusOnly = true))
        } finally { request.close(); WebVpnAutoLogin.cancel() }
    }
}
