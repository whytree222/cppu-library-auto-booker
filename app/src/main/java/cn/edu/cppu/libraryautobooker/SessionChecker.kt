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
    data class Result(val state: String, val detail: String, val networkFailure: Boolean = false, val vpnAuthState: String? = null)
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val routeGenerations = LibraryRoute.entries.associateWith { AtomicInteger() }
    private val main = Handler(Looper.getMainLooper())

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
        val routeId = routeGenerations.getValue(route).incrementAndGet()
        val app = context.applicationContext
        LoginStatusStore(app).checking(route)
        if (updateSession) RuntimeStore(app).prefs.edit().putString("session_state", "checking")
            .putString("session_detail", "正在通过${route.label}验证图书馆登录…").apply()
        val url = route.url("/isLogin")
        val cookie = CookieManager.getInstance().getCookie(url)
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
                responseCookies.forEach { CookieManager.getInstance().setCookie(url, it) }
                if (responseCookies.isNotEmpty()) CookieManager.getInstance().flush()
                if (routeId == routeGenerations.getValue(route).get()) LoginStatusStore(app).record(route, result)
                if (updateSession && id == generation.get()) {
                    RuntimeStore(app).session(result.state, result.detail)
                    RuntimeStore(app).prefs.edit().putString("session_route", route.name).apply()
                    if (result.state == "valid") RuntimeStore(app).prefs.edit().putString("active_route", route.name).apply()
                }
                callback(result)
            }
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
            if (uri?.scheme == "https" && uri.userInfo == null &&
                uri.host == "webvpn.cppu.edu.cn" && uri.path.orEmpty().trimEnd('/') == "/login")
                return Result("vpn_login_required", "WebVPN 要求重新统一认证，请手动登录并完成验证码", vpnAuthState = "expired")
            return Result("unknown", "WebVPN 返回未识别的跳转，无法确认两层登录状态")
        }
        if (route == LibraryRoute.WEBVPN && code == 200) {
            // A login HTML page is not successful authentication just because HTTP is 200.
            if (Regex("<form[^>]+id=[\"']fromuser[\"']", RegexOption.IGNORE_CASE).containsMatchIn(body) &&
                Regex("<input[^>]+id=[\"']passwd[\"']", RegexOption.IGNORE_CASE).containsMatchIn(body))
                return Result("expired", "已到达图书馆登录页面，需要重新登录图书馆", vpnAuthState = "valid")
            if (Regex("<input[^>]+type=[\"']password[\"']", RegexOption.IGNORE_CASE).containsMatchIn(body) &&
                Regex("统一(?:身份)?认证|WebVPN", RegexOption.IGNORE_CASE).containsMatchIn(body))
                return Result("vpn_login_required", "学校返回 WebVPN 认证页面，请手动完成认证", vpnAuthState = "expired")
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

