package cn.edu.cppu.libraryautobooker

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebVpnAutoLoginSubscriptionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() { WebVpnAutoLogin.cancel() }

    @Test fun releasingOneSubscriberDoesNotCancelAnotherOrNotifyTheReleasedTask() {
        var first = 0
        var second = 0
        val firstRequest = WebVpnAutoLogin.ensure(app) { first++ }
        val secondRequest = WebVpnAutoLogin.ensure(app) { second++ }
        assertTrue(WebVpnAutoLogin.running)
        firstRequest.close()
        firstRequest.close()
        assertTrue(WebVpnAutoLogin.running)
        assertEquals(0, first)
        assertEquals(0, second)
        WebVpnAutoLogin.cancel()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, first)
        assertEquals(1, second)
        secondRequest.close()
        assertEquals(1, second)
    }

    @Test fun releasingLastSubscriberStopsRunWithoutCancellationCallbackAndAllowsFreshOwnership() {
        var callbacks = 0
        val request = WebVpnAutoLogin.ensure(app) { callbacks++ }
        assertTrue(WebVpnAutoLogin.running)
        request.close()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, callbacks)
        val next = WebVpnAutoLogin.ensure(app) { callbacks++ }
        assertTrue(WebVpnAutoLogin.running)
        request.close() // A stale handle must never stop the new run.
        assertTrue(WebVpnAutoLogin.running)
        next.close()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, callbacks)
    }

    @Test fun releasingAnotherSubscriberDuringDeliverySuppressesItsPendingCallback() {
        var secondCallbacks = 0
        lateinit var second: AutoCloseable
        WebVpnAutoLogin.ensure(app) { second.close() }
        second = WebVpnAutoLogin.ensure(app) { secondCallbacks++ }
        WebVpnAutoLogin.cancel()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, secondCallbacks)
    }

    @Test fun canceledProbeCannotAttachRecoveryOrDeliverItsLateResult() {
        val request = SessionCoordinator.CheckRequest()
        var closed = 0
        var delivered = 0
        request.close()
        request.attach(AutoCloseable { closed++ })
        request.deliver { delivered++ }
        assertFalse(request.active)
        assertEquals(1, closed)
        assertEquals(0, delivered)
    }

    @Test fun normalCompletionReleasesRecoveryExactlyOnceAndRejectsRepeatedCallbacks() {
        val request = SessionCoordinator.CheckRequest()
        var closed = 0
        var delivered = 0
        request.attach(AutoCloseable { closed++ })
        request.deliver { delivered++ }
        request.deliver { delivered++ }
        request.close()
        assertEquals(1, closed)
        assertEquals(1, delivered)
        assertFalse(request.active)
    }

    @Test fun visibleLoginCancelsHiddenOwnerAndRejectsLateRecoveryUntilClosed() {
        var oldCallbacks = 0
        val old = WebVpnAutoLogin.ensure(app) { oldCallbacks++ }
        val visible = WebVpnAutoLogin.acquireInteractiveLogin()
        try {
            assertFalse(WebVpnAutoLogin.running)
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
            assertEquals(1, oldCallbacks)
            var late: SessionChecker.Result? = null
            val blocked = WebVpnAutoLogin.ensure(app) { late = it }
            assertEquals("unknown", late!!.state)
            assertTrue(late!!.detail.contains("不启动另一轮"))
            assertFalse(WebVpnAutoLogin.running)
            blocked.close(); old.close()
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
        } finally { visible.close(); old.close() }
        assertFalse(WebVpnAutoLogin.interactiveLoginOpen)
        val next = WebVpnAutoLogin.ensure(app) {}
        assertTrue(WebVpnAutoLogin.running)
        visible.close() // An old release must not cancel a newer recovery.
        assertTrue(WebVpnAutoLogin.running)
        next.close()
    }

    @Test fun eachVisibleOwnerMustCloseAndStaleReleaseCannotUnlockAnother() {
        val first = WebVpnAutoLogin.acquireInteractiveLogin()
        val second = WebVpnAutoLogin.acquireInteractiveLogin()
        try {
            first.close(); first.close()
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
            val expired = SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired")
            assertFalse(WebVpnAutoLogin.canRecover(app, expired))
            assertTrue(WebVpnAutoLogin.recoveryUnavailableReason(app, expired)!!.contains("登录页面"))
        } finally { first.close(); second.close() }
        assertFalse(WebVpnAutoLogin.interactiveLoginOpen)
    }
}
