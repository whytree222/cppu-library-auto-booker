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
    private class Connection(url: String, private val status: Int, val destination: String? = null,
        private val issued: List<String> = emptyList(), private val bytes: ByteArray = "synthetic".toByteArray()) : HttpURLConnection(URL(url)) {
        val sent = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) destination else null
        override fun getHeaderFields(): MutableMap<String, MutableList<String>> = mutableMapOf("Set-Cookie" to issued.toMutableList())
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getOutputStream() = sent
        override fun getContentType() = "text/html; charset=UTF-8"
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
