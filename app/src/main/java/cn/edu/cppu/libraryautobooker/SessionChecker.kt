package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object SessionChecker {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    // Same read-only login probe used by the school's readingroommanage.js.
    // Never persist or log the cookie or server response.
    fun check(context: Context, callback: (Boolean) -> Unit = {}) {
        val id = generation.incrementAndGet()
        val app = context.applicationContext
        RuntimeStore(app).prefs.edit().putString("session_state", "checking")
            .putString("session_detail", "正在向学校系统验证登录…").apply()
        val cookie = CookieManager.getInstance().getCookie("http://mlib.cppu.edu.cn")
        executor.execute {
            var connection: HttpURLConnection? = null
            val result = try {
                connection = URL("http://mlib.cppu.edu.cn/isLogin").openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }
                val code = connection.responseCode
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
                decode(code, connection.getHeaderField("Location"), body)
            } catch (_: Exception) {
                "unknown" to "无法验证登录状态，请检查校园网络"
            } finally { connection?.disconnect() }
            main.post {
                if (id == generation.get()) {
                    RuntimeStore(app).session(result.first, result.second)
                    callback(result.first == "valid")
                }
            }
        }
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
