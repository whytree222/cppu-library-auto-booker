package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

/** Separate opt-in accounts for school SSO and the library reached through WebVPN. */
class WebVpnCredentialStore internal constructor(
    context: Context,
    val kind: Kind,
    private val keyProvider: () -> SecretKey,
    private val keyRemover: () -> Unit = {}
) {
    constructor(context: Context, kind: Kind) : this(context, kind,
        { keyFor(kind) }, { deleteKey(kind) })

    enum class Kind(internal val storageName: String, internal val keyAlias: String) {
        SSO("webvpn_sso_credentials_v1", "webvpn_sso_login_v1"),
        LIBRARY("webvpn_library_credentials_v1", "webvpn_library_login_v1")
    }

    data class Credentials(val username: String, val password: String) {
        // Prevent accidental credential disclosure from debugging a result or collection.
        override fun toString(): String = "Credentials(username=<redacted>, password=<redacted>)"
    }

    private val preferences = context.getSharedPreferences(kind.storageName, Context.MODE_PRIVATE)
    private val associatedData = "cppu-webvpn:${kind.name}:v1".toByteArray(Charsets.UTF_8)
    // Activity, recovery and status checks use different store instances. One
    // monitor per account kind keeps revision checks and writes atomic across all.
    private val lock get() = credentialLocks.getValue(kind)
    val configured get() = synchronized(lock) { preferences.contains("encrypted") && preferences.contains("iv") }
    val enabled get() = synchronized(lock) { configured && preferences.getBoolean("enabled", false) }
    val paused get() = synchronized(lock) { preferences.getBoolean("paused", false) }
    val verified get() = synchronized(lock) { configured && preferences.getBoolean("verified", false) }
    val revision get() = synchronized(lock) { preferences.getString("revision", "").orEmpty() }

    /** Explicit user consent saves locally, but only a real server response may mark it verified. */
    fun save(username: String, password: String): Unit = synchronized(lock) {
        require(inputError(username, password) == null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(associatedData)
        val plain = JSONObject().put("username", username.trim()).put("password", password)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val encrypted = Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
            check(preferences.edit().putString("encrypted", encrypted)
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putBoolean("enabled", true).putBoolean("paused", false)
                .putBoolean("verified", false).putString("revision", UUID.randomUUID().toString()).commit())
        } finally {
            plain.fill(0)
        }
    }

    fun read(): Credentials? = synchronized(lock) {
        if (!enabled || paused) return@synchronized null
        try {
            decrypt()
        } catch (_: Exception) {
            // A missing/invalidated key must never result in a plaintext fallback or a retry loop.
            pause()
            null
        }
    }

    /** Only the account name is prefilled. The password never enters saved UI state. */
    fun savedUsername(): String = synchronized(lock) { runCatching { decrypt()?.username.orEmpty() }.getOrDefault("") }

    private fun decrypt(): Credentials? {
        if (!configured) return null
        val iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        val plain = cipher.doFinal(Base64.decode(preferences.getString("encrypted", ""), Base64.NO_WRAP))
        return try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            val username = json.getString("username")
            val password = json.getString("password")
            require(inputError(username, password) == null)
            Credentials(username, password)
        } finally {
            plain.fill(0)
        }
    }

    fun markVerified(): Unit = synchronized(lock) {
        if (configured) preferences.edit().putBoolean("verified", true).apply()
    }

    /** A late response for old credentials cannot verify a newer explicit save. */
    fun markVerifiedIfRevision(expected: String): Boolean = synchronized(lock) {
        if (!configured || !enabled || paused || revision != expected) return@synchronized false
        preferences.edit().putBoolean("verified", true).apply()
        true
    }

    /** Unknown or rejected old submissions must not pause a newly saved account. */
    fun pauseIfRevision(expected: String): Boolean = synchronized(lock) {
        if (!configured || !enabled || revision != expected) return@synchronized false
        pause()
        true
    }

    fun pause(): Unit = synchronized(lock) {
        preferences.edit().putBoolean("paused", true).putBoolean("verified", false).apply()
    }

    fun resume(): Unit = synchronized(lock) {
        preferences.edit().putBoolean("paused", false).apply()
    }

    fun setEnabled(value: Boolean): Unit = synchronized(lock) {
        preferences.edit().putBoolean("enabled", value).apply()
    }

    fun clear(): Unit = synchronized(lock) {
        check(preferences.edit().clear().commit())
        // If the key was already invalidated/deleted, local deletion is still successful.
        runCatching { keyRemover() }
        Unit
    }

    companion object {
        private val credentialLocks = Kind.entries.associateWith { Any() }
        fun inputError(username: String, password: String): String? = when {
            username.isBlank() -> "请填写账号"
            password.isEmpty() -> "请填写密码"
            username.length > 256 || password.length > 4096 -> "账号或密码长度超出范围"
            username.any { it == '\n' || it == '\r' || it == '\u0000' } ||
                password.any { it == '\n' || it == '\r' || it == '\u0000' } -> "账号和密码不能包含换行或空字符"
            else -> null
        }

        private fun keyFor(kind: Kind): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(kind.keyAlias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(kind.keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }

        private fun deleteKey(kind: Kind) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(kind.keyAlias)) store.deleteEntry(kind.keyAlias)
        }
    }
}
