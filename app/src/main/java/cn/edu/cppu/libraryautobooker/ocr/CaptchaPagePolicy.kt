package cn.edu.cppu.libraryautobooker.ocr

import java.net.URI

/** Exact resources verified from the school's public login page; never arbitrary school subdomains. */
internal object CaptchaPagePolicy {
    const val SSO_PROXY_PREFIX = "/https/77726476706e69737468656265737421e3e44ed224207845300d8db9d6562d"
    const val MIN_CONFIDENCE = 0.70

    fun isTrusted(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return@runCatching false)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return@runCatching false
        when (uri.host) {
            "sso.cppu.edu.cn" -> uri.rawPath == "/tpass/login"
            "webvpn.cppu.edu.cn" -> uri.rawPath == "$SSO_PROXY_PREFIX/tpass/login"
            else -> false
        }
    }.getOrDefault(false)

    fun accepts(text: String, unrestricted: String, confidence: Double): Boolean =
        text.length == 4 && text.all { it in '0'..'9' } && text == unrestricted &&
            confidence.isFinite() && confidence in MIN_CONFIDENCE..1.0
}

/** Limits local image inference, not server requests. Reset only on a new page or explicit user retry. */
internal class CaptchaAttemptBudget(startedAt: Long) {
    enum class Decision { START, ALREADY_TRIED, LIMIT, EXPIRED }
    private val deadline = startedAt + WINDOW_MILLIS
    private val images = mutableSetOf<String>()

    fun expired(now: Long) = now >= deadline

    fun begin(imageKey: String, now: Long): Decision = when {
        expired(now) -> Decision.EXPIRED
        imageKey in images -> Decision.ALREADY_TRIED
        images.size >= MAX_IMAGES -> Decision.LIMIT
        else -> { images.add(imageKey); Decision.START }
    }

    companion object {
        const val MAX_IMAGES = 3
        const val WINDOW_MILLIS = 180_000L
    }
}
