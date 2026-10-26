package cn.edu.cppu.libraryautobooker.network

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Login responses and cookies stay in memory; neither is included in diagnostics. */
internal class NativeVpnResponse(val url: String, val code: Int, val body: ByteArray,
    val contentType: String = "text/html", val location: String? = null) {
    override fun toString() = "NativeVpnResponse(code=$code, content=<redacted>)"
}

internal interface NativeVpnTransport : AutoCloseable {
    fun request(url: String, method: String = "GET", body: ByteArray? = null,
        referer: String? = null, follow: Boolean = true): NativeVpnResponse
    fun cookieBinding(url: String): String
}

internal object NativeVpnUrls {
    val libraryLogin = LibraryRoute.WEBVPN.url("/login")
    val ssoPrefix = "https://webvpn.cppu.edu.cn${WebVpnPages.SSO_PROXY_PREFIX}"
    val captcha = "$ssoPrefix/tpass/captcha.jpg"
    val loginScript = "$ssoPrefix/tpass/themes/sudy_ccpu/js/login.js"
    fun allows(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "webvpn.cppu.edu.cn" ||
            uri.port !in listOf(-1, 443) || uri.userInfo != null || uri.rawFragment != null ||
            uri.rawPath.contains('\\') || uri.rawPath.contains('%')) return@runCatching false
        uri.rawPath in setOf("/", "/login", "${WebVpnPages.SSO_PROXY_PREFIX}/tpass/login",
            "${WebVpnPages.SSO_PROXY_PREFIX}/tpass/captcha.jpg",
            "${WebVpnPages.SSO_PROXY_PREFIX}/tpass/themes/sudy_ccpu/js/login.js") ||
            LibraryRoute.WEBVPN.pathOf(url) in setOf("/login", "/isLogin", "/selectreadingroom", "/index")
    }.getOrDefault(false)
    fun resolve(base: String, value: String): String {
        val resolved = URI(base).resolve(value).toString()
        require(allows(resolved)) { "Untrusted login destination" }
        return resolved
    }
}

internal interface NativeVpnCookies {
    fun get(url: String): String?
    fun accept(url: String, headers: List<String>, isOpen: () -> Boolean)
}

internal class NativeVpnHttp(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val cookieAccess: NativeVpnCookies? = null
) : NativeVpnTransport {
    private val closed = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var connection: HttpURLConnection? = null
    private val cookies get() = CookieManager.getInstance()
    override fun cookieBinding(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(cookie(url).orEmpty().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cookie(url: String) = cookieAccess?.get(url) ?: if (cookieAccess == null) cookies.getCookie(url) else null

    override fun request(url: String, method: String, body: ByteArray?, referer: String?, follow: Boolean): NativeVpnResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Login I/O must not run on UI thread" }
        var next = url
        var verb = method
        var payload = body
        var previous = referer
        repeat(11) {
            check(!closed.get())
            require(NativeVpnUrls.allows(next))
            require(verb in setOf("GET", "POST"))
            if (verb == "POST") require(next == LibraryRoute.WEBVPN.url("/isLogin") ||
                LibraryRoute.WEBVPN.pathOf(next) == "/login" || WebVpnPages.isSso(next))
            val current = openConnection(next)
            connection = current
            try {
                current.apply {
                    requestMethod = verb; instanceFollowRedirects = false
                    connectTimeout = 10_000; readTimeout = 12_000; useCaches = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LibraryAutoBooker")
                    setRequestProperty("Cache-Control", "no-store")
                    cookie(next)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
                    previous?.takeIf(NativeVpnUrls::allows)?.let { setRequestProperty("Referer", it) }
                    if (verb == "POST") {
                        setRequestProperty("Origin", "https://webvpn.cppu.edu.cn")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        if (LibraryRoute.WEBVPN.pathOf(next) == "/isLogin")
                            setRequestProperty("X-Requested-With", "XMLHttpRequest")
                        doOutput = true
                        val bytes = payload ?: byteArrayOf()
                        require(bytes.size <= 32_768)
                        setFixedLengthStreamingMode(bytes.size)
                        outputStream.use { it.write(bytes) }
                    }
                }
                val code = current.responseCode
                val issued = current.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().filterNotNull()
                if (issued.isNotEmpty()) acceptCookies(next, issued)
                check(!closed.get())
                val location = current.getHeaderField("Location")
                if (follow && code in 300..399 && !location.isNullOrBlank()) {
                    // Never replay a credential POST through a redirect.
                    require(verb != "POST" || code in setOf(301, 302, 303))
                    previous = next; next = NativeVpnUrls.resolve(next, location)
                    verb = "GET"; payload = null
                } else {
                    val stream = if (code < 400) current.inputStream else current.errorStream
                    val bytes = stream?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 1_048_576) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: byteArrayOf()
                    require(bytes.size <= 1_048_576)
                    return NativeVpnResponse(next, code, bytes, current.contentType.orEmpty(), location)
                }
            } finally {
                current.disconnect()
                if (connection === current) connection = null
            }
        }
        error("Too many login redirects")
    }

    private fun acceptCookies(url: String, headers: List<String>) {
        if (cookieAccess != null) { cookieAccess.accept(url, headers) { !closed.get() }; return }
        // Await Android's cookie callback before following CAS redirects. Asynchronous
        // fire-and-forget writes can otherwise make the next hop lose its session.
        val ready = CountDownLatch(headers.size)
        main.post {
            if (closed.get()) { repeat(headers.size) { ready.countDown() }; return@post }
            headers.forEach { header -> cookies.setCookie(url, header) { ready.countDown() } }
        }
        check(ready.await(5, TimeUnit.SECONDS) && !closed.get())
    }

    override fun close() { closed.set(true); connection?.disconnect() }
}
