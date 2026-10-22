package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import cn.edu.cppu.libraryautobooker.booking.BookingScheduler
import org.json.JSONArray
import java.security.KeyStore

/** Erases only retired feature data, never reads/decrypts passwords or clears campus cookies. */
object LegacyFeatureMigration {
    @Synchronized fun run(context: Context) {
        val prefs = context.getSharedPreferences("booking_config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("campus_only_fixed_plan_v17", false)) return
        val wasEnabled = prefs.getBoolean("enabled", false)
        val oldAt = prefs.getLong("scheduled_at", 0L)
        BookingScheduler(context).cancel()
        listOf("webvpn_sso_credentials_v1", "webvpn_library_credentials_v1", "webvpn_ocr").forEach {
            check(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
        }
        runCatching {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            listOf("webvpn_sso_login_v1", "webvpn_library_login_v1").forEach {
                if (keys.containsAlias(it)) keys.deleteEntry(it)
            }
        }
        val runtime = RuntimeStore(context)
        val state = runtime.prefs.edit()
        runtime.prefs.all.keys.filter { it.startsWith("login_vpn_") }.forEach(state::remove)
        state.putString("active_route", "DIRECT").putString("session_route", "DIRECT")
            .putString("session_state", "unknown").putString("session_detail", "请重新检查校园网直连登录").commit()
        check(prefs.edit().putBoolean("enabled", false).putLong("scheduled_at", 0)
            .putString("scheduled_times", "[]").putString("release_times", JSONArray(listOf(58)).toString())
            .putInt("release_hour", 0).putInt("release_minute", 58)
            .putBoolean("dry_run", false).putBoolean("wait_for_release", true)
            .remove("webvpn_backup").putBoolean("campus_only_fixed_plan_v17", true).commit())
        if (wasEnabled) runtime.record("CANCELLED",
            "升级为校园网直连与固定 00:58 预约：旧自定义时间计划已取消；请核对座位和使用时段后重新点击预约",
            if (oldAt > 0) RuntimeStore.scheduledAction(oldAt) else "upgrade-fixed-plan-v17", "升级取消旧计划")
    }
}
