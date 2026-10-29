package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.NoRouteToHostException
import java.net.SocketException
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.ocr.CaptchaPagePolicy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

object SessionChecker {
    data class Result(val state: String, val detail: String, val networkFailure: Boolean = false,
        val vpnAuthState: String? = null)
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val probeEpochs = SessionProbeEpochs()
    private val main = Handler(Looper.getMainLooper())

    /** Invalidate in-flight read-only checks before authentication changes its document/session. */
    fun invalidateAuthentication(route: LibraryRoute) {
        probeEpochs.invalidateAuthentication(route)
    }

    // Same read-only login probe used by the school's readingroommanage.js.
    // Never persist or log the cookie or server response.
    fun check(context: Context, callback: (Boolean) -> Unit = {}) {
        checkDetailed(context) { callback(it == "valid") }
    }

    fun checkDetailed(context: Context, callback: (String) -> Unit) {
        probe(context, LibraryRoute.DIRECT) { callback(it.state) }
    }

    fun probe(context: Context, route: LibraryRoute, updateSession: Boolean = true, callback: (Result) -> Unit) {
        val permit = if (route == LibraryRoute.WEBVPN) WebVpnAuthOwnership.beginProbe() else null
        if (route == LibraryRoute.WEBVPN && permit == null) {
            main.post { callback(Result("logging_in", "WebVPN 认证正在处理，请等待本轮完成")) }
            return
        }
        val released = AtomicBoolean()
        fun releasePermit() { if (released.compareAndSet(false, true)) permit?.close() }
        try {
        val id = if (updateSession) generation.incrementAndGet() else -1
        val app = context.applicationContext
        LoginStatusStore(app).checking(route)
        if (updateSession) RuntimeStore(app).prefs.edit().putString("session_state", "checking")
            .putString("session_detail", "正在通过${route.label}验证图书馆登录…").apply()
        val url = route.url("/isLogin")
        val cookie = CookieManager.getInstance().getCookie(url)
        val ticket = probeEpochs.begin(route, cookie)
        executor.execute {
            var connection: HttpURLConnection? = null
            var responseCookies = emptyList<String>()
            val result = try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("Origin", route.origin)
                    setRequestProperty("Referer", route.url("/selectreadingroom"))
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }
                val code = connection.responseCode
                responseCookies = connection.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                    .values.flatten().filterNotNull()
                val body = if (code == 200) connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(65536)
                    var total = 0
                    while (total < buffer.size) {
                        val count = reader.read(buffer, total, buffer.size - total)
                        if (count < 0) break
                        total += count
                    }
                    String(buffer, 0, total)
                } else ""
                decodeRoute(route, code, connection.getHeaderField("Location"), body)
            } catch (error: Exception) {
                Result("unknown", "${route.label}登录检查失败：${error.javaClass.simpleName}", isNetworkFailure(error))
            } finally { runCatching { connection?.disconnect() } }
            if (!main.post {
                var transferred = false
                try {
                if (route == LibraryRoute.WEBVPN && WebVpnAuthOwnership.busy) {
                    releasePermit()
                    callback(Result("logging_in", "WebVPN 认证已开始，旧检查结果未应用"))
                    return@post
                }
                var acceptedNewest = false
                probeEpochs.deliver(ticket, CookieManager.getInstance().getCookie(url), result, accept = {
                    // A late response must never restore cookies from before a newer login.
                    // Generation checks apply to cookies as well as persisted status.
                    // Acceptance and delivery below are asynchronous: successful callers must
                    // never navigate using cookies that the browser has not accepted yet.
                    acceptedNewest = true
                }, callback = { accepted ->
                    val current = acceptedNewest && probeEpochs.isCurrent(ticket) &&
                        (route != LibraryRoute.WEBVPN || !WebVpnAuthOwnership.busy)
                    val bookingStatusOnly = !updateSession &&
                        cn.edu.cppu.libraryautobooker.booking.BookingService.running
                    val headers = if (current && !bookingStatusOnly) responseCookies else emptyList()
                    var writeFailed = false
                    val barrier = ProbeCookieCompletion(headers.size, { timedOut ->
                        val final = when {
                            timedOut -> Result("unknown", "登录检查的会话写入尚未完成，请稍后重试")
                            writeFailed -> Result("unknown", "登录检查的会话写入未成功，请稍后重试")
                            route == LibraryRoute.WEBVPN && WebVpnAuthOwnership.busy ->
                                Result("logging_in", "WebVPN 认证已开始，旧检查结果未应用")
                            !probeEpochs.authenticationCurrent(ticket) ->
                                Result("unknown", "登录检查已过时，请重新检查")
                            else -> accepted
                        }
                        try {
                            if (!timedOut && current && probeEpochs.isCurrent(ticket) &&
                                (route != LibraryRoute.WEBVPN || !WebVpnAuthOwnership.busy)) {
                                if (headers.isNotEmpty()) CookieManager.getInstance().flush()
                                LoginStatusStore(app).record(route, final)
                                if (updateSession && id == generation.get()) {
                                    RuntimeStore(app).session(final.state, final.detail)
                                    RuntimeStore(app).prefs.edit().putString("session_route", route.name).apply()
                                    if (final.state == "valid") RuntimeStore(app).prefs.edit().putString("active_route", route.name).apply()
                                }
                            }
                        } finally { callback(final) }
                    }, ::releasePermit)
                    transferred = true
                    headers.forEach { header ->
                        val receipt = barrier.receipt()
                        try { CookieManager.getInstance().setCookie(url, header) { ok ->
                            if (!ok) writeFailed = true
                            receipt()
                        } } catch (_: RuntimeException) { writeFailed = true; receipt() }
                    }
                    barrier.registrationComplete()
                    if (headers.isNotEmpty()) main.postDelayed({ barrier.timeout() }, 5000L)
                })
                } catch (_: Exception) {
                    if (!transferred) releasePermit()
                    // Callback failures must not leak the authentication lease.
                }
            }) releasePermit()
        }
        } catch (_: Exception) {
            releasePermit()
            main.post { callback(Result("unknown", "登录检查暂时不可用，请稍后重试")) }
        }
    }

    internal fun isNetworkFailure(error: Exception) = error is UnknownHostException ||
        error is ConnectException || error is SocketTimeoutException ||
        error is NoRouteToHostException || error is SocketException

    internal fun decodeRoute(route: LibraryRoute, code: Int, location: String?, body: String): Result {
        if (route == LibraryRoute.WEBVPN && code in 300..399) {
            val destination = runCatching { java.net.URI(route.url("/isLogin")).resolve(location.orEmpty()).toString() }.getOrDefault("")
            if (route.pathOf(destination)?.trimEnd('/') == "/login")
                return Result("expired", "WebVPN 通道的图书馆登录已过期或未登录", vpnAuthState = "valid")
            val uri = runCatching { java.net.URI(destination) }.getOrNull()
            if (CaptchaPagePolicy.isTrusted(destination))
                return Result("vpn_login_required", "学校要求重新进行统一认证，可使用独立保存的 WebVPN 账号恢复", vpnAuthState = "expired")
            if (uri?.scheme == "https" && uri.userInfo == null &&
                uri.host == "webvpn.cppu.edu.cn" && uri.path.orEmpty().trimEnd('/') == "/login")
                return Result("vpn_login_required", "WebVPN 要求重新统一认证，请打开应用内登录并手动输入验证码", vpnAuthState = "expired")
            return Result("unknown", "WebVPN 返回未识别的跳转，无法确认两层登录状态")
        }
        if (route == LibraryRoute.WEBVPN && code == 200) {
            // The WebVPN proxy answers proxied requests without a WebVPN session with its
            // own JSON envelope, not the library's ReturnValue response. This is not a
            // malformed school reply: it means the outer VPN login must be renewed.
            val vpnSessionEnded = runCatching {
                val json = JSONObject(body)
                !json.has("ReturnValue") && json.opt("success") == false && json.optString("url") == "/login"
            }.getOrDefault(false)
            if (vpnSessionEnded)
                return Result("vpn_login_required", "WebVPN 会话已结束，需要重新完成统一认证", vpnAuthState = "expired")
            // A login HTML page is not successful authentication just because HTTP is 200.
            if (Regex("<form[^>]+id=[\"']fromuser[\"']", RegexOption.IGNORE_CASE).containsMatchIn(body) &&
                Regex("<input[^>]+id=[\"']passwd[\"']", RegexOption.IGNORE_CASE).containsMatchIn(body))
                return Result("expired", "已到达图书馆登录页面，需要重新登录图书馆", vpnAuthState = "valid")
            if (Regex("<input[^>]+type=[\"']password[\"']", RegexOption.IGNORE_CASE).containsMatchIn(body) &&
                Regex("统一(?:身份)?认证|WebVPN", RegexOption.IGNORE_CASE).containsMatchIn(body))
                return Result("vpn_login_required", "学校返回 WebVPN 认证页面，需要完成统一认证", vpnAuthState = "expired")
        }
        val decoded = decode(code, location, body)
        return Result(decoded.first, "${route.label}：${decoded.second}", code in listOf(408, 502, 503, 504),
            if (route == LibraryRoute.WEBVPN && code == 200 && decoded.first in listOf("valid", "expired")) "valid" else null)
    }

    internal fun decode(code: Int, location: String?, body: String): Pair<String, String> = when {
        code in 300..399 && location.orEmpty().contains("/login") -> "expired" to "登录已失效，请重新登录"
        code != 200 -> "unknown" to "无法验证登录状态：服务器返回 HTTP $code"
        else -> try {
            val json = JSONObject(body)
            if (!json.has("ReturnValue")) "unknown" to "服务器返回格式不符，无法验证登录"
            else if (json.getInt("ReturnValue") > 0) "valid" to "已登录（学校系统验证通过）"
            else "expired" to "未登录或登录已失效，请重新登录"
        } catch (_: Exception) { "unknown" to "服务器返回格式不符，无法验证登录" }
    }
}

/** Cookie receipts may arrive synchronously, twice, or after the UI timeout. */
internal class ProbeCookieCompletion(
    count: Int,
    private val complete: (Boolean) -> Unit,
    private val release: () -> Unit
) {
    private var pending = count
    private var registered = false
    private var delivered = false
    private var delivering = false
    private var released = false
    fun receipt(): () -> Unit {
        val once = AtomicBoolean()
        return { if (once.compareAndSet(false, true)) synchronized(this) { pending--; drain(false) } }
    }
    @Synchronized fun registrationComplete() { registered = true; drain(false) }
    @Synchronized fun timeout() { drain(true) }
    private fun drain(timedOut: Boolean) {
        if (registered && !delivered && (pending == 0 || timedOut)) {
            delivered = true
            delivering = true
            try { complete(timedOut) } catch (_: Exception) { /* callers own their UI errors */ }
            finally { delivering = false }
        }
        if (registered && pending == 0 && delivered && !delivering && !released) {
            released = true
            release()
        }
    }
}

/**
 * A probe reads one cookie snapshot, not whichever authentication session exists on completion.
 * Keep the snapshot private and never include it in diagnostics, data classes, or test output.
 */
internal class SessionProbeEpochs {
    private val authentication = LibraryRoute.entries.associateWith { AtomicInteger() }
    private val probes = LibraryRoute.entries.associateWith { AtomicInteger() }

    internal class Ticket internal constructor(
        internal val route: LibraryRoute,
        internal val authenticationEpoch: Int,
        internal val probeEpoch: Int,
        private val cookieSnapshot: String?
    ) {
        internal fun matchesCookie(currentCookie: String?) = cookieSnapshot.orEmpty() == currentCookie.orEmpty()
        override fun toString() = "SessionProbeTicket(route=$route, cookie=<redacted>)"
    }

    fun begin(route: LibraryRoute, cookieSnapshot: String?): Ticket = Ticket(route,
        authentication.getValue(route).get(), probes.getValue(route).incrementAndGet(), cookieSnapshot)

    fun invalidateAuthentication(route: LibraryRoute) {
        authentication.getValue(route).incrementAndGet()
    }

    fun authenticationCurrent(ticket: Ticket) = ticket.authenticationEpoch == authentication.getValue(ticket.route).get()
    fun isCurrent(ticket: Ticket) = authenticationCurrent(ticket) && ticket.probeEpoch == probes.getValue(ticket.route).get()

    /** Only a current, unchanged session may publish response cookies or status. */
    fun deliver(ticket: Ticket, currentCookie: String?, result: SessionChecker.Result,
        accept: (SessionChecker.Result) -> Unit, callback: (SessionChecker.Result) -> Unit) {
        val unchangedSession = ticket.authenticationEpoch == authentication.getValue(ticket.route).get() &&
            ticket.matchesCookie(currentCookie)
        if (unchangedSession) {
            // Parallel status checks may observe the same unchanged session. Their callers can
            // use those results, but only the newest probe may mutate shared cookies or status.
            if (ticket.probeEpoch == probes.getValue(ticket.route).get()) accept(result)
            callback(result)
        } else {
            // Finish the caller conservatively instead of hanging it or treating an old success
            // as proof of the current login. Do not persist this stale result over the new state.
            callback(SessionChecker.Result("unknown", "登录检查已过时，认证会话已变化；请重新检查"))
        }
    }
}
