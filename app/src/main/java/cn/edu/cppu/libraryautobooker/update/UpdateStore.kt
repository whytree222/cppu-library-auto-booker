package cn.edu.cppu.libraryautobooker.update

import android.content.Context

class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("update_state", Context.MODE_PRIVATE)

    fun shouldAutoCheck(now: Long): Boolean {
        val lastSuccess = prefs.getLong("last_check_at", 0L)
        if (lastSuccess > 0 && now >= lastSuccess && now - lastSuccess < DAY_MS) return false
        val lastFailure = prefs.getLong("last_failure_at", 0L)
        if (lastFailure > 0 && now >= lastFailure && now - lastFailure < RETRY_MS) return false
        return true
    }

    fun markChecked(now: Long) {
        prefs.edit().putLong("last_check_at", now).remove("last_failure_at").apply()
    }

    fun markFailed(now: Long) {
        prefs.edit().putLong("last_failure_at", now).apply()
    }

    fun defer(versionCode: Int, until: Long) {
        prefs.edit().putInt("deferred_version", versionCode).putLong("deferred_until", until).apply()
    }

    fun isDeferred(versionCode: Int, now: Long): Boolean =
        prefs.getInt("deferred_version", -1) == versionCode && now < prefs.getLong("deferred_until", 0L)

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val RETRY_MS = 60 * 60 * 1000L
    }
}
