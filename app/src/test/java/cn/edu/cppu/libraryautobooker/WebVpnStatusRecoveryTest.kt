package cn.edu.cppu.libraryautobooker

import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.NativeWebVpnAutoSettings
import org.junit.Assert.*
import org.junit.After
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
    private lateinit var harness: QueuedNativeRecoveryHarness
    @Before fun setup() { harness = QueuedNativeRecoveryHarness(app); clear() }
    @After fun cleanup() { harness.close() }
    private fun clear() = harness.clearPreferences()
    private fun enable(kind: Kind, paused: Boolean = false) = harness.enable(kind, paused)

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

    @Test fun savedEnabledDualAccountsAllowNativeSsoIndependentlyOfRetiredWebpageOcrPreference() {
        enable(Kind.SSO); enable(Kind.LIBRARY)
        app.getSharedPreferences("webvpn_ocr", 0).edit().putBoolean("enabled", false).commit()
        val expired = SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired")
        assertTrue(NativeWebVpnAutoSettings(app).enabled)
        assertTrue(WebVpnAutoLogin.canRecover(app, expired))
        assertTrue(WebVpnAutoLogin.canRecover(app, expired, statusOnly = true))
        var starts = 0
        var received: SessionChecker.Result? = null
        SessionCoordinator.handleWebVpnCheck(app, expired, false, SessionCoordinator.CheckRequest(), { received = it },
            ensure = { listener ->
                starts++
                listener(SessionChecker.Result("valid", "synthetic-native-verified", vpnAuthState = "valid"))
                AutoCloseable {}
            })
        assertEquals(1, starts); assertEquals("valid", received!!.state)
    }

    @Test fun ssoRequiresItsEnabledAccountAndTheNewFullFlowSettingAndNeverClearsPauses() {
        repeat(4) { variant ->
            clear()
            when (variant) {
                0 -> NativeWebVpnAutoSettings(app).setEnabled(true) // Missing the SSO account.
                1 -> enable(Kind.SSO) // One saved account does not implicitly enable the full flow.
                2 -> { enable(Kind.SSO); enable(Kind.LIBRARY); NativeWebVpnAutoSettings(app).setEnabled(false) }
                3 -> { enable(Kind.SSO, paused = true); enable(Kind.LIBRARY) }
            }
            val expired = SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired")
            assertFalse(WebVpnAutoLogin.canRecover(app, expired))
            var received: SessionChecker.Result? = null
            SessionCoordinator.handleWebVpnCheck(app, expired, false, SessionCoordinator.CheckRequest(),
                { received = it }, ensure = { throw AssertionError("Unavailable native recovery must not start") })
            assertNotNull(received)
            assertTrue(received!!.detail.contains(when (variant) {
                0 -> "保存开启"; 3 -> "暂停"; else -> "全流程自动登录未开启"
            }))
            if (variant == 3) assertTrue(app.getSharedPreferences(Kind.SSO.storageName, 0).getBoolean("paused", false))
        }
    }

    @Test fun closingFullFlowSettingRetainsLibraryOnlyRecoveryWhenOuterSessionIsValid() {
        enable(Kind.SSO); enable(Kind.LIBRARY)
        NativeWebVpnAutoSettings(app).setEnabled(false)
        assertFalse(WebVpnAutoLogin.canRecover(app, SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired")))
        assertTrue(WebVpnAutoLogin.canRecover(app, SessionChecker.Result("expired", "expired", vpnAuthState = "valid")))
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
        harness.result = SessionChecker.Result("valid", "synthetic-native-verified", vpnAuthState = "valid")
        val request = WebVpnAutoLogin.ensure(app, updateSession = false) {}
        try {
            assertEquals("valid", runtime.prefs.getString("session_state", ""))
            harness.runAllWorkers()
            assertEquals("valid", runtime.prefs.getString("session_state", ""))
            assertEquals("DIRECT", runtime.prefs.getString("active_route", ""))
            assertEquals("DIRECT", runtime.prefs.getString("session_route", ""))
        } finally { request.close(); WebVpnAutoLogin.cancel() }
    }

    @Test fun aRealFakeFailureAppliesTheSameFiveMinuteCooldownToStatusAndScheduledRecovery() {
        val originalClock = WebVpnAutoLogin.wallClock
        var now = originalClock()
        WebVpnAutoLogin.wallClock = { now }
        enable(Kind.LIBRARY)
        val expired = SessionChecker.Result("expired", "expired", vpnAuthState = "valid")
        harness.result = SessionChecker.Result("unknown", "synthetic-native-failure")
        val request = WebVpnAutoLogin.ensure(app, updateSession = false) {}
        try {
            harness.runAllWorkers()
            assertFalse(WebVpnAutoLogin.canRecover(app, expired, statusOnly = true))
            assertFalse(WebVpnAutoLogin.canRecover(app, expired))
            assertTrue(WebVpnAutoLogin.recoveryUnavailableReason(app, expired)!!.contains("五分钟"))
            var blocked: SessionChecker.Result? = null
            WebVpnAutoLogin.ensure(app) { blocked = it }.close()
            assertTrue(blocked!!.detail.contains("五分钟"))
            assertEquals(1, harness.created)
            now += 299_999L
            assertFalse(WebVpnAutoLogin.canRecover(app, expired))
            now++
            assertTrue(WebVpnAutoLogin.canRecover(app, expired, statusOnly = true))
            assertTrue(WebVpnAutoLogin.canRecover(app, expired))
        } finally { request.close(); WebVpnAutoLogin.cancel(); WebVpnAutoLogin.wallClock = originalClock }
    }

    @Test fun newAccountRevisionClearsTheOldFailureKeyButCancellationDoesNotCreateACooldown() {
        enable(Kind.LIBRARY)
        val expired = SessionChecker.Result("expired", "expired", vpnAuthState = "valid")
        val first = WebVpnAutoLogin.ensure(app, updateSession = false) {}
        harness.runAllWorkers()
        assertFalse(WebVpnAutoLogin.canRecover(app, expired))
        enable(Kind.LIBRARY)
        assertTrue(WebVpnAutoLogin.canRecover(app, expired))
        val second = WebVpnAutoLogin.ensure(app) {}
        WebVpnAutoLogin.cancel(); second.close(); first.close()
        assertTrue(WebVpnAutoLogin.canRecover(app, expired))
        harness.runAllWorkers()
        assertEquals(1, harness.created) // The canceled second worker never constructed a recovery task.
    }
}
