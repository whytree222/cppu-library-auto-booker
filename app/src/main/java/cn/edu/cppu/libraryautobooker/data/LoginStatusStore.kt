package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

class LoginStatusStore(context: Context) {
    val prefs = RuntimeStore(context).prefs
    data class Entry(val state: String = "unknown", val detail: String = "尚未检查", val checkedAt: Long = 0)
    data class Snapshot(val direct: Entry, val webVpn: Entry)

    fun snapshot() = Snapshot(entry(LibraryRoute.DIRECT), entry(LibraryRoute.WEBVPN))

    private fun prefix(route: LibraryRoute) =
        if (route == LibraryRoute.DIRECT) "login_direct_" else "login_vpn_"

    private fun entry(route: LibraryRoute): Entry {
        val key = prefix(route)
        return Entry(prefs.getString("${key}state", "unknown").orEmpty(),
            prefs.getString("${key}detail", "尚未检查").orEmpty(), prefs.getLong("${key}at", 0))
    }

    fun checking(route: LibraryRoute) {
        val key = prefix(route)
        prefs.edit().putString("${key}state", "checking")
            .putString("${key}detail", "正在检查学校返回结果…").apply()
    }

    fun recovering(detail: String) {
        val key = prefix(LibraryRoute.WEBVPN)
        prefs.edit().putString("${key}state", "checking")
            .putString("${key}detail", detail).apply()
    }

    fun record(route: LibraryRoute, result: SessionChecker.Result) {
        val key = prefix(route)
        prefs.edit().putString("${key}state", result.state)
            .putString("${key}detail", result.detail)
            .putLong("${key}at", System.currentTimeMillis()).apply()
    }
}
