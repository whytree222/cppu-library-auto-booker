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

class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("auto_login", Context.MODE_PRIVATE)
    data class Credentials(val username: String, val password: String)
    val configured get() = prefs.contains("encrypted")
    val enabled get() = prefs.getBoolean("enabled", false) && configured
    val paused get() = prefs.getBoolean("paused", false)
    val preferredAutoLogin get() = prefs.getBoolean("preferred_enabled", if (configured) enabled else true)

    fun save(username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty())
        require(!username.contains('\n') && !username.contains('\r') && !password.contains('\n') && !password.contains('\r'))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSONObject().put("username", username.trim()).put("password", password).toString().toByteArray(Charsets.UTF_8)
        try {
            val encoded = Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
            check(prefs.edit().putString("encrypted", encoded).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putBoolean("enabled", true).putBoolean("preferred_enabled", true).putBoolean("paused", false).commit())
        } finally { plain.fill(0) }
    }

    fun read(): Credentials? {
        if (!enabled) return null
        return decrypt()
    }

    // Only the account name is prefilled; never put a password in saved UI state.
    fun savedUsername(): String = runCatching { decrypt()?.username.orEmpty() }.getOrDefault("")

    private fun decrypt(): Credentials? {
        if (!configured) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        val plain = cipher.doFinal(Base64.decode(prefs.getString("encrypted", ""), Base64.NO_WRAP))
        return try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            Credentials(json.getString("username"), json.getString("password"))
        } finally { plain.fill(0) }
    }

    fun pause() { prefs.edit().putBoolean("paused", true).apply() }
    fun resume() { prefs.edit().putBoolean("paused", false).apply() }
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).putBoolean("preferred_enabled", value).apply() }
    fun setPreference(value: Boolean) { prefs.edit().putBoolean("preferred_enabled", value).apply() }
    fun clear() {
        check(prefs.edit().clear().commit())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object { private const val ALIAS = "library_auto_login_v1" }
}
