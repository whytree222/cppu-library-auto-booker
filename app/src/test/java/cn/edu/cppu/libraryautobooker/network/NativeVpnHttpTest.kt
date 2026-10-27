package cn.edu.cppu.libraryautobooker.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fake connections only: exercises redirect/cookie behavior without any sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeVpnHttpTest {
    @Test fun casReturnIsRestoredOnlyForThePinnedServiceAndTrustedSchoolOrigin() {
        val expected = "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-service-ticket"
        val wrapped = "https://webvpn.cppu.edu.cn/https/${LibraryRoute.encryptHost("webvpn.cppu.edu.cn")}/login?cas_login=true&ticket=synthetic-service-ticket"
        assertFalse(NativeVpnUrls.allows(wrapped))
        assertEquals(expected, NativeVpnUrls.restoreCasCallback(NativeLoginFixture.action, wrapped))
        assertTrue(NativeVpnUrls.allows(expected))
        for (destination in listOf(
            "https://attacker.example/login?cas_login=true&ticket=synthetic-service-ticket",
            "http://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-service-ticket",
            "https://name@webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-service-ticket",
            "https://webvpn.cppu.edu.cn:444/login?cas_login=true&ticket=synthetic-service-ticket",
            "https://webvpn.cppu.edu.cn/login?cas_login=false&ticket=synthetic-service-ticket",
            "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=one&ticket=two",
            "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=",
            "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic%0d%0a-private"
        )) assertNull(NativeVpnUrls.restoreCasCallback(NativeLoginFixture.action, destination))
        assertNull(NativeVpnUrls.restoreCasCallback(NativeVpnUrls.libraryLogin, wrapped))
        assertNull(NativeVpnUrls.restoreCasCallback(NativeLoginFixture.action.replace("service=", "other="), wrapped))
        assertNull(NativeVpnUrls.restoreCasCallback(NativeLoginFixture.action.replace("webvpn.cppu.edu.cn%2Flogin", "attacker.example%2Flogin"), wrapped))
        assertNull(NativeVpnUrls.restoreCasCallback(NativeLoginFixture.action + "&service=untrusted", wrapped))
    }

    @Test fun queryOnlyRedirectsRetainTheDocumentAndFragmentsAreNeverSentInTransport() {
        val reference = "?cas_login=true&ticket=synthetic-service-ticket"
        val resolved = NativeVpnUrls.resolveReference(NativeLoginFixture.action, reference)
        assertEquals("${NativeVpnUrls.ssoPrefix}/tpass/login$reference", resolved)
        assertEquals("https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-service-ticket",
            NativeVpnUrls.restoreCasCallback(NativeLoginFixture.action, resolved))
        assertEquals(NativeLoginFixture.action, NativeVpnUrls.resolve(NativeLoginFixture.action, ""))
        val callback = "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-service-ticket"
        assertEquals(callback, NativeVpnUrls.withoutTransportFragment("$callback#/"))
        assertFalse(NativeVpnUrls.allows("$callback#/"))
    }

    @Test fun proxiedCasReturnCompletesTheVpnSessionBeforeTheLibraryLoginStepWithoutResendingPassword() {
        val fixture = NativeLoginFixture()
        val cookies = Cookies()
        val calls = mutableListOf<Connection>()
        val callback = "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-service-ticket"
        val wrapped = "https://webvpn.cppu.edu.cn/https/${LibraryRoute.encryptHost("webvpn.cppu.edu.cn")}/login?cas_login=true&ticket=synthetic-service-ticket"
        var ssoRequests = 0
        var vpnValid = false
        val libraryHtml = """<form id="fromuser" method="POST" action="login">
            <input id="user" name="user"><input id="passwd" name="passwd" type="password">
            <input id="url" name="url" type="hidden" value=""></form>"""
        val http = NativeVpnHttp({ url ->
            val connection = when (url) {
                NativeVpnUrls.libraryLogin -> if (vpnValid)
                    Connection(url, 200, bytes = libraryHtml.toByteArray())
                    else Connection(url, 302, "/login")
                "https://webvpn.cppu.edu.cn/login" -> Connection(url, 302, NativeLoginFixture.action)
                NativeLoginFixture.action -> if (ssoRequests++ == 0)
                    Connection(url, 200, bytes = fixture.ssoHtml.toByteArray())
                    else Connection(url, 302, wrapped, listOf("synthetic-cas=granted"))
                NativeVpnUrls.loginScript -> Connection(url, 200, bytes = fixture.script.toByteArray(), mime = "application/javascript")
                NativeVpnUrls.captcha -> Connection(url, 200, bytes = byteArrayOf(1, 2, 3), mime = "image/png")
                callback -> {
                    vpnValid = true
                    Connection(url, 200, issued = listOf("synthetic-vpn=valid"))
                }
                LibraryRoute.WEBVPN.url("/isLogin") -> Connection(url, 200,
                    bytes = (if (vpnValid) "{\"ReturnValue\":0}" else "{\"success\":false,\"url\":\"/login\"}").toByteArray(),
                    mime = "application/json")
                else -> throw AssertionError("Unexpected synthetic request")
            }
            calls.add(connection)
            connection
        }, cookies)
        NativeWebVpnSession(http) { 100L }.use { session ->
            val sso = offMain { session.load() }.form!!
            val next = offMain { session.submit(sso, "synthetic-user", "synthetic-password", "1234", true) }
            assertEquals(NativeVpnForm.Kind.LIBRARY, next.form!!.kind)
            assertEquals("valid", next.result.vpnAuthState)
            assertEquals("expired", next.result.state)
            assertEquals(1, calls.count { it.url.toString() == NativeLoginFixture.action && it.requestMethod == "POST" })
            val returned = calls.single { it.url.toString() == callback }
            assertEquals("GET", returned.requestMethod)
            assertEquals(0, returned.sent.size())
            assertFalse(calls.any { it.url.toString() == wrapped })
            val detail = session.diagnostics()
            assertTrue(detail.contains("认证回跳"))
            assertFalse(detail.contains("synthetic"))
            assertFalse(detail.contains("ticket="))
        }
        fixture.close()
    }

    @Test fun theSameCallbackCannotBeVisitedTwiceAndUnknownCasLikeLandingsStillStop() {
        val callback = "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-service-ticket"
        val queue = ArrayDeque(listOf(Connection(NativeLoginFixture.action, 302, callback), Connection(callback, 302, callback)))
        var calls = 0
        val http = NativeVpnHttp({ calls++; queue.removeFirst() }, Cookies())
        val failure = offMain { runCatching { http.request(NativeLoginFixture.action, "POST", "synthetic=body".toByteArray()) }.exceptionOrNull() }
        assertEquals(NativeVpnTransportFailure.Reason.CALLBACK_REPEATED, (failure as NativeVpnTransportFailure).reason)
        assertEquals(2, calls)
        http.close()
        var otherCalls = 0
        val unknown = NativeVpnHttp({ otherCalls++; Connection(it, 302, "/portal?cas_login=false&ticket=synthetic-private") }, Cookies())
        val response = offMain { unknown.request(NativeLoginFixture.action, "POST", "synthetic=body".toByteArray()) }
        assertEquals(302, response.code)
        assertEquals(1, otherCalls)
        assertFalse(unknown.diagnostics().joinToString().contains("synthetic"))
        unknown.close()
    }
    @Test fun cookieCompletionDoesNotCountAsAcceptanceAndPartialWritesAreRejected() {
        val receipt = NativeVpnCookieReceipt(2)
        receipt.record(true)
        receipt.record(false)
        val failure = assertThrows(NativeVpnTransportFailure::class.java) { receipt.await(10) { true } }
        assertEquals(NativeVpnTransportFailure.Reason.COOKIE_REJECTED, failure.reason)
        assertEquals(1, receipt.accepted.get())
    }
    @Test fun allCookieWritesMustCompleteSuccessfullyBeforeTheNextHop() {
        val pending = NativeVpnCookieReceipt(2)
        pending.record(true)
        val failure = assertThrows(NativeVpnTransportFailure::class.java) { pending.await(1) { true } }
        assertEquals(NativeVpnTransportFailure.Reason.COOKIE_TIMEOUT, failure.reason)
        val accepted = NativeVpnCookieReceipt(2)
        accepted.record(true); accepted.record(true)
        accepted.await(10) { true }
        assertEquals(2, accepted.accepted.get())
    }
    @Test fun requestTraceKeepsOnlyRouteCategoriesAndStatusCodes() {
        val callback = "https://webvpn.cppu.edu.cn/login?cas_login=true&ticket=synthetic-private-ticket"
        val first = Connection(NativeLoginFixture.action, 302, callback, listOf("synthetic-cookie=private"))
        val second = Connection(callback, 200, bytes = "synthetic-private-response".toByteArray())
        val queue = ArrayDeque(listOf(first, second))
        val http = NativeVpnHttp({ queue.removeFirst() }, Cookies())
        offMain { http.request(NativeLoginFixture.action, "POST", "password=synthetic-secret&authcode=1234".toByteArray()) }
        val detail = http.diagnostics().joinToString("\n")
        assertTrue(detail.contains("POST 统一认证"))
        assertTrue(detail.contains("HTTP 302"))
        assertTrue(detail.contains("GET WebVPN 登录或回跳"))
        assertFalse(detail.contains("synthetic"))
        assertFalse(detail.contains("1234"))
        assertFalse(detail.contains("ticket="))
        assertFalse(detail.contains("https://"))
        http.close()
        assertTrue(http.diagnostics().isEmpty())
    }
    private class Connection(url: String, private val status: Int, val destination: String? = null,
        private val issued: List<String> = emptyList(), private val bytes: ByteArray = "synthetic".toByteArray(),
        private val mime: String = "text/html; charset=UTF-8") : HttpURLConnection(URL(url)) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) destination else null
        override fun getHeaderFields(): MutableMap<String, MutableList<String>> = mutableMapOf("Set-Cookie" to issued.toMutableList())
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getOutputStream() = sent
        override fun getContentType() = mime
    }
    private class Cookies : NativeVpnCookies {
        var value: String? = null
        var accepted = 0
        override fun get(url: String) = value
        override fun accept(url: String, headers: List<String>, isOpen: () -> Boolean) {
            if (isOpen()) { accepted++; value = headers.last() }
        }
    }
    private fun <T> offMain(action: () -> T): T {
        val worker = Executors.newSingleThreadExecutor()
        return try { worker.submit<T> { action() }.get(5, TimeUnit.SECONDS) }
        finally { worker.shutdownNow() }
    }
    @Test fun alreadyLoggedInBusinessLandingUsesOnlyTheExistingProbeAndDoesNotOpenTheBusinessPage() {
        val entry = NativeVpnUrls.libraryLogin
        val landing = LibraryRoute.WEBVPN.url("/readingroommanage")
        assertFalse(NativeVpnUrls.allows(landing))
        val calls = mutableListOf<String>()
        val queue = ArrayDeque(listOf(
            Connection(entry, 302, landing),
            Connection(LibraryRoute.WEBVPN.url("/isLogin"), 200,
                bytes = "{\"ReturnValue\":1}".toByteArray(), mime = "application/json")
        ))
        val http = NativeVpnHttp({ url -> calls.add(url); queue.removeFirst().also { assertEquals(url, it.url.toString()) } }, Cookies())
        NativeWebVpnSession(http) { 100L }.use { session ->
            assertEquals("valid", offMain { session.load() }.result.state)
        }
        assertEquals(listOf(entry, LibraryRoute.WEBVPN.url("/isLogin")), calls)
    }
    @Test fun portalOrBusinessRedirectAloneNeverProvesAuthentication() {
        for (landing in listOf(LibraryRoute.WEBVPN.url("/readingroommanage"), "https://webvpn.cppu.edu.cn/portal", "https://webvpn.cppu.edu.cn/#/")) {
            val calls = mutableListOf<String>()
            val fragmentOnlyLanding = landing == "https://webvpn.cppu.edu.cn/#/"
            val queue = ArrayDeque<Connection>()
            queue.add(Connection(NativeVpnUrls.libraryLogin, 302, landing))
            // Fragments never reach HTTP: '/' was already an allowed GET route.
            // Rendering that landing page still must not prove authentication.
            if (fragmentOnlyLanding) queue.add(Connection("https://webvpn.cppu.edu.cn/", 200))
            queue.add(Connection(LibraryRoute.WEBVPN.url("/isLogin"), 200,
                bytes = "{\"success\":false,\"url\":\"/login\"}".toByteArray(), mime = "application/json"))
            val http = NativeVpnHttp({ url -> calls.add(url); queue.removeFirst() }, Cookies())
            val response = offMain { http.request(NativeVpnUrls.libraryLogin) }
            assertEquals(if (fragmentOnlyLanding) 200 else 302, response.code)
            if (fragmentOnlyLanding) assertEquals("https://webvpn.cppu.edu.cn/", response.url)
            val probe = offMain { http.request(LibraryRoute.WEBVPN.url("/isLogin"), "POST", byteArrayOf(), follow = false) }
            assertEquals("vpn_login_required", cn.edu.cppu.libraryautobooker.SessionChecker.decodeRoute(
                LibraryRoute.WEBVPN, probe.code, probe.location, probe.body.toString(Charsets.UTF_8)).state)
            http.close()
            assertEquals(if (fragmentOnlyLanding) 3 else 2, calls.size)
            assertFalse(calls.contains(landing))
        }
    }
    @Test fun credentialPostCanStopAtSameOriginLandingButNeverReplaysOrVisitsIt() {
        val landing = "https://webvpn.cppu.edu.cn/portal"
        val sent = Connection(NativeLoginFixture.action, 302, landing)
        var calls = 0
        val http = NativeVpnHttp({ calls++; sent }, Cookies())
        val response = offMain { http.request(NativeLoginFixture.action, "POST", "synthetic=body".toByteArray()) }
        assertEquals(302, response.code)
        assertEquals(landing, response.location)
        assertEquals(1, calls)
        assertEquals("synthetic=body", sent.sent.toString("UTF-8"))
        http.close()
    }
    @Test fun redirectFailureReportsFixedReasonWithoutExposingTheDestinationQuery() {
        for (landing in listOf("https://attacker.example/login?ticket=synthetic-private", "http://webvpn.cppu.edu.cn/login", "https://name@webvpn.cppu.edu.cn/portal", "https://webvpn.cppu.edu.cn/%2fportal")) {
            var calls = 0
            val http = NativeVpnHttp({ calls++; Connection(it, 302, landing) }, Cookies())
            val failure = offMain { runCatching { http.request(NativeVpnUrls.libraryLogin) }.exceptionOrNull() }
            assertTrue(failure is NativeVpnTransportFailure)
            val detail = NativeVpnFailure.from(failure!!, "L01 读取图书馆登录入口").explanation
            assertTrue(detail.contains("H04"))
            assertFalse(detail.contains("ticket"))
            assertFalse(detail.contains("synthetic"))
            assertEquals(1, calls)
            http.close()
        }
    }
    @Test fun ssoRedirectChangesPostToGetOnlyAfterTheIssuedCookieIsAccepted() {
        val cookies = Cookies()
        val first = Connection(NativeLoginFixture.action, 302, "/login?cas_login=true", listOf("synthetic-cookie=issued"))
        val second = Connection("https://webvpn.cppu.edu.cn/login?cas_login=true", 200)
        val queue = ArrayDeque(listOf(first, second))
        val http = NativeVpnHttp({ url -> queue.removeFirst().also { assertEquals(url, it.url.toString()) } }, cookies)
        offMain { http.request(NativeLoginFixture.action, "POST", "synthetic=body".toByteArray()) }
        assertEquals("POST", first.requestMethod); assertEquals("GET", second.requestMethod)
        assertEquals("synthetic-cookie=issued", second.getRequestProperty("Cookie"))
        assertEquals(0, second.sent.size()); assertEquals(1, cookies.accepted)
        http.close()
    }
    @Test fun credentialPreservingRedirectAndForeignLocationAreStoppedBeforeAnotherRequest() {
        for ((code, target) in listOf(307 to "/login", 308 to "/login", 302 to "https://attacker.example/login")) {
            var calls = 0
            val http = NativeVpnHttp({ calls++; Connection(NativeLoginFixture.action, code, target) }, Cookies())
            assertThrows(Exception::class.java) { offMain { http.request(NativeLoginFixture.action, "POST", "synthetic=body".toByteArray()) } }
            assertEquals(1, calls)
            http.close()
        }
    }
    @Test fun probeRedirectIsReturnedWithoutFollowingOrPostingToTheLoginPage() {
        var calls = 0
        val http = NativeVpnHttp({ calls++; Connection(it, 302, "/login") }, Cookies())
        val response = offMain { http.request(LibraryRoute.WEBVPN.url("/isLogin"), "POST", byteArrayOf(), follow = false) }
        assertEquals(302, response.code); assertEquals("/login", response.location); assertEquals(1, calls)
        http.close()
    }
    @Test fun closeAndOversizedResponseFailWithoutCreatingASecondRequest() {
        var calls = 0
        val http = NativeVpnHttp({ calls++; Connection(it, 200, bytes = ByteArray(1_048_577)) }, Cookies())
        assertThrows(Exception::class.java) { offMain { http.request(NativeVpnUrls.libraryLogin) } }
        assertEquals(1, calls)
        http.close()
        assertThrows(Exception::class.java) { offMain { http.request(NativeVpnUrls.libraryLogin) } }
        assertEquals(1, calls)
    }
}
