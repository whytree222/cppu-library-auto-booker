package cn.edu.cppu.libraryautobooker.network

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Local conversion based on WilliamPeterMatthew/webvpn-converter (MIT). */
enum class LibraryRoute(val label: String) {
    DIRECT("校园网直连"), WEBVPN("学校 WebVPN");

    val origin get() = if (this == DIRECT) "http://mlib.cppu.edu.cn" else "https://webvpn.cppu.edu.cn"
    val prefix get() = if (this == DIRECT) "" else "/http/${encryptHost("mlib.cppu.edu.cn") }"
    fun url(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\'))
        return origin + prefix + path
    }
    fun pathOf(url: String): String? = runCatching {
        val uri = URI(url)
        val base = URI(origin)
        if (uri.scheme != base.scheme || uri.host != base.host || uri.userInfo != null ||
            uri.port !in listOf(-1, if (this == DIRECT) 80 else 443)) return@runCatching null
        val path = uri.rawPath.orEmpty()
        if (!path.startsWith("$prefix/")) return@runCatching null
        path.removePrefix(prefix)
    }.getOrNull()

    companion object {
        // Public vendor URL-obfuscation constants, NOT account passwords or secret tokens.
        fun encryptHost(host: String): String {
            require(host.matches(Regex("[a-z0-9.-]+")))
            val bytes = "wrdvpnisthebest!".toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CFB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(bytes, "AES"), IvParameterSpec(bytes))
            val plain = host.padEnd(host.length + 16 - host.length % 16, '0').toByteArray(Charsets.UTF_8)
            val encrypted = cipher.doFinal(plain).take(host.length).toByteArray()
            return (bytes + encrypted).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

