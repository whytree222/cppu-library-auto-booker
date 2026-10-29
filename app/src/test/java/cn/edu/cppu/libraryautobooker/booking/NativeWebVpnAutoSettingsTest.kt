package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import cn.edu.cppu.libraryautobooker.data.NativeWebVpnAutoSettings
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeWebVpnAutoSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun account(kind: Kind) = WebVpnCredentialStore(app, kind, { key })
    private fun enable(kind: Kind) = account(kind).save("synthetic-${kind.name}", "synthetic-password")

    @Before fun reset() {
        Kind.entries.forEach { prefs(it.storageName).edit().clear().commit() }
        listOf("native_webvpn_auto_login", "webvpn_ocr", "runtime_status", "auto_login").forEach {
            prefs(it).edit().clear().commit()
        }
    }

    @Test fun defaultsRequireBothSavedAndEnabledWebVpnAccounts() {
        val settings = NativeWebVpnAutoSettings(app)
        assertFalse(settings.enabled)
        enable(Kind.SSO)
        assertFalse(settings.enabled)
        enable(Kind.LIBRARY)
        assertTrue(settings.enabled)
        account(Kind.SSO).setEnabled(false)
        assertFalse(settings.enabled)
        account(Kind.SSO).setEnabled(true)
        account(Kind.LIBRARY).clear()
        assertFalse(settings.enabled)
        assertTrue(account(Kind.SSO).enabled)
    }

    @Test fun oldOcrPreferenceAndPausedAccountsAreNotRewritten() {
        enable(Kind.SSO); enable(Kind.LIBRARY)
        account(Kind.SSO).pause()
        prefs("webvpn_ocr").edit().putBoolean("enabled", false).putBoolean("remember_me", false).commit()
        val before = prefs(Kind.SSO.storageName).all
        assertTrue(NativeWebVpnAutoSettings(app).enabled)
        assertEquals(before, prefs(Kind.SSO.storageName).all)
        assertTrue(account(Kind.SSO).paused)
        assertFalse(prefs("webvpn_ocr").getBoolean("enabled", true))
        assertFalse(prefs("webvpn_ocr").getBoolean("remember_me", true))
    }

    @Test fun explicitDisableSurvivesResavingDeletingAndReenablingAccounts() {
        enable(Kind.SSO); enable(Kind.LIBRARY)
        NativeWebVpnAutoSettings(app).setEnabled(false)
        enable(Kind.SSO)
        account(Kind.LIBRARY).clear()
        enable(Kind.LIBRARY)
        account(Kind.SSO).setEnabled(false); account(Kind.SSO).setEnabled(true)
        assertFalse(NativeWebVpnAutoSettings(app).enabled)
        assertTrue(account(Kind.SSO).enabled)
        assertTrue(account(Kind.LIBRARY).enabled)
    }

    @Test fun explicitEnableIsRememberedButDoesNotManufactureOrResumeCredentials() {
        enable(Kind.SSO)
        account(Kind.SSO).pause()
        NativeWebVpnAutoSettings(app).setEnabled(true)
        assertTrue(NativeWebVpnAutoSettings(app).enabled)
        assertTrue(account(Kind.SSO).paused)
        assertFalse(account(Kind.LIBRARY).configured)
        assertNull(account(Kind.SSO).read())
    }

    @Test fun changingTheSwitchPreservesCredentialsTasksAndSeparatePreferences() {
        enable(Kind.SSO); enable(Kind.LIBRARY)
        val ssoBefore = prefs(Kind.SSO.storageName).all
        val libraryBefore = prefs(Kind.LIBRARY.storageName).all
        prefs("runtime_status").edit().putString("task_detail", "synthetic-existing-task").commit()
        prefs("auto_login").edit().putString("marker", "synthetic-direct-settings").commit()
        prefs("webvpn_ocr").edit().putBoolean("remember_me", false).commit()
        val settings = NativeWebVpnAutoSettings(app)
        settings.setEnabled(false); settings.setEnabled(true)
        assertEquals(ssoBefore, prefs(Kind.SSO.storageName).all)
        assertEquals(libraryBefore, prefs(Kind.LIBRARY.storageName).all)
        assertEquals("synthetic-existing-task", prefs("runtime_status").getString("task_detail", null))
        assertEquals("synthetic-direct-settings", prefs("auto_login").getString("marker", null))
        assertFalse(prefs("webvpn_ocr").getBoolean("remember_me", true))
    }
}
