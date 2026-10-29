package cn.edu.cppu.libraryautobooker

import android.os.Looper
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebVpnAuthOwnershipTest {
    @Test fun reservedWriterBlocksNewProbesAndWaitsForOldReceipts() {
        val oldProbe = requireNotNull(WebVpnAuthOwnership.beginProbe())
        val owner = Any()
        try {
            assertTrue(WebVpnAuthOwnership.reserve(owner))
            assertTrue(WebVpnAuthOwnership.busy)
            assertFalse(WebVpnAuthOwnership.ready(owner))
            assertNull(WebVpnAuthOwnership.beginProbe())
            oldProbe.close(); oldProbe.close()
            assertTrue(WebVpnAuthOwnership.ready(owner))
        } finally { oldProbe.close(); WebVpnAuthOwnership.release(owner) }
    }

    @Test fun staleOwnerCannotReleaseOrReplaceCurrentWriter() {
        val first = Any(); val second = Any()
        try {
            assertTrue(WebVpnAuthOwnership.reserve(first))
            assertFalse(WebVpnAuthOwnership.reserve(second))
            WebVpnAuthOwnership.release(first)
            assertTrue(WebVpnAuthOwnership.reserve(second))
            WebVpnAuthOwnership.release(first)
            assertTrue(WebVpnAuthOwnership.owns(second))
        } finally { WebVpnAuthOwnership.release(first); WebVpnAuthOwnership.release(second) }
    }

    @Test fun statusProbeDuringAuthenticationDoesNotTouchNetworkCookiesOrRecoveringState() {
        val app = RuntimeEnvironment.getApplication()
        val owner = Any()
        RuntimeStore(app).session("logging_in", "synthetic native recovery")
        LoginStatusStore(app).recovering("synthetic native recovery")
        assertTrue(WebVpnAuthOwnership.reserve(owner))
        try {
            var result: SessionChecker.Result? = null
            SessionChecker.probe(app, LibraryRoute.WEBVPN) { result = it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("logging_in", result!!.state)
            assertEquals("synthetic native recovery", RuntimeStore(app).prefs.getString("session_detail", ""))
            assertEquals("synthetic native recovery", LoginStatusStore(app).snapshot().webVpn.detail)
        } finally { WebVpnAuthOwnership.release(owner) }
    }

    @Test fun secondVisibleLoginNeverAcquiresNetworkUntilFirstOwnerIsClosed() {
        var firstReady: Boolean? = null; var secondReady: Boolean? = null
        val first = WebVpnAutoLogin.acquireInteractiveLogin { firstReady = it }
        val second = WebVpnAutoLogin.acquireInteractiveLogin { secondReady = it }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, firstReady)
            assertEquals(false, secondReady)
            second.close()
            assertTrue(WebVpnAuthOwnership.busy)
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
        } finally { second.close(); first.close() }
        assertFalse(WebVpnAuthOwnership.busy)
    }

    @Test fun manualAuthenticationCannotChangeCookiesDuringAnExistingReservation() {
        val running = cn.edu.cppu.libraryautobooker.booking.BookingService::class.java.getDeclaredField("running")
        running.isAccessible = true
        val previous = running.getBoolean(null)
        running.setBoolean(null, true)
        var ready: Boolean? = null
        val visible = WebVpnAutoLogin.acquireInteractiveLogin { ready = it }
        try {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
            assertEquals(false, ready)
            assertFalse(WebVpnAuthOwnership.busy)
        } finally { running.setBoolean(null, previous); visible.close() }
    }
}
