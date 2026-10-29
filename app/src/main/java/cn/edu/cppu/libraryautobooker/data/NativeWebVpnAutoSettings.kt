package cn.edu.cppu.libraryautobooker.data

import android.content.Context

/** The full native SSO flow is independent from the retired webpage OCR preference. */
class NativeWebVpnAutoSettings(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("native_webvpn_auto_login", Context.MODE_PRIVATE)

    // Preserve the user's explicit choice across account edits and upgrades. Until
    // that choice is made, the two opted-in accounts enable the new native flow.
    // Reading configured/enabled never decrypts credentials or clears a pause.
    val enabled: Boolean
        get() = if (preferences.contains("enabled")) preferences.getBoolean("enabled", false)
        else WebVpnCredentialStore.Kind.entries.all { WebVpnCredentialStore(app, it).enabled }

    fun setEnabled(value: Boolean) {
        check(preferences.edit().putBoolean("enabled", value).commit())
    }
}
