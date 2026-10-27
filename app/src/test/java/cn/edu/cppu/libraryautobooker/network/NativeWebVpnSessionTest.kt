package cn.edu.cppu.libraryautobooker.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.Base64

/** Synthetic fields/challenges only. No live school requests or account values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeWebVpnSessionTest {
    @Test fun anExpiredPortalLandingRecoversTheKnownSsoFormWithOnlyBoundedGetRequests() {
        NativeLoginFixture().use { server ->
            val calls = mutableListOf<Pair<String, String>>()
            val transport = object : NativeVpnTransport {
                override fun request(url: String, method: String, body: ByteArray?, referer: String?, follow: Boolean): NativeVpnResponse {
                    calls.add(method to url)
                    if (method == "GET" && url in setOf(NativeVpnUrls.libraryLogin, "https://webvpn.cppu.edu.cn/login"))
                        return NativeVpnResponse(url, 302, byteArrayOf(), location = "/portal")
                    return server.request(url, method, body, referer, follow)
                }
                override fun cookieBinding(url: String) = server.binding
                override fun close() {}
            }
            NativeWebVpnSession(transport) { server.time }.use { client ->
                val next = client.load()
                assertEquals(NativeVpnForm.Kind.SSO, next.form!!.kind)
                assertNotNull(next.image)
                assertNotEquals("valid", next.result.state)
                assertEquals(1, calls.count { it == ("GET" to NativeVpnUrls.ssoLogin) })
                assertEquals(1, calls.count { it == ("GET" to "https://webvpn.cppu.edu.cn/login") })
                assertTrue(calls.filter { it.first == "POST" }.all { LibraryRoute.WEBVPN.pathOf(it.second) == "/isLogin" })
                assertTrue(server.posts.isEmpty())
            }
        }
    }
    @Test fun aPortalThatNeverReturnsAFormStopsAfterOneRecoverySequence() {
        NativeLoginFixture().use { server ->
            val calls = mutableListOf<Pair<String, String>>()
            val transport = object : NativeVpnTransport {
                override fun request(url: String, method: String, body: ByteArray?, referer: String?, follow: Boolean): NativeVpnResponse {
                    calls.add(method to url)
                    return if (method == "GET") NativeVpnResponse(url, 302, byteArrayOf(), location = "/portal")
                        else server.request(url, method, body, referer, follow)
                }
                override fun cookieBinding(url: String) = server.binding
                override fun close() {}
            }
            NativeWebVpnSession(transport) { server.time }.use { client ->
                assertThrows(NativeVpnFailure::class.java) { client.load() }
                assertEquals(1, calls.count { it == ("GET" to NativeVpnUrls.ssoLogin) })
                assertEquals(5, calls.size)
                assertTrue(server.posts.isEmpty())
            }
        }
    }
    @Test fun readOnlyChecksPreserveOnlyAnUnconsumedCurrentChallenge() {
        NativeLoginFixture().use { f ->
            val old = f.client.load()
            val checked = f.client.check()
            assertSame(old.form, checked.form)
            assertSame(old.image, checked.image)
            assertTrue(checked.error)
            assertNotEquals("valid", checked.result.state)
            assertTrue(f.posts.isEmpty())
            f.binding = "synthetic-new-session"
            assertThrows(NativeVpnFailure::class.java) { f.client.check() }
        }
        NativeLoginFixture().use { f ->
            f.client.load(); f.time += 120_001
            assertThrows(NativeVpnFailure::class.java) { f.client.check() }
            assertTrue(f.posts.isEmpty())
        }
    }
    @Test fun aReturnedSsoFormWithoutAnExplicitMessageStillProvidesAFreshManualChallenge() {
        NativeLoginFixture().use { f ->
            val old = f.client.load().form!!
            f.blankRejection = true
            val next = f.client.submit(old, "synthetic-user", "synthetic-secret", "1234", false)
            assertTrue(next.error)
            assertEquals(NativeVpnForm.Kind.SSO, next.form!!.kind)
            assertNotSame(old, next.form)
            assertNotNull(next.image)
            assertEquals(1, f.posts.size)
            assertTrue(old.consumed.get())
            assertFalse(next.form.consumed.get())
        }
    }
    @Test fun malformedReloadAndPostResponseReportTheExactSafePhaseWithoutCredentials() {
        NativeLoginFixture().use { f ->
            f.ssoHtml = f.ssoHtml.replace("name=\"execution\"", "name=\"unknownExecution\"")
            val failure = assertThrows(NativeVpnFailure::class.java) { f.client.load() }
            assertTrue(failure.explanation.contains("S03"))
            assertFalse(failure.explanation.contains("synthetic-execution"))
            assertFalse(failure.mayHaveSubmitted)
            assertTrue(f.posts.isEmpty())
        }
        NativeLoginFixture().use { f ->
            val form = f.client.load().form!!
            f.rejectCaptcha = true
            f.script = "synthetic-changed-script"
            val failure = assertThrows(NativeVpnFailure::class.java) {
                f.client.submit(form, "synthetic-user", "synthetic-secret", "1234", false)
            }
            assertTrue(failure.mayHaveSubmitted)
            assertTrue(failure.explanation.contains("S05"))
            assertTrue(failure.explanation.contains("不会自动重发"))
            assertEquals(1, f.posts.size)
            assertThrows(NativeVpnFailure::class.java) {
                f.client.submit(form, "synthetic-user", "synthetic-secret", "1234", false)
            }
            assertEquals(1, f.posts.size)
        }
    }
    @Test fun diagnosticsSeparateTimeoutDnsTlsAndInvalidFormWithoutIncludingExceptionMessages() {
        val sensitive = "synthetic-password cookie=synthetic-session https://example.invalid/?ticket=synthetic-ticket"
        val cases = listOf(
            java.net.SocketTimeoutException(sensitive) to "超时",
            java.net.UnknownHostException(sensitive) to "解析",
            javax.net.ssl.SSLHandshakeException(sensitive) to "HTTPS",
            IllegalArgumentException(sensitive) to "适配规则",
            NoClassDefFoundError(sensitive) to "运行组件"
        )
        for ((error, expected) in cases) {
            val failure = NativeVpnFailure.from(error, "S07 读取验证码图片")
            assertTrue(failure.explanation.contains(expected))
            assertTrue(failure.explanation.contains("S07"))
            assertFalse(failure.explanation.contains("synthetic"))
            assertFalse(failure.explanation.contains("ticket="))
            assertFalse(failure.mayHaveSubmitted)
        }
    }
    @Test fun nativeSsoAndLibraryUseSeparateFieldsAndVerifyBothSessions() {
        NativeLoginFixture().use { f ->
            val sso = f.client.load()
            assertEquals(NativeVpnForm.Kind.SSO, sso.form!!.kind)
            assertNotNull(sso.image)
            val library = f.client.submit(sso.form, "synthetic-sso", "synthetic-password", "1234", true)
            assertEquals(NativeVpnForm.Kind.LIBRARY, library.form!!.kind)
            val first = f.posts.single { it.first == "sso" }.second
            assertEquals("synthetic-sso", first["username"])
            assertEquals("1234", first["authcode"]); assertEquals("true", first["rememberMe"])
            assertEquals("synthetic-execution", first["execution"])
            assertNotEquals("synthetic-password", first["password"])
            val done = f.client.submit(library.form, "synthetic-library", "different-library-secret", "", false)
            assertEquals("valid", done.result.state)
            val second = f.posts.single { it.first == "library" }.second
            assertEquals("synthetic-library", second["user"])
            assertEquals(SchoolPasswordEncoding.library("different-library-secret"), second["passwd"])
            assertEquals("selectreadingroom", second["url"])
            assertFalse(second.containsKey("username")); assertFalse(second.containsKey("authcode"))
            assertEquals(2, f.posts.size)
        }
    }
    @Test fun passwordEncodingMatchesTheRealPublicSchoolRsaScriptGoldenVectors() {
        // Compared offline with public security.js SHA256
        // de4ce8fd9327b150289a30e949739ea9b5f475ed009434a3139be79c6241f6d7.
        assertEquals("6d5b82c6d48b60eb6451cba43dcca739dd23e6d206e278910eeee4077528dd42bfd74c1ba6afd130456c26d03c2ba078086ec96387b878a1fdeee18709677ce7a2e018e5ae5b48901c481577f00ba969c091f8080274c982b9b206c957f1df11dc90334f0daf344d9e21a4b9648da9254d3312162f9eacda3ecff5a04e428df4",
            SchoolPasswordEncoding.sso("synthetic-password", "010001", NativeLoginFixture.modulus))
        assertEquals("5d28141b06fd39b2704294d9e3e50e4606961471716e652b59faf34aae96eb34101bc4dc8350ad21431c88a7838f5a08da9f68781be480ce0f486a2bd7bbe9629e4006a8a29ecef95a58af84c0173d9b11098862cbe81595bae48670af5e7a703de7bbfc9bcd2761cc9cd9e53cfa02a2e3f16677b2b32ce7eb8c59c08745de67",
            SchoolPasswordEncoding.sso("中文123", "010001", NativeLoginFixture.modulus))
        assertEquals(2, SchoolPasswordEncoding.sso("a".repeat(127), "010001", NativeLoginFixture.modulus).split(' ').size)
        assertEquals("c3ludGhldGljLXBhc3N3b3Jk", SchoolPasswordEncoding.library("synthetic-password"))
    }
    @Test fun missingInvalidOrExpiredCaptchaNeverSendsCredentials() {
        for (code in listOf("", "123", "12345", "12a4")) NativeLoginFixture().use { f ->
            val form = f.client.load().form!!
            assertThrows(NativeVpnFailure::class.java) { f.client.submit(form, "synthetic", "secret", code, false) }
            assertTrue(f.posts.isEmpty())
        }
        NativeLoginFixture().use { f ->
            val form = f.client.load().form!!; f.time += 120_001
            assertThrows(NativeVpnFailure::class.java) { f.client.submit(form, "synthetic", "secret", "1234", false) }
            assertTrue(f.posts.isEmpty())
        }
    }
    @Test fun changedCookiesRefreshedFormAndDoubleTapCannotReuseAnOldChallenge() {
        NativeLoginFixture().use { f ->
            val old = f.client.load().form!!; f.binding = "changed"
            assertThrows(NativeVpnFailure::class.java) { f.client.submit(old, "synthetic", "secret", "1234", false) }
            assertTrue(f.posts.isEmpty())
            val replaced = f.client.load().form!!; val current = f.client.load().form!!
            assertThrows(NativeVpnFailure::class.java) { f.client.submit(replaced, "synthetic", "secret", "1234", false) }
            f.client.submit(current, "synthetic", "secret", "1234", false)
            assertThrows(NativeVpnFailure::class.java) { f.client.submit(current, "synthetic", "secret", "1234", false) }
            assertEquals(1, f.posts.size)
        }
    }
    @Test fun rememberMeOptOutOmitsTheFieldAndCaptchaRejectionNeedsAnotherExplicitSubmit() {
        NativeLoginFixture().use { f ->
            f.rejectCaptcha = true
            val old = f.client.load().form!!
            val rejected = f.client.submit(old, "synthetic", "secret", "1234", false)
            assertTrue(rejected.error); assertEquals(NativeVpnForm.Kind.SSO, rejected.form!!.kind)
            assertEquals(1, f.posts.size); assertFalse(f.posts.single().second.containsKey("rememberMe"))
            assertThrows(NativeVpnFailure::class.java) { f.client.submit(old, "synthetic", "secret", "1234", false) }
            assertEquals(1, f.posts.size)
            f.rejectCaptcha = false
            f.client.submit(rejected.form, "synthetic", "secret", "5678", false)
            assertEquals(2, f.posts.size)
        }
    }
    @Test fun postTimeoutLocksThisFormAndReadOnlyCheckNeverResendsIt() {
        NativeLoginFixture().use { f ->
            f.failPost = true
            val form = f.client.load().form!!
            val failure = assertThrows(NativeVpnFailure::class.java) { f.client.submit(form, "synthetic", "secret", "1234", false) }
            assertTrue(failure.mayHaveSubmitted)
            assertThrows(NativeVpnFailure::class.java) { f.client.check() }
            assertThrows(NativeVpnFailure::class.java) { f.client.submit(form, "synthetic", "secret", "1234", false) }
            assertEquals(1, f.posts.size)
        }
    }
    @Test fun foreignSsoTargetServiceMissingFieldsAndChangedScriptFailBeforeCredentialPost() {
        val replacements = listOf(
            "action=\"${NativeLoginFixture.action}\"" to "action=\"https://attacker.example/login\"",
            "webvpn.cppu.edu.cn%2Flogin" to "attacker.example%2Flogin",
            "name=\"execution\"" to "name=\"unknownExecution\"",
            "name=\"password\"" to "name=\"otherPassword\"",
            "id=\"authcode\"" to "id=\"notAuthcode\""
        )
        for ((before, after) in replacements) NativeLoginFixture().use { f ->
            f.ssoHtml = f.ssoHtml.replace(before, after)
            assertThrows(Exception::class.java) { f.client.load() }
            assertTrue(f.posts.isEmpty())
        }
        NativeLoginFixture().use { f ->
            f.script = "function changedProtocol() {}"
            assertThrows(Exception::class.java) { f.client.load() }; assertTrue(f.posts.isEmpty())
        }
    }
    @Test fun urlPolicyRejectsThirdPartyCleartextCredentialRedirectsAndEncodedPaths() {
        for (url in listOf("http://webvpn.cppu.edu.cn/login", "https://attacker.example/login",
            "https://webvpn.cppu.edu.cn:444/login", "https://someone@webvpn.cppu.edu.cn/login",
            "https://webvpn.cppu.edu.cn/login#fragment", "https://webvpn.cppu.edu.cn/%2f/login",
            "https://sso.cppu.edu.cn/tpass/login", "https://webvpn.cppu.edu.cn/http/other/login"))
            assertFalse(url, NativeVpnUrls.allows(url))
        assertTrue(NativeVpnUrls.allows(NativeLoginFixture.action))
        assertTrue(NativeVpnUrls.allows(NativeVpnUrls.libraryLogin))
    }
    @Test fun hiddenParametersAndResponsesHaveRedactedDiagnostics() {
        NativeLoginFixture().use { f ->
            val step = f.client.load()
            assertFalse(step.toString().contains("synthetic-execution"))
            assertFalse(step.form.toString().contains("synthetic-execution"))
            assertFalse(NativeVpnResponse(NativeLoginFixture.action, 200, "synthetic-private".toByteArray()).toString().contains("synthetic-private"))
        }
    }
}

internal class NativeLoginFixture : NativeVpnTransport {
    var time = 100L
    var binding = "synthetic-cookie-binding"
    var rejectCaptcha = false
    var blankRejection = false
    var failPost = false
    private var ssoValid = false
    private var libraryValid = false
    var closed = false
    val posts = mutableListOf<Pair<String, Map<String, String>>>()
    var script = "RSAUtils.getKeyPair(\"010001\", '', \"$modulus\"); RSAUtils.encryptedString(key, thisPwd);"
    var ssoHtml = """<html><meta charset="utf-8"><script src="${NativeVpnUrls.loginScript}"></script>
        <form id="fm1" method="post" action="$action">
        <input id="username" name="username"><input id="password" name="password" type="password">
        <input id="authcode" name="authcode"><img class="codeImg" src="${NativeVpnUrls.captcha}">
        <input name="execution" type="hidden" value="synthetic-execution">
        <input name="encrypted" type="hidden" value="true"><input name="_eventId" type="hidden" value="submit">
        <input name="loginType" type="hidden" value="1"><input name="rememberMe" type="checkbox" value="true">
        <input id="passbutton" name="submit" type="submit" value="登录"></form></html>""".trimIndent()
    private val libraryHtml = """<html><form id="fromuser" method="POST" action="login">
        <input id="user" name="user"><input id="passwd" name="passwd" type="password">
        <input id="url" name="url" type="hidden" value=""></form></html>""".trimIndent()
    val client = NativeWebVpnSession(this) { time }
    override fun cookieBinding(url: String) = binding
    override fun request(url: String, method: String, body: ByteArray?, referer: String?, follow: Boolean): NativeVpnResponse {
        check(!closed)
        if (method == "POST" && LibraryRoute.WEBVPN.pathOf(url) != "/isLogin") {
            val fields = body!!.toString(Charsets.UTF_8).split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            posts.add((if (WebVpnPages.isSso(url)) "sso" else "library") to fields)
            if (failPost) throw java.net.SocketTimeoutException("synthetic failure")
            if (WebVpnPages.isSso(url)) {
                if (blankRejection) return html(action, ssoHtml)
                if (rejectCaptcha) return html(action, ssoHtml.replace("<form id=", "<div id=\"msg1\">验证码信息无效</div><form id="))
                ssoValid = true
                return html(NativeVpnUrls.libraryLogin, libraryHtml)
            }
            libraryValid = true
            return html(LibraryRoute.WEBVPN.url("/index"), "<html>synthetic</html>")
        }
        return when {
            url == NativeVpnUrls.loginScript -> NativeVpnResponse(url, 200, script.toByteArray(), "application/javascript")
            url == NativeVpnUrls.captcha -> NativeVpnResponse(url, 200, Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="), "image/png")
            LibraryRoute.WEBVPN.pathOf(url) == "/isLogin" -> NativeVpnResponse(url, 200,
                (if (libraryValid) "{\"ReturnValue\":1}" else if (ssoValid) "{\"ReturnValue\":0}" else "{\"success\":false,\"url\":\"/login\"}").toByteArray(), "application/json")
            ssoValid -> html(NativeVpnUrls.libraryLogin, libraryHtml)
            else -> html(action, ssoHtml)
        }
    }
    private fun html(url: String, text: String) = NativeVpnResponse(url, 200, text.toByteArray(), "text/html; charset=UTF-8")
    override fun close() { closed = true }
    companion object {
        const val modulus = "008aed7e057fe8f14c73550b0e6467b023616ddc8fa91846d2613cdb7f7621e3cada4cd5d812d627af6b87727ade4e26d26208b7326815941492b2204c3167ab2d53df1e3a2c9153bdb7c8c2e968df97a5e7e01cc410f92c4c2c2fba529b3ee988ebc1fca99ff5119e036d732c368acf8beba01aa2fdafa45b21e4de4928d0d403"
        val action = "${NativeVpnUrls.ssoPrefix}/tpass/login?service=https%3A%2F%2Fwebvpn.cppu.edu.cn%2Flogin%3Fcas_login%3Dtrue"
    }
}
