package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
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
    private val main = Handler(Looper.getMainLooper())

    // Same read-only login probe used by the school's readingroommanage.js.
    // Never persist or log the cookie or server response.
    fun check(context: Context, callback: (Boolean) -> Unit = {}) {
        checkDetailed(context) { callback(it == "valid") }
    }

    fun checkDetailed(context: Context, callback: (String) -> Unit) {
        probe(context, LibraryRoute.DIRECT) { callback(it.state) }
    }

    fun probe(context: Context, route: LibraryRoute, callback: (Result) -> Unit) {
        val id = generation.incrementAndGet()
        val app = context.applicationContext
        RuntimeStore(app).prefs.edit().putString("session_state", "checking")
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
                if (id == generation.get()) {
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
            if (route.pathOf(destination) != "/login")
                return Result("vpn_login_required", "WebVPN 统一认证已过期或需要验证码，请在应用内手动认证")
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

