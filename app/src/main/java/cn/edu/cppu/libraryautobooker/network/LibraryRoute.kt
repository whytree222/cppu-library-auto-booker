package cn.edu.cppu.libraryautobooker.network

import java.net.URI

/** Only the campus library origin is supported. No proxy conversion or fallback. */
enum class LibraryRoute(val label: String) {
    DIRECT("校园网直连");

    val origin get() = "http://mlib.cppu.edu.cn"
    val prefix get() = ""
    fun url(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\'))
        return origin + path
    }
    fun pathOf(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "http" || uri.host != "mlib.cppu.edu.cn" || uri.userInfo != null ||
            uri.port !in listOf(-1, 80)) return@runCatching null
        uri.rawPath?.takeIf { it.startsWith('/') }
    }.getOrNull()
}
