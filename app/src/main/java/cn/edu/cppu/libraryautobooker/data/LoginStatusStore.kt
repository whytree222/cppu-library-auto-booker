package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

class LoginStatusStore(context: Context) {
    val prefs = RuntimeStore(context).prefs
    data class Entry(val state: String = "unknown", val detail: String = "尚未检查", val checkedAt: Long = 0)
    data class Snapshot(val direct: Entry)

    fun snapshot() = Snapshot(Entry(prefs.getString("login_direct_state", "unknown").orEmpty(),
        prefs.getString("login_direct_detail", "尚未检查").orEmpty(), prefs.getLong("login_direct_at", 0)))

    fun checking(route: LibraryRoute) {
        prefs.edit().putString("login_direct_state", "checking")
            .putString("login_direct_detail", "正在检查学校返回结果…").apply()
    }

    fun record(route: LibraryRoute, result: SessionChecker.Result) {
        prefs.edit().putString("login_direct_state", result.state)
            .putString("login_direct_detail", result.detail)
            .putLong("login_direct_at", System.currentTimeMillis()).apply()
    }
}
