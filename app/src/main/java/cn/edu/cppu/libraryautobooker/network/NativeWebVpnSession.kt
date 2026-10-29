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

internal enum class NativeVpnFailureKind {
    NONE, CAPTCHA_REJECTED, CREDENTIAL_REJECTED, RATE_LIMITED, EXTRA_VERIFICATION,
    UNKNOWN_RESULT, CANCELLED, FORM_STALE, INVALID_INPUT, PRE_SUBMISSION_FAILURE
}

/** Only stable categories leave parsing; raw school feedback never enters logs. */
internal object NativeVpnFeedback {
    fun classify(message: String): NativeVpnFailureKind = when {
        Regex("锁定|冻结|频繁|次数.*(?:上限|过多|限制|超)|过多.*尝试|请求过多|稍后(?:再|重)试|封禁|限流").containsMatchIn(message) -> NativeVpnFailureKind.RATE_LIMITED
        Regex("短信|动态口令|二次认证|额外验证|额外认证|双重认证|一次性密码|扫码|人脸|多因素|滑块").containsMatchIn(message) -> NativeVpnFailureKind.EXTRA_VERIFICATION
        Regex("(?:密码|账号|帐号|用户名|用户).*(?:错误|不正确|无效|不存在|不匹配|未找到|失败)|请输入.*(?:密码|账号|帐号|用户名)").containsMatchIn(message) -> NativeVpnFailureKind.CREDENTIAL_REJECTED
        Regex("验证码.*(?:无效|错误|不正确|失效|过期|失败)").containsMatchIn(message) -> NativeVpnFailureKind.CAPTCHA_REJECTED
        else -> NativeVpnFailureKind.UNKNOWN_RESULT
    }
}

internal class NativeVpnFailure(val explanation: String, val mayHaveSubmitted: Boolean = false,
    val kind: NativeVpnFailureKind = if (mayHaveSubmitted) NativeVpnFailureKind.UNKNOWN_RESULT else NativeVpnFailureKind.PRE_SUBMISSION_FAILURE) :
    Exception(explanation) {
    companion object {
        // Only fixed labels and error categories reach the UI. Never include the
        // exception message, URL, response body, cookie or submitted fields.
        fun from(error: Throwable, phase: String, submitted: Boolean = false): NativeVpnFailure {
            if (error is NativeVpnFailure) return error
            val reason = when (error) {
                is NativeVpnTransportFailure -> error.reason.detail
                is java.net.UnknownHostException -> "无法解析学校服务器地址"
                is java.net.SocketTimeoutException -> "学校连接或响应超时"
                is javax.net.ssl.SSLException -> "无法建立学校 HTTPS 安全连接"
                is java.net.ConnectException, is java.net.NoRouteToHostException -> "无法连接学校服务器"
                is java.io.IOException -> "读取学校响应时连接异常"
                is IllegalArgumentException -> "学校页面字段、返回内容或跳转目标与适配规则不符"
                is IllegalStateException -> "登录会话或本机组件未就绪"
                is LinkageError -> "本机登录运行组件不可用"
                else -> "处理学校登录响应失败"
            }
            val prefix = if (submitted) "登录已提交，但结果未确认；请先检查登录状态，不会自动重发。" else ""
            return NativeVpnFailure("$prefix$phase：$reason", submitted,
                if (error is NativeVpnTransportFailure && error.reason == NativeVpnTransportFailure.Reason.CANCELLED)
                    NativeVpnFailureKind.CANCELLED else if (submitted) NativeVpnFailureKind.UNKNOWN_RESULT
                else NativeVpnFailureKind.PRE_SUBMISSION_FAILURE)
        }
    }
}

internal class NativeVpnForm(val kind: Kind, internal val action: String,
    internal val hidden: Map<String, String>, internal val cookieBinding: String,
    internal val createdAt: Long, internal val exponent: String = "", internal val modulus: String = "",
    internal val submitValue: String = "", internal val rememberValue: String = "true") {
    enum class Kind { SSO, LIBRARY }
    internal val consumed = AtomicBoolean()
    override fun toString() = "NativeVpnForm(kind=$kind, session=<redacted>)"
}

internal class NativeVpnStep(val form: NativeVpnForm? = null, val image: ByteArray? = null,
    val result: SessionChecker.Result, val error: Boolean = false,
    val failureKind: NativeVpnFailureKind = NativeVpnFailureKind.NONE) {
    override fun toString() = "NativeVpnStep(state=${result.state}, content=<redacted>)"
}

/** Native GET/form-POST login. No WebView, JavaScript execution, OCR or simulated tap. */
internal class NativeWebVpnSession(
    private val transport: NativeVpnTransport = NativeVpnHttp(),
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime
) : AutoCloseable {
    private var active: NativeVpnForm? = null
    private var activeImage: ByteArray? = null
    @Volatile private var phase = "N00 登录初始化"
    @Volatile private var credentialAttempts = 0
    @Volatile private var probeSummary = "尚未检查"
    fun diagnostics(): String = (listOf("当前阶段：$phase", "登录提交尝试：$credentialAttempts", "最近会话检查：$probeSummary") +
        transport.diagnostics()).joinToString("\n")
    @Volatile private var closed = false
    private val lifecycle = Any()
    private fun requireOpen() { check(!closed) }

    /** Current, unconsumed challenge only. Does not send or consume anything. */
    fun validate(form: NativeVpnForm, guard: () -> Boolean = { true }): Boolean = synchronized(lifecycle) {
        runCatching { !closed && guard() && form === active && !form.consumed.get() &&
            clock() - form.createdAt in 0..120_000L && transport.cookieBinding(form.action) == form.cookieBinding }.getOrDefault(false)
    }

    private fun requireValid(form: NativeVpnForm, guard: () -> Boolean) {
        if (closed || !guard()) throw NativeVpnFailure("登录操作已取消或账号设置已变化，未继续发送", kind = NativeVpnFailureKind.CANCELLED)
        if (!validate(form)) throw NativeVpnFailure("验证码或登录会话已变化，请重新获取登录表单", kind = NativeVpnFailureKind.FORM_STALE)
    }

    fun load(): NativeVpnStep {
        try {
            synchronized(lifecycle) { requireOpen(); active = null; activeImage = null }
            phase = "L01 读取图书馆登录入口"
            var page = transport.request(NativeVpnUrls.libraryLogin)
            var rootAlreadyLoaded = false
            // Some proxy versions answer an expired session with their own JSON envelope.
            if (page.contentType.contains("json", true)) {
                phase = "L02 读取统一认证入口"
                page = transport.request("https://webvpn.cppu.edu.cn/login")
                rootAlreadyLoaded = true
            }
            if (isSso(page)) return ssoStep(page)
            if (isLibraryLogin(page)) return libraryStep(page)
            return verifiedOrFail(recoverSso = true, rootAlreadyLoaded = rootAlreadyLoaded)
        } catch (error: Exception) { synchronized(lifecycle) { active = null; activeImage = null }; throw NativeVpnFailure.from(error, phase) }
    }

    fun check(): NativeVpnStep {
        try { requireOpen(); return verifiedOrFail() }
        catch (error: Exception) {
            val failure = NativeVpnFailure.from(error, phase)
            val form = active
            // A read-only check is not a login submission. Keep an untouched
            // challenge only while both its deadline and cookie binding still match.
            val reusable = runCatching { !closed && form != null && form.kind == NativeVpnForm.Kind.SSO &&
                !form.consumed.get() && clock() - form.createdAt in 0..120_000L &&
                transport.cookieBinding(form.action) == form.cookieBinding && activeImage != null }.getOrDefault(false)
            if (reusable) return NativeVpnStep(form, activeImage, SessionChecker.Result("unknown",
                "登录状态未确认；当前验证码尚未提交，可继续填写或换图。${failure.explanation}"), error = true,
                failureKind = NativeVpnFailureKind.PRE_SUBMISSION_FAILURE)
            throw failure
        }
    }

    fun submit(form: NativeVpnForm, username: String, password: String, captcha: String,
        remember: Boolean, guard: () -> Boolean = { true }): NativeVpnStep {
        synchronized(lifecycle) { requireValid(form, guard) }
        if (username.isBlank() || password.isEmpty() || username.length > 256 || password.length > 4096 ||
            (username + password).any { it == '\r' || it == '\n' || it == '\u0000' })
            throw NativeVpnFailure("请填写有效的账号和密码", kind = NativeVpnFailureKind.INVALID_INPUT)
        if (form.kind == NativeVpnForm.Kind.SSO && !captcha.matches(Regex("[0-9]{4}")))
            throw NativeVpnFailure("请填写图片中的四位数字验证码", kind = NativeVpnFailureKind.INVALID_INPUT)
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
        val body = fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }.toByteArray(Charsets.UTF_8)
        try {
            // close() and final consumption share this short state lock. RSA and
            // all network I/O remain outside it; a cancelled OCR cannot submit.
            synchronized(lifecycle) {
                requireValid(form, guard)
                if (!form.consumed.compareAndSet(false, true)) throw NativeVpnFailure("本轮登录已提交，请检查登录状态", kind = NativeVpnFailureKind.FORM_STALE)
                active = null; activeImage = null
            }
            phase = if (form.kind == NativeVpnForm.Kind.SSO) "P01 提交统一认证并读取响应" else "P02 提交图书馆登录并读取响应"
            credentialAttempts++
            val response = transport.guardedRequest(form.action, "POST", body, referer = form.action) { !closed && guard() }
            if (response.code == 429) return rejected(form.kind, NativeVpnFailureKind.RATE_LIMITED)
            if (isSso(response)) {
                val doc = document(response)
                val feedback = doc.select("#fm1 #msg1, #fm1 #swiSpan1, #msg1, #swiSpan1").text()
                when (val reason = NativeVpnFeedback.classify(feedback)) {
                    NativeVpnFailureKind.CAPTCHA_REJECTED -> return ssoStep(response, "验证码未通过，请输入新的验证码后再点登录", true, reason)
                    NativeVpnFailureKind.CREDENTIAL_REJECTED, NativeVpnFailureKind.RATE_LIMITED,
                    NativeVpnFailureKind.EXTRA_VERIFICATION -> return rejected(form.kind, reason)
                    else -> Unit
                }
            }
            val result = probe()
            if (result.state == "valid") return NativeVpnStep(result = result)
            if (form.kind == NativeVpnForm.Kind.SSO && result.vpnAuthState == "valid") {
                val page = if (isLibraryLogin(response)) response else transport.request(NativeVpnUrls.libraryLogin)
                if (isLibraryLogin(page)) return libraryStep(page)
            }
            if (form.kind == NativeVpnForm.Kind.LIBRARY && result.state == "expired" && result.vpnAuthState == "valid" && isLibraryLogin(response)) {
                val reason = NativeVpnFeedback.classify(document(response).select(".alert-danger,.alertify-message,[role=alert],.error-message,#msg1,#msg").text())
                if (reason == NativeVpnFailureKind.RATE_LIMITED || reason == NativeVpnFailureKind.EXTRA_VERIFICATION)
                    return rejected(form.kind, reason)
                if (reason == NativeVpnFailureKind.CAPTCHA_REJECTED)
                    return rejected(form.kind, NativeVpnFailureKind.EXTRA_VERIFICATION)
                return libraryStep(response, "图书馆未接受本次登录，请核对图书馆账号密码", true, NativeVpnFailureKind.CREDENTIAL_REJECTED)
            }
            if (form.kind == NativeVpnForm.Kind.SSO && result.state == "vpn_login_required" && isSso(response))
                return ssoStep(response, "学校仍返回统一认证登录页，尚未登录；请核对输入并填写新验证码后再点登录", true, NativeVpnFailureKind.UNKNOWN_RESULT)
            throw NativeVpnFailure("登录已提交，但学校结果尚未确认。请点“检查登录状态”；不会自动重复提交", true)
        } catch (error: NativeVpnFailure) { throw error }
        catch (error: Exception) { throw NativeVpnFailure.from(error, phase, submitted = true) }
        finally { body.fill(0); fields.clear() }
    }

    private fun rejected(kind: NativeVpnForm.Kind, reason: NativeVpnFailureKind): NativeVpnStep {
        val message = when (reason) {
            NativeVpnFailureKind.CREDENTIAL_REJECTED -> "学校未接受本次认证，请核对账号密码或稍后重试"
            NativeVpnFailureKind.RATE_LIMITED -> "学校限制登录频率或账号已锁定，已停止自动尝试"
            NativeVpnFailureKind.EXTRA_VERIFICATION -> "学校要求额外认证，请手动完成登录"
            else -> "登录结果未确认，已停止自动尝试"
        }
        return NativeVpnStep(result = SessionChecker.Result(if (kind == NativeVpnForm.Kind.SSO) "vpn_login_required" else "expired",
            message, vpnAuthState = if (kind == NativeVpnForm.Kind.SSO) "expired" else "valid"), error = true, failureKind = reason)
    }

    private fun probe(): SessionChecker.Result {
        phase = "V01 核对 WebVPN 与图书馆登录状态"
        val response = transport.request(LibraryRoute.WEBVPN.url("/isLogin"), "POST", byteArrayOf(), follow = false)
        val result = SessionChecker.decodeRoute(LibraryRoute.WEBVPN, response.code, response.location, response.body.toString(Charsets.UTF_8))
        probeSummary = "外层 ${result.vpnAuthState ?: "unknown"}，整体 ${result.state}"
        return result
    }

    private fun verifiedOrFail(recoverSso: Boolean = false, rootAlreadyLoaded: Boolean = false): NativeVpnStep {
        val result = probe()
        if (result.state == "valid") return NativeVpnStep(result = result)
        if (result.state == "expired" && result.vpnAuthState == "valid") {
            val page = transport.request(NativeVpnUrls.libraryLogin)
            if (isLibraryLogin(page)) return libraryStep(page)
        }
        if (recoverSso && result.state == "vpn_login_required") {
            if (!rootAlreadyLoaded) {
                phase = "L02 重新读取学校统一认证入口"
                val page = transport.request("https://webvpn.cppu.edu.cn/login")
                if (isSso(page)) return ssoStep(page)
                if (isLibraryLogin(page)) return libraryStep(page)
            }
            // The portal can be an inert landing page rather than a server-side
            // redirect. Visit the already-adapted CAS entry once, via GET only.
            phase = "L03 获取可填写的统一认证表单"
            val page = transport.request(NativeVpnUrls.ssoLogin)
            if (isSso(page)) return ssoStep(page)
            if (isLibraryLogin(page)) return libraryStep(page)
            // An existing school session may have completed the CAS return.
            // Verify it; this final pass cannot restart the recovery sequence.
            return verifiedOrFail()
        }
        throw NativeVpnFailure(if (result.state == "vpn_login_required") "统一认证尚未完成，请重新获取验证码并在应用内登录"
            else "暂时无法验证学校登录状态，请检查网络后重试")
    }

    private fun ssoStep(page: NativeVpnResponse, message: String = "请在应用内输入统一认证账号、密码和验证码", error: Boolean = false,
        failureKind: NativeVpnFailureKind = NativeVpnFailureKind.NONE): NativeVpnStep {
        phase = "S01 解析统一认证表单"
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
        phase = "S02 核对统一认证输入字段"
        for ((id, name) in listOf("username" to "username", "password" to "password", "authcode" to "authcode")) {
            val inputs = form.select("input#$id")
            require(inputs.size == 1 && inputs.single().attr("name") == name &&
                !inputs.single().hasAttr("disabled") && !inputs.single().hasAttr("readonly"))
            require(inputs.single().attr("type").lowercase() in
                if (id == "password") setOf("password") else setOf("", "text", "tel", "number"))
        }
        val hidden = hidden(form)
        phase = "S03 核对统一认证动态表单"
        require(hidden.keys == setOf("execution", "encrypted", "_eventId", "loginType") &&
            hidden["execution"].orEmpty().isNotBlank() && hidden["encrypted"] == "true" &&
            hidden["_eventId"] == "submit" && hidden["loginType"] == "1")
        val scripts = doc.select("script[src]").map { NativeVpnUrls.run { runCatching { resolve(page.url, it.attr("src")) }.getOrNull() } }
        phase = "S04 读取学校登录加密脚本"
        require(scripts.any { it?.substringBefore('?') == NativeVpnUrls.loginScript })
        val code = transport.request(NativeVpnUrls.loginScript).body.toString(Charsets.UTF_8)
        phase = "S05 核对学校登录加密规则"
        val key = Regex("RSAUtils\\.getKeyPair\\(\\s*[\"']([0-9a-fA-F]+)[\"']\\s*,\\s*[\"'][\"']\\s*,\\s*[\"']([0-9a-fA-F]+)[\"']\\s*\\)").findAll(code).toList()
        require(key.size == 1 && code.contains("RSAUtils.encryptedString(key, thisPwd)"))
        val exponent = key.single().groupValues[1]
        val modulus = key.single().groupValues[2]
        require(exponent.equals("010001", true) && BigInteger(modulus, 16).bitLength() in 1024..4096)
        phase = "S06 核对验证码图片来源"
        val images = form.select("img.codeImg")
        require(images.size == 1)
        val imageUrl = NativeVpnUrls.resolve(page.url, images.single().attr("src"))
        require(imageUrl.substringBefore('?') == NativeVpnUrls.captcha)
        phase = "S07 读取验证码图片"
        val image = transport.request(imageUrl, referer = page.url)
        require(image.code == 200 && image.contentType.startsWith("image/", true) && image.body.size in 1..262_144)
        phase = "S08 核对登录按钮与五天登录选项"
        val buttons = form.select("#passbutton[name=submit]")
        require(buttons.size == 1)
        val remember = form.select("input[name=rememberMe]")
        require(remember.size == 1)
        synchronized(lifecycle) {
            requireOpen()
            active = NativeVpnForm(NativeVpnForm.Kind.SSO, action, hidden, transport.cookieBinding(action), clock(),
                exponent, modulus, buttons.single().attr("value"), remember.single().attr("value").ifBlank { "on" })
            activeImage = image.body
            return NativeVpnStep(active, image.body, SessionChecker.Result("vpn_login_required", message, vpnAuthState = "expired"), error, failureKind)
        }
    }

    private fun libraryStep(page: NativeVpnResponse, message: String = "统一认证已通过，请在应用内登录图书馆", error: Boolean = false,
        failureKind: NativeVpnFailureKind = NativeVpnFailureKind.NONE): NativeVpnStep {
        phase = "B01 解析图书馆登录表单"
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
        synchronized(lifecycle) {
            requireOpen()
            active = NativeVpnForm(NativeVpnForm.Kind.LIBRARY, action, hidden, transport.cookieBinding(action), clock())
            activeImage = null
            return NativeVpnStep(active, result = SessionChecker.Result("expired", message, vpnAuthState = "valid"), error = error, failureKind = failureKind)
        }
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
    fun awaitQuiescence(timeoutMs: Long): Boolean = transport.awaitQuiescence(timeoutMs)
    fun isQuiescent(): Boolean = transport.isQuiescent()
    override fun close() { synchronized(lifecycle) { closed = true; active = null; activeImage = null }; transport.close() }
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
