package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import android.util.Base64
import cn.edu.cppu.libraryautobooker.data.CredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebVpnCredentialStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    // Synthetic fixture only. Production uses a separate AndroidKeyStore key for each account kind.
    private val fixtureKey: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    @Before fun clearAccountPreferences() {
        Kind.entries.forEach { preferences(it).edit().clear().commit() }
        app.getSharedPreferences("auto_login", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun preferences(kind: Kind) = app.getSharedPreferences(kind.storageName, Context.MODE_PRIVATE)
    private fun store(kind: Kind) = WebVpnCredentialStore(app, kind, { fixtureKey })

    @Test fun newAccountsAreUnconfiguredAndNeverReadDirectCredentials() {
        app.getSharedPreferences("auto_login", Context.MODE_PRIVATE).edit()
            .putString("encrypted", "direct-only-ciphertext")
            .putString("iv", "direct-only-iv").putBoolean("enabled", true).commit()
        Kind.entries.forEach { kind ->
            val account = store(kind)
            assertFalse(account.configured)
            assertFalse(account.enabled)
            assertFalse(account.paused)
            assertFalse(account.verified)
            assertEquals("", account.revision)
            assertEquals("", account.savedUsername())
            assertNull(account.read())
        }
        assertTrue(CredentialStore(app).enabled)
    }

    @Test fun ssoAndProxyLibraryRemainIndependentFromEachOtherAndDirectLogin() {
        val directPreferences = app.getSharedPreferences("auto_login", Context.MODE_PRIVATE)
        directPreferences.edit().putBoolean("enabled", false).putString("marker", "direct-settings").commit()
        val sso = store(Kind.SSO)
        val library = store(Kind.LIBRARY)
        sso.save("synthetic-student", "sso-fixture-password")
        library.save("synthetic-reader", "library-fixture-password")

        assertEquals(WebVpnCredentialStore.Credentials("synthetic-student", "sso-fixture-password"), sso.read())
        assertEquals(WebVpnCredentialStore.Credentials("synthetic-reader", "library-fixture-password"), library.read())
        assertNotEquals(Kind.SSO.storageName, Kind.LIBRARY.storageName)
        assertNotEquals(Kind.SSO.keyAlias, Kind.LIBRARY.keyAlias)
        sso.pause()
        assertNull(sso.read())
        assertNotNull(library.read())
        library.setEnabled(false)
        assertTrue(sso.enabled)
        assertFalse(library.enabled)
        assertFalse(directPreferences.getBoolean("enabled", true))
        assertEquals("direct-settings", directPreferences.getString("marker", null))
    }

    @Test fun savedPreferencesContainAuthenticatedCiphertextNotPlaintextCredentials() {
        val account = store(Kind.SSO)
        val username = "synthetic-account-81047"
        val password = "synthetic-secret-a40925"
        account.save(username, password)
        val persisted = preferences(Kind.SSO).all.values.joinToString("|")
        assertFalse(persisted.contains(username))
        assertFalse(persisted.contains(password))
        assertFalse(preferences(Kind.SSO).contains("username"))
        assertFalse(preferences(Kind.SSO).contains("password"))
        val iv = Base64.decode(preferences(Kind.SSO).getString("iv", null), Base64.NO_WRAP)
        assertEquals(12, iv.size)
        assertEquals(username, account.read()?.username)
        assertEquals(password, account.read()?.password)
        assertEquals(username, account.savedUsername())
        assertFalse(account.read().toString().contains(username))
        assertFalse(account.read().toString().contains(password))
    }

    @Test fun savingEnablesButDoesNotPretendServerValidationHasSucceeded() {
        val account = store(Kind.SSO)
        account.save("student", "fixture-password")
        assertTrue(account.configured)
        assertTrue(account.enabled)
        assertFalse(account.paused)
        assertFalse(account.verified)
        assertNotNull(account.read())
        account.markVerified()
        assertTrue(account.verified)
        account.save("student", "changed-fixture-password")
        assertFalse(account.verified)
        assertEquals("changed-fixture-password", account.read()?.password)
    }

    @Test fun pauseBlocksReadingAndResumeDoesNotClaimSuccessfulValidation() {
        val account = store(Kind.LIBRARY)
        account.save("reader", "fixture-password")
        account.markVerified()
        account.pause()
        assertTrue(account.enabled)
        assertTrue(account.paused)
        assertFalse(account.verified)
        assertNull(account.read())
        assertEquals("reader", account.savedUsername())
        account.resume()
        assertFalse(account.paused)
        assertFalse(account.verified)
        assertEquals("reader", account.read()?.username)
        account.setEnabled(false)
        assertNull(account.read())
        assertTrue(account.configured)
        account.resume()
        assertNull(account.read())
    }

    @Test fun eachSaveChangesRevisionEvenWhenCredentialsAreUnchanged() {
        val account = store(Kind.SSO)
        assertEquals("", account.revision)
        account.save("student", "fixture-password")
        val initial = account.revision
        assertTrue(initial.isNotBlank())
        account.markVerified()
        account.pause()
        account.resume()
        account.setEnabled(false)
        account.setEnabled(true)
        assertEquals(initial, account.revision)
        assertEquals(initial, store(Kind.SSO).revision)
        account.save("student", "fixture-password")
        val next = account.revision
        assertNotEquals(initial, next)
        assertFalse(account.verified)
        account.save("changed-student", "fixture-password")
        assertNotEquals(next, account.revision)
        assertEquals("", store(Kind.LIBRARY).revision)
    }

    @Test fun copyingCiphertextBetweenAccountKindsFailsClosedEvenWithSameFixtureKey() {
        val sso = store(Kind.SSO)
        sso.save("student", "sso-fixture-password")
        val original = preferences(Kind.SSO)
        preferences(Kind.LIBRARY).edit()
            .putString("encrypted", original.getString("encrypted", null))
            .putString("iv", original.getString("iv", null))
            .putBoolean("enabled", true).putBoolean("verified", true).commit()

        val library = store(Kind.LIBRARY)
        assertNull(library.read())
        assertTrue(library.paused)
        assertFalse(library.verified)
        assertEquals("", library.savedUsername())
        assertNotNull(sso.read())
        assertFalse(sso.paused)
    }

    @Test fun tamperedCiphertextPausesAccountWithoutPlaintextFallback() {
        val account = store(Kind.SSO)
        account.save("student", "fixture-password")
        account.markVerified()
        val ciphertext = Base64.decode(preferences(Kind.SSO).getString("encrypted", null), Base64.NO_WRAP)
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        preferences(Kind.SSO).edit()
            .putString("encrypted", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit()
        assertNull(account.read())
        assertTrue(account.paused)
        assertFalse(account.verified)
        assertTrue(account.configured)
        assertEquals("", account.savedUsername())
    }

    @Test fun missingOrInvalidatedKeyPausesInsteadOfRepeatedlyReadingCredential() {
        var currentKey = fixtureKey
        val account = WebVpnCredentialStore(app, Kind.SSO, { currentKey })
        account.save("student", "fixture-password")
        currentKey = SecretKeySpec(ByteArray(32) { 99 }, "AES")
        assertNull(account.read())
        assertTrue(account.paused)
        // Returning the old key does not silently re-enable a previously failed account.
        currentKey = fixtureKey
        assertNull(account.read())
        account.resume()
        assertEquals("student", account.read()?.username)
    }

    @Test fun clearRemovesOnlySelectedAccountAndItsRevision() {
        var removedKeys = 0
        val sso = WebVpnCredentialStore(app, Kind.SSO, { fixtureKey }, { removedKeys++ })
        val library = store(Kind.LIBRARY)
        sso.save("student", "sso-fixture-password")
        library.save("reader", "library-fixture-password")
        val libraryRevision = library.revision
        sso.clear()
        assertEquals(1, removedKeys)
        assertFalse(sso.configured)
        assertFalse(sso.enabled)
        assertFalse(sso.verified)
        assertFalse(sso.paused)
        assertEquals("", sso.revision)
        assertNull(sso.read())
        assertEquals("reader", library.read()?.username)
        assertEquals(libraryRevision, library.revision)
    }

    @Test fun validationRejectsInvalidValuesAndPreservesPasswordWhitespace() {
        val account = store(Kind.SSO)
        assertNotNull(WebVpnCredentialStore.inputError("", "password"))
        assertNotNull(WebVpnCredentialStore.inputError("student", ""))
        assertNotNull(WebVpnCredentialStore.inputError("student\n", "password"))
        assertNotNull(WebVpnCredentialStore.inputError("student", "password\r"))
        assertNotNull(WebVpnCredentialStore.inputError("student", "password\u0000"))
        assertNotNull(WebVpnCredentialStore.inputError("a".repeat(257), "password"))
        assertNotNull(WebVpnCredentialStore.inputError("student", "a".repeat(4097)))
        account.save(" student ", " fixture-password ")
        assertEquals("student", account.read()?.username)
        assertEquals(" fixture-password ", account.read()?.password)
    }
}
