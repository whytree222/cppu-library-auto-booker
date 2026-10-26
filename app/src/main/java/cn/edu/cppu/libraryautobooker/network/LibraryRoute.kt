package cn.edu.cppu.libraryautobooker.network

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two supported ways to reach the library: trusted campus network, or the
 * school's WebVPN reverse proxy. WebVPN host obfuscation uses the same public
 * AES-CFB scheme as the open-source webvpn-converter project.
 */
enum class LibraryRoute(val label: String) {
    DIRECT("校园网直连"),
    WEBVPN("学校 WebVPN");

    val origin get() = if (this == DIRECT) "http://mlib.cppu.edu.cn" else "https://webvpn.cppu.edu.cn"
    val prefix get() = if (this == DIRECT) "" else "/http/${encryptHost("mlib.cppu.edu.cn")}"
    fun url(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\'))
        return origin + prefix + path
    }
    fun pathOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = if (this == DIRECT) "http" else "https"
        val port = if (this == DIRECT) 80 else 443
        if (uri.scheme != scheme || uri.host != URI(origin).host || uri.userInfo != null ||
            uri.port !in listOf(-1, port)) return@runCatching null
        val path = uri.rawPath.orEmpty()
        if (!path.startsWith("$prefix/")) return@runCatching null
        path.removePrefix(prefix)
    }.getOrNull()

    companion object {
        // Public URL-obfuscation constants from webvpn-converter, not account secrets.
        private const val VENDOR_KEY = "wrdvpnisthebest!"

        fun encryptHost(host: String): String {
            require(host.matches(Regex("[a-z0-9.-]+")))
            val key = VENDOR_KEY.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CFB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            val plain = host.padEnd(host.length + 16 - host.length % 16, '0').toByteArray(Charsets.UTF_8)
            val encrypted = cipher.doFinal(plain).take(host.length).toByteArray()
            return (key + encrypted).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

/** Authenticated WebVPN pages reachable only for manual login; no automation runs here. */
object WebVpnPages {
    const val LIBRARY_HOST = "mlib.cppu.edu.cn"
    const val SSO_ORIGIN = "https://sso.cppu.edu.cn"
    const val SSO_PATH = "/tpass/login"
    const val SSO_PROXY_PREFIX =
        "/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d"

    fun isSso(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.userInfo != null) return@runCatching false
        uri.scheme == "https" && uri.port in listOf(-1, 443) && when (uri.host) {
            "sso.cppu.edu.cn" -> uri.rawPath == SSO_PATH
            "webvpn.cppu.edu.cn" -> uri.rawPath == "$SSO_PROXY_PREFIX$SSO_PATH"
            else -> false
        }
    }.getOrDefault(false)

    fun allows(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.userInfo != null || uri.fragment != null) return@runCatching false
        when (uri.host) {
            "sso.cppu.edu.cn" -> isSso(url)
            "webvpn.cppu.edu.cn" -> uri.scheme == "https" && uri.port in listOf(-1, 443) && when {
                uri.rawPath == "/" || uri.rawPath == "/login" -> true
                uri.rawPath == "$SSO_PROXY_PREFIX$SSO_PATH" -> true
                else -> LibraryRoute.WEBVPN.pathOf(url) != null
            }
            else -> false
        }
    }.getOrDefault(false)
}
