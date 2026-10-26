package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.network.FallbackPolicy
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import android.webkit.CookieManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebVpnTest {
    private val vpn = LibraryRoute.WEBVPN
    private val proxy = "https://webvpn.cppu.edu.cn/http/77726476706e69737468656265737421fdfb489e693378406b468ca88d1b203b"

    @Test fun localConverterMatchesUserVerifiedUrl() {
        assertEquals("$proxy/multireadingroomtablelist", vpn.url("/multireadingroomtablelist"))
        assertEquals("/selectreadingroom", vpn.pathOf(vpn.url("/selectreadingroom?groupid=1")))
        assertEquals("http://mlib.cppu.edu.cn/isLogin", LibraryRoute.DIRECT.url("/isLogin"))
    }
    @Test fun routeIsolationRejectsOtherResourcesAndCredentialsInUrls() {
        assertNull(vpn.pathOf("https://webvpn.cppu.edu.cn/login"))
        assertNull(vpn.pathOf("https://evil.example" + vpn.prefix + "/selectreadingroom"))
        assertNull(vpn.pathOf("https://user@webvpn.cppu.edu.cn" + vpn.prefix + "/selectreadingroom"))
        assertNull(vpn.pathOf("http://webvpn.cppu.edu.cn" + vpn.prefix + "/selectreadingroom"))
        assertNull(vpn.pathOf("https://webvpn.cppu.edu.cn/http/other/selectreadingroom"))
    }
    @Test fun onlyOneWayFallbackBeforeSubmission() {
        val policy = FallbackPolicy(true)
        assertTrue(policy.switchForNetworkFailure())
        assertEquals(vpn, policy.route)
        assertFalse(policy.switchForNetworkFailure())
        assertFalse(policy.markSubmission(LibraryRoute.DIRECT))
        assertTrue(policy.markSubmission(vpn))
        assertFalse(FallbackPolicy(false).switchForNetworkFailure())
    }
    @Test fun uncertainSubmissionNeverSwitchesButConfirmedNextBatchCan() {
        val policy = FallbackPolicy(true)
        policy.markSubmission()
        assertFalse(policy.switchForNetworkFailure())
        policy.confirmedNextBatch()
        assertTrue(policy.switchForNetworkFailure())
    }
    @Test fun loginBusinessAndCertificateErrorsAreNotNetworkFallbacks() {
        assertTrue(SessionChecker.isNetworkFailure(UnknownHostException()))
        assertTrue(SessionChecker.isNetworkFailure(SocketTimeoutException()))
        assertFalse(SessionChecker.isNetworkFailure(SSLHandshakeException("bad certificate")))
        assertFalse(SessionChecker.decodeRoute(LibraryRoute.DIRECT, 403, null, "").networkFailure)
        assertFalse(SessionChecker.decodeRoute(LibraryRoute.DIRECT, 200, null, "bad-json").networkFailure)
        assertFalse(SessionChecker.decodeRoute(LibraryRoute.DIRECT, 200, null, "{\"ReturnValue\":0}").networkFailure)
        assertTrue(SessionChecker.decodeRoute(LibraryRoute.DIRECT, 503, null, "").networkFailure)
    }
    @Test fun vpnAuthAndLibraryAuthHaveDifferentStates() {
        assertEquals("vpn_login_required", SessionChecker.decodeRoute(vpn, 302, "/login", "").state)
        assertEquals("expired", SessionChecker.decodeRoute(vpn, 302, vpn.prefix + "/login", "").state)
        assertEquals("valid", SessionChecker.decodeRoute(vpn, 200, null, "{\"ReturnValue\":1}").state)
    }
    @Test fun exactSchoolSsoRedirectIsRecognizedButLookalikeIsNot() {
        listOf("https://sso.cppu.edu.cn/tpass/login", "https://webvpn.cppu.edu.cn${cn.edu.cppu.libraryautobooker.ocr.CaptchaPagePolicy.SSO_PROXY_PREFIX}/tpass/login").forEach {
            val result = SessionChecker.decodeRoute(vpn, 302, it, "")
            assertEquals("vpn_login_required", result.state)
            assertEquals("expired", result.vpnAuthState)
        }
        assertEquals("unknown", SessionChecker.decodeRoute(vpn, 302, "https://sso.cppu.edu.cn.attacker.example/tpass/login", "").state)
    }
    @Test fun webViewCookiesRemainInTheirOwnHostScope() {
        val manager = CookieManager.getInstance()
        manager.setCookie(LibraryRoute.DIRECT.url("/login"), "direct_session=fictional-direct; Path=/")
        manager.setCookie(vpn.url("/login"), "vpn_session=fictional-vpn; Path=/; Secure")
        assertTrue(manager.getCookie(vpn.url("/isLogin")).orEmpty().contains("fictional-vpn"))
        assertTrue(manager.getCookie(LibraryRoute.DIRECT.url("/isLogin")).orEmpty().contains("fictional-direct"))
        assertFalse(manager.getCookie(vpn.url("/isLogin")).orEmpty().contains("fictional-direct"))
        assertFalse(manager.getCookie(LibraryRoute.DIRECT.url("/isLogin")).orEmpty().contains("fictional-vpn"))
    }
}
