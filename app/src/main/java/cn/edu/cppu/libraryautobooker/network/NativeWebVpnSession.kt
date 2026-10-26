package cn.edu.cppu.libraryautobooker.network

import cn.edu.cppu.libraryautobooker.SessionChecker
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

internal class NativeVpnFailure(val explanation: String, val mayHaveSubmitted: Boolean = false) :
    Exception(explanation)

internal class NativeVpnForm(val kind: Kind, internal val action: String,
    internal val hidden: Map<String, String>, internal val cookieBinding: String,
    internal val createdAt: Long, internal val exponent: String = "", internal val modulus: String = "",
    internal val submitValue: String = "", internal val rememberValue: String = "true") {
    enum class Kind { SSO, LIBRARY }
    internal val consumed = AtomicBoolean()
    override fun toString() = "NativeVpnForm(kind=$kind, session=<redacted>)"
}

internal class NativeVpnStep(val form: NativeVpnForm? = null, val image: ByteArray? = null,
    val result: SessionChecker.Result, val error: Boolean = false) {
    override fun toString() = "NativeVpnStep(state=${result.state}, content=<redacted>)"
}

/** Native GET/form-POST login. No WebView, JavaScript execution, OCR or simulated tap. */
internal class NativeWebVpnSession(
    private val transport: NativeVpnTransport = NativeVpnHttp(),
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime
) : AutoCloseable {
    private var active: NativeVpnForm? = null
    @Volatile private var closed = false
    private fun requireOpen() { check(!closed) }

    fun load(): NativeVpnStep {
        requireOpen(); active = null
        var page = transport.request(NativeVpnUrls.libraryLogin)
        // Some proxy versions answer an expired session with their own JSON envelope.
        if (page.contentType.contains("json", true))
            page = transport.request("https://webvpn.cppu.edu.cn/login")
        if (isSso(page)) return ssoStep(page)
        if (isLibraryLogin(page)) return libraryStep(page)
        return verifiedOrFail()
    }

    fun check(): NativeVpnStep { requireOpen(); return verifiedOrFail() }

    fun submit(form: NativeVpnForm, username: String, password: String, captcha: String,
        remember: Boolean): NativeVpnStep {
        requireOpen()
        if (form !== active || form.consumed.get()) throw NativeVpnFailure("本轮登录已提交或页面已更新，请先检查登录状态或重新获取验证码")
        if (clock() - form.createdAt !in 0..120_000L || transport.cookieBinding(form.action) != form.cookieBinding) {
            active = null
            throw NativeVpnFailure("验证码或登录会话已变化，请重新获取登录表单")
        }
        if (username.isBlank() || password.isEmpty() || username.length > 256 || password.length > 4096 ||
            (username + password).any { it == '\r' || it == '\n' || it == '\u0000' })
            throw NativeVpnFailure("请填写有效的账号和密码")
        if (form.kind == NativeVpnForm.Kind.SSO && !captcha.matches(Regex("[0-9]{4}")))
            throw NativeVpnFailure("请手动填写图片中的四位数字验证码")
        val fields = LinkedHashMap(form.hidden)
        if (form.kind == NativeVpnForm.Kind.SSO) {
            fields["username"] = username.trim()
            fields["password"] = SchoolPasswordEncoding.sso(password, form.exponent, form.modulus)
            fields["authcode"] = captcha
            fields["submit"] = form.submitValue
            if (remember) fields["rememberMe"] = form.rememberValue
        } else {
            fields["user"] = username.trim()
            fields["passwd"] = SchoolPasswordEncoding.library(password)
            fields["url"] = "selectreadingroom"
        }
        if (!form.consumed.compareAndSet(false, true)) throw NativeVpnFailure("本轮登录已提交，请检查登录状态")
        active = null
        val body = fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }.toByteArray(Charsets.UTF_8)
        try {
            val response = transport.request(form.action, "POST", body, referer = form.action)
            if (isSso(response)) {
                val doc = document(response)
                val feedback = doc.select("#fm1 #msg1, #fm1 #swiSpan1, #msg1, #swiSpan1").text()
                if (Regex("验证码.*(无效|错误|不正确|失效|过期)").containsMatchIn(feedback))
                    return ssoStep(response, "验证码未通过，请输入新的验证码后再点登录", true)
                if (Regex("密码|账号|帐号|用户名|锁定|频繁|拒绝|失败").containsMatchIn(feedback))
                    return ssoStep(response, "学校未接受本次认证，请核对账号密码或稍后重试", true)
            }
            val result = probe()
            if (result.state == "valid") return NativeVpnStep(result = result)
            if (form.kind == NativeVpnForm.Kind.SSO && result.vpnAuthState == "valid") {
                val page = if (isLibraryLogin(response)) response else transport.request(NativeVpnUrls.libraryLogin)
                if (isLibraryLogin(page)) return libraryStep(page)
            }
            if (form.kind == NativeVpnForm.Kind.LIBRARY && result.state == "expired" && result.vpnAuthState == "valid" && isLibraryLogin(response))
                return libraryStep(response, "图书馆未接受本次登录，请核对图书馆账号密码", true)
            throw NativeVpnFailure("登录已提交，但学校结果尚未确认。请点“检查登录状态”；不会自动重复提交", true)
        } catch (error: NativeVpnFailure) { throw error }
        catch (_: Exception) { throw NativeVpnFailure("登录提交后连接中断或结果不明，请先检查登录状态；不会自动重发", true) }
        finally { body.fill(0); fields.clear() }
    }

    private fun probe(): SessionChecker.Result {
        val response = transport.request(LibraryRoute.WEBVPN.url("/isLogin"), "POST", byteArrayOf(), follow = false)
        return SessionChecker.decodeRoute(LibraryRoute.WEBVPN, response.code, response.location, response.body.toString(Charsets.UTF_8))
    }

    private fun verifiedOrFail(): NativeVpnStep {
        val result = probe()
        if (result.state == "valid") return NativeVpnStep(result = result)
        if (result.state == "expired" && result.vpnAuthState == "valid") {
            val page = transport.request(NativeVpnUrls.libraryLogin)
            if (isLibraryLogin(page)) return libraryStep(page)
        }
        throw NativeVpnFailure(if (result.state == "vpn_login_required") "统一认证尚未完成，请重新获取验证码并在应用内登录"
            else "暂时无法验证学校登录状态，请检查网络后重试")
    }

    private fun ssoStep(page: NativeVpnResponse, message: String = "请在应用内输入统一认证账号、密码和验证码", error: Boolean = false): NativeVpnStep {
        val doc = document(page)
        val forms = doc.select("form#fm1")
        require(forms.size == 1)
        val form = forms.single()
        require(form.attr("method").equals("post", true) && form.attr("target").lowercase() in setOf("", "_self"))
        val action = NativeVpnUrls.resolve(page.url, form.attr("action"))
        require(WebVpnPages.isSso(action))
        val service = URI(action).rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "service" }
        require(service.size == 1 && java.net.URLDecoder.decode(service.single().substringAfter('='), "UTF-8") ==
            "https://webvpn.cppu.edu.cn/login?cas_login=true")
        for ((id, name) in listOf("username" to "username", "password" to "password", "authcode" to "authcode")) {
            val inputs = form.select("input#$id")
            require(inputs.size == 1 && inputs.single().attr("name") == name &&
                !inputs.single().hasAttr("disabled") && !inputs.single().hasAttr("readonly"))
            require(inputs.single().attr("type").lowercase() in
                if (id == "password") setOf("password") else setOf("", "text", "tel", "number"))
        }
        val hidden = hidden(form)
        require(hidden.keys == setOf("execution", "encrypted", "_eventId", "loginType") &&
            hidden["execution"].orEmpty().isNotBlank() && hidden["encrypted"] == "true" &&
            hidden["_eventId"] == "submit" && hidden["loginType"] == "1")
        val scripts = doc.select("script[src]").map { NativeVpnUrls.run { runCatching { resolve(page.url, it.attr("src")) }.getOrNull() } }
        require(scripts.any { it?.substringBefore('?') == NativeVpnUrls.loginScript })
        val code = transport.request(NativeVpnUrls.loginScript).body.toString(Charsets.UTF_8)
        val key = Regex("RSAUtils\\.getKeyPair\\(\\s*[\"']([0-9a-fA-F]+)[\"']\\s*,\\s*[\"'][\"']\\s*,\\s*[\"']([0-9a-fA-F]+)[\"']\\s*\\)").findAll(code).toList()
        require(key.size == 1 && code.contains("RSAUtils.encryptedString(key, thisPwd)"))
        val exponent = key.single().groupValues[1]
        val modulus = key.single().groupValues[2]
        require(exponent.equals("010001", true) && BigInteger(modulus, 16).bitLength() in 1024..4096)
        val images = form.select("img.codeImg")
        require(images.size == 1)
        val imageUrl = NativeVpnUrls.resolve(page.url, images.single().attr("src"))
        require(imageUrl.substringBefore('?') == NativeVpnUrls.captcha)
        val image = transport.request(imageUrl, referer = page.url)
        require(image.code == 200 && image.contentType.startsWith("image/", true) && image.body.size in 1..262_144)
        val buttons = form.select("#passbutton[name=submit]")
        require(buttons.size == 1)
        val remember = form.select("input[name=rememberMe]")
        require(remember.size == 1)
        active = NativeVpnForm(NativeVpnForm.Kind.SSO, action, hidden, transport.cookieBinding(action), clock(),
            exponent, modulus, buttons.single().attr("value"), remember.single().attr("value").ifBlank { "on" })
        return NativeVpnStep(active, image.body, SessionChecker.Result("vpn_login_required", message, vpnAuthState = "expired"), error)
    }

    private fun libraryStep(page: NativeVpnResponse, message: String = "统一认证已通过，请在应用内登录图书馆", error: Boolean = false): NativeVpnStep {
        val forms = document(page).select("form#fromuser")
        require(forms.size == 1)
        val form = forms.single()
        require(form.attr("method").equals("post", true) && form.attr("target").lowercase() in setOf("", "_self"))
        val raw = form.attr("action")
        val action = if (raw in setOf("login", "/login")) NativeVpnUrls.libraryLogin else NativeVpnUrls.resolve(page.url, raw)
        require(action == NativeVpnUrls.libraryLogin)
        for ((id, name) in listOf("user" to "user", "passwd" to "passwd", "url" to "url")) {
            val inputs = form.select("input#$id[name=$name]")
            require(inputs.size == 1 && !inputs.single().hasAttr("disabled") && !inputs.single().hasAttr("readonly"))
        }
        require(form.select("iframe, input[name*=captcha], input[name*=authcode]").isEmpty())
        val hidden = hidden(form)
        require(hidden.keys == setOf("url"))
        active = NativeVpnForm(NativeVpnForm.Kind.LIBRARY, action, hidden, transport.cookieBinding(action), clock())
        return NativeVpnStep(active, result = SessionChecker.Result("expired", message, vpnAuthState = "valid"), error = error)
    }

    private fun hidden(form: Element): Map<String, String> {
        val nodes = form.select("input[type=hidden][name]")
        require(nodes.size <= 12 && nodes.map { it.attr("name") }.distinct().size == nodes.size)
        return nodes.associate { it.attr("name") to it.attr("value").also { value -> require(value.length <= 8192) } }
    }
    private fun isSso(page: NativeVpnResponse) = page.code == 200 && WebVpnPages.isSso(page.url) && document(page).select("form#fm1").isNotEmpty()
    private fun isLibraryLogin(page: NativeVpnResponse) = page.code == 200 && LibraryRoute.WEBVPN.pathOf(page.url) == "/login" && document(page).select("form#fromuser").isNotEmpty()
    private fun document(page: NativeVpnResponse): Document {
        require(page.body.size <= 1_048_576)
        val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(page.contentType)?.groupValues?.get(1)?.trim('"', '\'')
        return Jsoup.parse(ByteArrayInputStream(page.body), charset, page.url)
    }
    override fun close() { closed = true; active = null; transport.close() }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** Exact byte/word encoding used by the school's public security.js and Base64 helper. */
internal object SchoolPasswordEncoding {
    fun sso(password: String, exponent: String, modulus: String): String {
        val n = BigInteger(modulus, 16)
        val chunkSize = 2 * ((n.bitLength() - 1) / 16)
        require(chunkSize > 0)
        return password.chunked(chunkSize).joinToString(" ") { block ->
            var number = BigInteger.ZERO
            val words = LongArray(chunkSize / 2)
            for (index in block.indices step 2) {
                val word = block[index].code + ((block.getOrNull(index + 1)?.code ?: 0) shl 8)
                words[index / 2] = word.toLong()
                number = number.add(BigInteger.valueOf(word.toLong()).shiftLeft((index / 2) * 16))
            }
            val e = BigInteger(exponent, 16)
            // The school uses UTF-16 charCodeAt values, even above 255, and its
            // first square truncates the carry through JS's unsigned 32-bit >>>.
            // Preserve that public-script behavior instead of sending a different
            // ciphertext for non-ASCII passwords. Later digits are canonical.
            val encrypted = if (words.any { it > 65535 }) {
                require(e == BigInteger.valueOf(65537))
                val high = maxOf(0, words.indexOfLast { it != 0L })
                val squared = LongArray(words.size * 2 + 1)
                for (i in 0..high) {
                    var carry = 0L
                    for (j in 0..high) {
                        val product = squared[i + j] + words[j] * words[i] + carry
                        squared[i + j] = product and 65535L
                        carry = (product and 0xffffffffL) ushr 16
                    }
                    squared[i + high + 1] = carry
                }
                var square = BigInteger.ZERO
                squared.forEachIndexed { index, word -> square = square.add(BigInteger.valueOf(word).shiftLeft(index * 16)) }
                number.mod(n).multiply(square.modPow(BigInteger.valueOf(32768), n)).mod(n)
            } else number.modPow(e, n)
            val hex = encrypted.toString(16)
            hex.padStart(((hex.length + 3) / 4) * 4, '0')
        }
    }
    fun library(password: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (char in password) when {
            char.code < 128 -> bytes.write(char.code)
            char.code < 2048 -> { bytes.write((char.code shr 6) or 192); bytes.write((char.code and 63) or 128) }
            else -> { bytes.write((char.code shr 12) or 224); bytes.write(((char.code shr 6) and 63) or 128); bytes.write((char.code and 63) or 128) }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
}
