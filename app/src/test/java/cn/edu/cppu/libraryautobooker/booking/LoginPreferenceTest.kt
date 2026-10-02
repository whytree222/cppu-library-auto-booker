package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.LoginInput
import cn.edu.cppu.libraryautobooker.data.CredentialStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginPreferenceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun newInstallDefaultsCheckedButDoesNotPretendCredentialsExist() {
        val store = CredentialStore(app)
        assertTrue(store.preferredAutoLogin)
        assertFalse(store.enabled)
        assertFalse(store.configured)
        assertNull(store.read())
        assertEquals("", store.savedUsername())
    }

    @Test fun optOutIsRetainedAndDisablesAutomaticCredentialReading() {
        val prefs = app.getSharedPreferences("auto_login", 0)
        prefs.edit().putString("encrypted", "fake-ciphertext").putBoolean("enabled", true).commit()
        val store = CredentialStore(app)
        store.setEnabled(false)
        assertFalse(CredentialStore(app).preferredAutoLogin)
        assertFalse(store.enabled)
        assertNull(store.read())
    }

    @Test fun checkingPreferenceAloneDoesNotEnableUnverifiedCredentials() {
        val store = CredentialStore(app)
        store.setEnabled(false)
        store.setPreference(true)
        assertTrue(store.preferredAutoLogin)
        assertFalse(store.enabled)
        assertNull(store.read())
    }

    @Test fun validationPreservesPasswordSpacesAndRejectsNewlines() {
        assertNotNull(LoginInput.error("", "fake"))
        assertNotNull(LoginInput.error("demo", ""))
        assertNotNull(LoginInput.error("demo\n", "fake"))
        assertNotNull(LoginInput.error("demo", "fake\r"))
        assertNull(LoginInput.error(" demo ", " fake "))
    }
}
