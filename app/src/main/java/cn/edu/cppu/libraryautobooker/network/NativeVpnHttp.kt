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
import java.util.concurrent.atomic.AtomicInteger

/** Login responses and cookies stay in memory; neither is included in diagnostics. */
internal class NativeVpnResponse(val url: String, val code: Int, val body: ByteArray,
    val contentType: String = "text/html", val location: String? = null) {
    override fun toString() = "NativeVpnResponse(code=$code, content=<redacted>)"
}

internal interface NativeVpnTransport : AutoCloseable {
    fun request(url: String, method: String = "GET", body: ByteArray? = null,
        referer: String? = null, follow: Boolean = true): NativeVpnResponse
    fun cookieBinding(url: String): String
    fun diagnostics(): List<String> = emptyList()
}

internal object NativeVpnUrls {
    private const val CAS_SERVICE = "https://webvpn.cppu.edu.cn/login?cas_login=true"
    val libraryLogin = LibraryRoute.WEBVPN.url("/login")
    val ssoPrefix = "https://webvpn.cppu.edu.cn${WebVpnPages.SSO_PROXY_PREFIX}"
    val ssoLogin = "$ssoPrefix/tpass/login?service=" + java.net.URLEncoder.encode(
        CAS_SERVICE, "UTF-8")
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
        val resolved = resolveReference(base, value)
        require(allows(resolved)) { "Untrusted login destination" }
        return resolved
    }

    // Java URI.resolve treats a query-only reference as the parent's directory.
    // Browser navigation retains the current document path instead.
    fun resolveReference(base: String, value: String): String {
        val reference = URI(value)
        if (reference.scheme == null && reference.rawAuthority == null && reference.rawPath.orEmpty().isEmpty()) {
            val document = base.substringBefore('#')
            val query = reference.rawQuery?.let { "?" + it }
            val fragment = reference.rawFragment?.let { "#" + it }.orEmpty()
            return (if (query != null) document.substringBefore('?') + query else document) + fragment
        }
        return URI(base).resolve(reference).toString()
    }

    private fun sameProxy(uri: URI) = uri.scheme == "https" && uri.host == "webvpn.cppu.edu.cn" &&
        uri.port in listOf(-1, 443) && uri.userInfo == null &&
        !uri.rawPath.orEmpty().contains('\\') && !uri.rawPath.orEmpty().contains('%')

    private fun query(uri: URI): Map<String, List<String>> {
        val raw = uri.rawQuery.orEmpty()
        require(raw.length <= 32_768)
        return raw.split('&').filter { it.isNotEmpty() }.map {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }.groupBy({ it.first }, { it.second })
    }

    private fun ticket(uri: URI): String? {
        val values = query(uri)
        if (values["cas_login"] != listOf("true")) return null
        val tickets = values["ticket"] ?: return null
        return tickets.singleOrNull()?.takeIf { it.length in 1..8192 && it.none { c -> c.isWhitespace() || c.isISOControl() } }
    }

    /** Restore only the pinned service from a trusted CAS response. No new endpoint is allowed. */
    fun restoreCasCallback(source: String, destination: String): String? = runCatching {
        if (!allows(source) || !WebVpnPages.isSso(source) || query(URI(source))["service"] != listOf(CAS_SERVICE))
            return@runCatching null
        val target = URI(destination)
        if (!sameProxy(target)) return@runCatching null
        val issued = ticket(target) ?: return@runCatching null
        CAS_SERVICE + "&ticket=" + java.net.URLEncoder.encode(issued, "UTF-8")
    }.getOrNull()

    fun isCasCallback(url: String): Boolean = runCatching {
        val uri = URI(url)
        sameProxy(uri) && uri.rawPath == "/login" && uri.rawFragment == null && ticket(uri) != null
    }.getOrDefault(false)

    fun withoutTransportFragment(url: String): String {
        val clean = url.substringBefore('#')
        return if (runCatching { sameProxy(URI(url)) }.getOrDefault(false) && allows(clean)) clean else url
    }

    fun stoppedRedirectSummary(url: String): String = runCatching {
        val uri = URI(url)
        val values = query(uri)
        val category = when {
            uri.rawPath == "/login/" -> "登录入口尾斜线"
            uri.rawPath.orEmpty().startsWith("${WebVpnPages.SSO_PROXY_PREFIX}/") -> "统一认证其他路径"
            uri.rawPath.orEmpty().startsWith("/https/${LibraryRoute.encryptHost("webvpn.cppu.edu.cn")}/") -> "WebVPN 自身代理路径"
            LibraryRoute.WEBVPN.pathOf(url) != null -> "图书馆业务路径"
            else -> "WebVPN 其他路径"
        }
        "同源跳转未继续：$category，CAS 标志${if (values["cas_login"] == listOf("true")) "有" else "无"}，票据参数${if (values.containsKey("ticket")) "有" else "无"}"
    }.getOrDefault("同源跳转未继续：无法分类")
}

internal interface NativeVpnCookies {
    fun get(url: String): String?
    fun accept(url: String, headers: List<String>, isOpen: () -> Boolean)
}

internal class NativeVpnTransportFailure(val reason: Reason) : java.io.IOException(reason.detail) {
    enum class Reason(val detail: String) {
        DESTINATION("H01 登录请求目标不在允许范围内"),
        POST_DESTINATION("H02 登录提交目标不符合规则"),
        POST_REDIRECT("H03 学校要求保留登录提交内容再次跳转，已停止以避免重复发送"),
        REDIRECT_DESTINATION("H04 学校跳转到不受支持的来源或地址形式，未继续访问"),
        RESPONSE_SIZE("H05 学校返回内容超过登录读取上限"),
        REDIRECT_FORMAT("H06 学校返回的跳转地址格式无法解析"),
        REQUEST_SETUP("H07 无法设置登录请求参数或请求头"),
        REQUEST_SIZE("H08 登录提交内容超过读取上限"),
        COOKIE_REJECTED("H09 系统未接受学校会话 Cookie，已停止后续请求"),
        COOKIE_TIMEOUT("H10 等待学校会话 Cookie 写入超时，已停止后续请求"),
        CALLBACK_REPEATED("H11 学校重复返回同一次认证回跳，已停止，未重复提交登录")
    }
}

/** A callback is completion, not proof that Android accepted the cookie. */
internal class NativeVpnCookieReceipt(private val expected: Int) {
    private val ready = CountDownLatch(expected)
    val accepted = AtomicInteger()
    fun record(success: Boolean) {
        if (success) accepted.incrementAndGet()
        ready.countDown()
    }
    fun await(timeoutMs: Long, isOpen: () -> Boolean) {
        val completed = ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        check(isOpen())
        if (!completed) throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.COOKIE_TIMEOUT)
        if (accepted.get() != expected) throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.COOKIE_REJECTED)
    }
}

internal class NativeVpnHttp(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val cookieAccess: NativeVpnCookies? = null
) : NativeVpnTransport {
    private val closed = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    private val events = ArrayDeque<String>()
    private fun record(event: String) = synchronized(events) {
        if (events.size == 32) events.removeFirst()
        events.addLast(event)
    }
    override fun diagnostics(): List<String> = synchronized(events) { events.toList() }
    private fun routeLabel(url: String): String = when {
        WebVpnPages.isSso(url) -> "统一认证"
        url.substringBefore('?') == NativeVpnUrls.captcha -> "验证码图片"
        url.substringBefore('?') == NativeVpnUrls.loginScript -> "学校加密脚本"
        LibraryRoute.WEBVPN.pathOf(url) == "/isLogin" -> "两层会话检查"
        LibraryRoute.WEBVPN.pathOf(url) == "/login" -> "图书馆登录"
        else -> "WebVPN 登录或回跳"
    }
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
        var callbackVisited = false
        repeat(11) {
            check(!closed.get())
            if (!NativeVpnUrls.allows(next)) throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.DESTINATION)
            require(verb in setOf("GET", "POST"))
            if (verb == "POST" && !(next == LibraryRoute.WEBVPN.url("/isLogin") ||
                LibraryRoute.WEBVPN.pathOf(next) == "/login" || WebVpnPages.isSso(next)))
                throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.POST_DESTINATION)
            record("$verb ${routeLabel(next)}")
            if (verb == "GET" && NativeVpnUrls.isCasCallback(next)) {
                if (callbackVisited) throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.CALLBACK_REPEATED)
                callbackVisited = true
                record("正在完成固定 WebVPN 服务的认证回跳 GET")
            }
            val current = openConnection(next)
            connection = current
            try {
                current.apply {
                    requestMethod = verb; instanceFollowRedirects = false
                    connectTimeout = 10_000; readTimeout = 12_000; useCaches = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LibraryAutoBooker")
                    setRequestProperty("Cache-Control", "no-store")
                    val sharedCookie = cookie(next)
                    record("请求携带共享 Cookie：${if (sharedCookie.isNullOrBlank()) "无" else "有"}")
                    sharedCookie?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
                    previous?.takeIf(NativeVpnUrls::allows)?.let { setRequestProperty("Referer", it) }
                    if (verb == "POST") {
                        setRequestProperty("Origin", "https://webvpn.cppu.edu.cn")
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        if (LibraryRoute.WEBVPN.pathOf(next) == "/isLogin")
                            setRequestProperty("X-Requested-With", "XMLHttpRequest")
                        doOutput = true
                        val bytes = payload ?: byteArrayOf()
                        if (bytes.size > 32_768) throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.REQUEST_SIZE)
                        setFixedLengthStreamingMode(bytes.size)
                        outputStream.use { it.write(bytes) }
                    }
                }
                val code = current.responseCode
                val kind = when {
                    current.contentType.orEmpty().contains("json", true) -> "JSON"
                    current.contentType.orEmpty().startsWith("image/", true) -> "图片"
                    current.contentType.orEmpty().contains("html", true) -> "HTML"
                    else -> "其他"
                }
                record("响应 HTTP $code，$kind")
                val issued = current.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().filterNotNull()
                if (issued.isNotEmpty()) acceptCookies(next, issued)
                check(!closed.get())
                val location = current.getHeaderField("Location")
                if (follow && code in 300..399 && !location.isNullOrBlank()) {
                    // Never replay a credential POST through a redirect.
                    if (verb == "POST" && code !in setOf(301, 302, 303))
                        throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.POST_REDIRECT)
                    var destination = try { NativeVpnUrls.resolveReference(next, location) }
                        catch (_: Exception) { throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.REDIRECT_FORMAT) }
                    val callback = NativeVpnUrls.restoreCasCallback(next, destination)
                    if (callback != null && callback != destination) {
                        destination = callback
                        record("已将代理改写的认证回跳还原为固定 WebVPN 登录入口 GET")
                    }
                    destination = NativeVpnUrls.withoutTransportFragment(destination)
                    if (!NativeVpnUrls.allows(destination)) {
                        if (isSameProxyLanding(destination)) {
                            // Do not request a new business/portal URL. Hand the
                            // redirect to the session layer, which must verify via
                            // the existing /isLogin probe before declaring success.
                            record(NativeVpnUrls.stoppedRedirectSummary(destination))
                            return NativeVpnResponse(next, code, byteArrayOf(), current.contentType.orEmpty(), location)
                        }
                        throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.REDIRECT_DESTINATION)
                    }
                    previous = next; next = destination
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
                    if (bytes.size > 1_048_576) throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.RESPONSE_SIZE)
                    return NativeVpnResponse(next, code, bytes, current.contentType.orEmpty(), location)
                }
            } catch (_: IllegalArgumentException) {
                throw NativeVpnTransportFailure(NativeVpnTransportFailure.Reason.REQUEST_SETUP)
            } finally {
                current.disconnect()
                if (connection === current) connection = null
            }
        }
        error("Too many login redirects")
    }

    /** A terminal response only; this does not add anything to the request allowlist. */
    private fun isSameProxyLanding(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "webvpn.cppu.edu.cn" &&
            uri.port in listOf(-1, 443) && uri.userInfo == null &&
            !uri.rawPath.orEmpty().contains('\\') && !uri.rawPath.orEmpty().contains('%')
    }.getOrDefault(false)

    private fun acceptCookies(url: String, headers: List<String>) {
        if (cookieAccess != null) {
            cookieAccess.accept(url, headers) { !closed.get() }
            record("共享 Cookie 存储已处理 ${headers.size} 项")
            return
        }
        // Await Android's cookie callback before following CAS redirects. Asynchronous
        // fire-and-forget writes can otherwise make the next hop lose its session.
        val receipt = NativeVpnCookieReceipt(headers.size)
        main.post {
            if (closed.get()) { repeat(headers.size) { receipt.record(false) }; return@post }
            headers.forEach { header -> cookies.setCookie(url, header) { receipt.record(it) } }
        }
        try { receipt.await(5_000) { !closed.get() } }
        finally { record("系统接受 Cookie ${receipt.accepted.get()}/${headers.size} 项") }
    }

    override fun close() { closed.set(true); connection?.disconnect(); synchronized(events) { events.clear() } }
}
