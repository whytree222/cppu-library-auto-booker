package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.network.WebVpnPages
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginStatusTest {
    private val direct = LibraryRoute.DIRECT
    private val vpn = LibraryRoute.WEBVPN
    private val store get() = LoginStatusStore(RuntimeEnvironment.getApplication())
    private val proxy = "/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b"

    @Test fun directServerLoginResultIsRecordedWithTimestamp() {
        store.record(direct, SessionChecker.decodeRoute(direct, 200, null, "{\"ReturnValue\":1}"))
        assertEquals("valid", store.snapshot().direct.state)
        assertTrue(store.snapshot().direct.checkedAt > 0)
    }
    @Test fun confirmedLoggedOutAndNetworkUnknownRemainDistinct() {
        store.record(direct, SessionChecker.decodeRoute(direct, 200, null, "{\"ReturnValue\":0}"))
        assertEquals("expired", store.snapshot().direct.state)
        store.record(direct, SessionChecker.Result("unknown", "校园网络不可达", true))
        assertEquals("unknown", store.snapshot().direct.state)
    }
    @Test fun externalOrUnknownHtmlIsNeverValidAuthentication() {
        assertEquals("unknown", SessionChecker.decodeRoute(direct, 200, null, "<html>login</html>").state)
        assertEquals("unknown", SessionChecker.decodeRoute(direct, 502, null, "").state)
    }
    @Test fun routesAreExactlyCampusDirectAndTheVerifiedWebVpnProxy() {
        assertEquals(listOf(LibraryRoute.DIRECT, LibraryRoute.WEBVPN), LibraryRoute.entries)
        assertEquals("http://mlib.cppu.edu.cn", direct.origin)
        assertEquals("https://webvpn.cppu.edu.cn", vpn.origin)
        assertEquals(proxy, vpn.prefix)
        assertEquals("https://webvpn.cppu.edu.cn$proxy/selectreadingroom",
            vpn.url("/selectreadingroom"))
        assertEquals("/selectreadingroom", vpn.pathOf(vpn.url("/selectreadingroom")))
        assertNull(direct.pathOf(vpn.url("/selectreadingroom")))
        assertNull(vpn.pathOf("https://webvpn.cppu.edu.cn/login"))
        assertNull(vpn.pathOf("http://webvpn.cppu.edu.cn$proxy/login"))
        assertNull(vpn.pathOf("https://user@webvpn.cppu.edu.cn$proxy/login"))
        assertNull(vpn.pathOf("https://other.example$proxy/login"))
        assertNull(direct.pathOf("http://other.example/selectreadingroom"))
        assertNull(direct.pathOf("http://user@mlib.cppu.edu.cn/login"))
        assertEquals("/selectreadingroom", direct.pathOf(direct.url("/selectreadingroom")))
        assertEquals("77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b",
            LibraryRoute.encryptHost("mlib.cppu.edu.cn"))
    }
    @Test fun webVpnStatusIsStoredSeparatelyFromDirect() {
        store.record(vpn, SessionChecker.Result("vpn_login_required", "请完成网页登录"))
        assertEquals("vpn_login_required", store.snapshot().webVpn.state)
        assertEquals("unknown", store.snapshot().direct.state)
        store.record(direct, SessionChecker.Result("valid", "直连有效"))
        assertEquals("valid", store.snapshot().direct.state)
        assertEquals("vpn_login_required", store.snapshot().webVpn.state)
    }
    @Test fun webVpnRedirectsDistinguishLibraryExpiryFromSsoLogin() {
        val sso = "https://sso.cppu.edu.cn/tpass/login"
        assertEquals("expired", SessionChecker.decodeRoute(vpn, 302,
            "https://webvpn.cppu.edu.cn$proxy/login", "").state)
        assertEquals("vpn_login_required", SessionChecker.decodeRoute(vpn, 302, sso, "").state)
        assertEquals("vpn_login_required", SessionChecker.decodeRoute(vpn, 302,
            "https://webvpn.cppu.edu.cn/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d/tpass/login",
            "").state)
        assertEquals("vpn_login_required", SessionChecker.decodeRoute(vpn, 302,
            "https://webvpn.cppu.edu.cn/login", "").state)
        assertEquals("unknown", SessionChecker.decodeRoute(vpn, 302,
            "https://webvpn.cppu.edu.cn/unknown", "").state)
    }
    @Test fun webVpnHttp200LoginPagesAreNotAuthentication() {
        val libraryForm = "<form id=\"fromuser\"><input id=\"passwd\" type=\"password\"></form>"
        assertEquals("expired", SessionChecker.decodeRoute(vpn, 200, null, libraryForm).state)
        val ssoPage = "<input type=\"password\">统一认证"
        assertEquals("vpn_login_required", SessionChecker.decodeRoute(vpn, 200, null, ssoPage).state)
        assertEquals("valid", SessionChecker.decodeRoute(vpn, 200, null, "{\"ReturnValue\":1}").state)
    }
    @Test fun webVpnProxySessionEndedEnvelopeMeansRenewalNotMalformedReply() {
        val ended = """{"message":"您的会话已结束，请重新登录","success":false,"url":"/login"}"""
        val result = SessionChecker.decodeRoute(vpn, 200, null, ended)
        assertEquals("vpn_login_required", result.state)
        assertEquals("expired", result.vpnAuthState)
        assertFalse(result.detail.contains("格式不符"))
        // Only the proxy's exact session-ended envelope is reclassified.
        assertEquals("unknown", SessionChecker.decodeRoute(vpn, 200, null, "{\"success\":false}").state)
        assertEquals("unknown", SessionChecker.decodeRoute(direct, 200, null, ended).state)
        assertEquals("expired", SessionChecker.decodeRoute(vpn, 200, null, "{\"ReturnValue\":0}").state)
    }
    @Test fun manualLoginPagePolicyAllowsOnlySchoolVpnAndSso() {
        assertTrue(WebVpnPages.allows("https://webvpn.cppu.edu.cn/"))
        assertTrue(WebVpnPages.allows("https://webvpn.cppu.edu.cn/login"))
        assertTrue(WebVpnPages.allows("https://sso.cppu.edu.cn/tpass/login"))
        assertTrue(WebVpnPages.allows(vpn.url("/login")))
        assertTrue(WebVpnPages.allows("https://webvpn.cppu.edu.cn/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d/tpass/login"))
        assertFalse(WebVpnPages.allows("https://sso.cppu.edu.cn/other"))
        assertFalse(WebVpnPages.allows("https://webvpn.cppu.edu.cn/other"))
        assertFalse(WebVpnPages.allows("http://webvpn.cppu.edu.cn/login"))
        assertFalse(WebVpnPages.allows("https://mlib.cppu.edu.cn/login"))
    }
}
