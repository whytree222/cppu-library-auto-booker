package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginStatusTest {
    private val vpn = LibraryRoute.WEBVPN
    private val store get() = LoginStatusStore(RuntimeEnvironment.getApplication())

    @Test fun libraryExpiredCanCoexistWithValidUnifiedAuth() {
        val result = SessionChecker.decodeRoute(vpn, 200, null, "{\"ReturnValue\":0}")
        store.record(vpn, result)
        assertEquals("valid", store.snapshot().vpnAuth.state)
        assertEquals("expired", store.snapshot().vpnLibrary.state)
        assertTrue(store.snapshot().vpnAuth.checkedAt > 0)
    }
    @Test fun vpnLoginRedirectDoesNotClaimLibraryExpired() {
        store.record(vpn, SessionChecker.decodeRoute(vpn, 302, "/login", ""))
        assertEquals("expired", store.snapshot().vpnAuth.state)
        assertEquals("unknown", store.snapshot().vpnLibrary.state)
    }
    @Test fun timeoutAndUnknownRedirectLeaveBothLayersUnknownNotExpired() {
        store.record(vpn, SessionChecker.Result("unknown", "SocketTimeoutException", true))
        assertEquals("unknown", store.snapshot().vpnAuth.state)
        assertEquals("unknown", store.snapshot().vpnLibrary.state)
        assertEquals("unknown", SessionChecker.decodeRoute(vpn, 302, "/unexpected", "").state)
    }
    @Test fun libraryLoginRedirectConfirmsVpnIsStillUsable() {
        store.record(vpn, SessionChecker.decodeRoute(vpn, 302, vpn.prefix + "/login", ""))
        assertEquals("valid", store.snapshot().vpnAuth.state)
        assertEquals("expired", store.snapshot().vpnLibrary.state)
    }
    @Test fun authenticationHtmlWith200IsNotMistakenForValidSession() {
        store.record(vpn, SessionChecker.decodeRoute(vpn, 200, null, "<h1>WebVPN 统一认证</h1><input type='password'>"))
        assertEquals("expired", store.snapshot().vpnAuth.state)
        assertEquals("unknown", store.snapshot().vpnLibrary.state)
    }
    @Test fun independentProbesDoNotOverwriteDirectStatus() {
        store.record(LibraryRoute.DIRECT, SessionChecker.Result("valid", "直连验证通过"))
        store.record(vpn, SessionChecker.Result("unknown", "网络异常", true))
        assertEquals("valid", store.snapshot().direct.state)
        assertEquals("unknown", store.snapshot().vpnAuth.state)
    }
    @Test fun libraryLoginHtmlConfirmsOnlyVpnAuthentication() {
        store.record(vpn, SessionChecker.decodeRoute(vpn, 200, null, "<form id='fromuser'><input id='passwd'></form>"))
        assertEquals("valid", store.snapshot().vpnAuth.state)
        assertEquals("expired", store.snapshot().vpnLibrary.state)
    }
}

