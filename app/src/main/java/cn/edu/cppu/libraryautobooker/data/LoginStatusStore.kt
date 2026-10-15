package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

class LoginStatusStore(context: Context) {
    val prefs = RuntimeStore(context).prefs
    data class Entry(val state: String = "unknown", val detail: String = "尚未检查", val checkedAt: Long = 0)
    data class Snapshot(val direct: Entry, val vpnAuth: Entry, val vpnLibrary: Entry)

    fun snapshot() = Snapshot(read("direct"), read("vpn_auth"), read("vpn_library"))
    private fun read(key: String) = Entry(prefs.getString("login_${key}_state", "unknown").orEmpty(),
        prefs.getString("login_${key}_detail", "尚未检查").orEmpty(), prefs.getLong("login_${key}_at", 0))

    fun checking(route: LibraryRoute) {
        val keys = if (route == LibraryRoute.DIRECT) listOf("direct") else listOf("vpn_auth", "vpn_library")
        val editor = prefs.edit()
        keys.forEach { editor.putString("login_${it}_state", "checking").putString("login_${it}_detail", "正在检查学校返回结果…") }
        editor.apply()
    }

    fun recovering(detail: String) {
        val editor = prefs.edit()
        listOf("vpn_auth", "vpn_library").forEach {
            editor.putString("login_${it}_state", "checking").putString("login_${it}_detail", detail)
        }
        editor.apply()
    }

    fun record(route: LibraryRoute, result: SessionChecker.Result) {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        fun put(key: String, state: String, detail: String) {
            editor.putString("login_${key}_state", state).putString("login_${key}_detail", detail).putLong("login_${key}_at", now)
        }
        if (route == LibraryRoute.DIRECT) put("direct", result.state, result.detail)
        else {
            val auth = result.vpnAuthState ?: "unknown"
            put("vpn_auth", auth, when (auth) {
                "valid" -> "已通过 WebVPN 认证并到达图书馆服务；不保证固定五天有效"
                "expired" -> result.detail.ifBlank { "学校要求重新进行统一认证，可能未登录或会话已过期" }
                else -> "未获得足够响应，无法确认统一认证是否有效"
            })
            put("vpn_library", if (result.state in listOf("valid", "expired")) result.state else "unknown",
                if (auth == "expired") "需先恢复 WebVPN 统一认证；${result.detail}" else result.detail)
        }
        editor.apply()
    }
}
