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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object SessionChecker {
    data class Result(val state: String, val detail: String, val networkFailure: Boolean = false)
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
            } finally { connection?.disconnect() }
            main.post {
                probeEpochs.deliver(ticket, CookieManager.getInstance().getCookie(url), result, accept = {
                    // A late response must never restore cookies from before a newer login.
                    // Generation checks apply to cookies as well as persisted status.
                    responseCookies.forEach { CookieManager.getInstance().setCookie(url, it) }
                    if (responseCookies.isNotEmpty()) CookieManager.getInstance().flush()
                    LoginStatusStore(app).record(route, it)
                    if (updateSession && id == generation.get()) {
                        RuntimeStore(app).session(it.state, it.detail)
                        RuntimeStore(app).prefs.edit().putString("session_route", route.name).apply()
                        if (it.state == "valid") RuntimeStore(app).prefs.edit().putString("active_route", route.name).apply()
                    }
                }, callback = callback)
            }
        }
    }

    internal fun isNetworkFailure(error: Exception) = error is UnknownHostException ||
        error is ConnectException || error is SocketTimeoutException ||
        error is NoRouteToHostException || error is SocketException

    internal fun decodeRoute(route: LibraryRoute, code: Int, location: String?, body: String): Result {
        if (code in 300..399) {
            val destination = runCatching { java.net.URI(route.url("/isLogin")).resolve(location.orEmpty()).toString() }.getOrDefault("")
            return if (route.pathOf(destination)?.trimEnd('/') == "/login")
                Result("expired", "图书馆登录已过期，请重新登录")
            else Result("unknown", "图书馆返回非预期跳转，请检查校园网络")
        }
        val decoded = decode(code, location, body)
        return Result(decoded.first, "${route.label}：${decoded.second}", code in listOf(408, 502, 503, 504))
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
