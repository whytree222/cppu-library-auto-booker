package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.ocr.CaptchaPagePolicy
import cn.edu.cppu.libraryautobooker.ocr.WebVpnCaptchaController
import org.json.JSONObject
import org.json.JSONTokener

/** Two independent opted-in accounts. This class never submits a seat reservation. */
class WebVpnLoginDriver internal constructor(
    context: Context,
    private val view: WebView,
    private val onStatus: (String) -> Unit,
    private val onResult: (SessionChecker.Result) -> Unit,
    private val ssoStore: WebVpnCredentialStore,
    private val libraryStore: WebVpnCredentialStore,
    private val probe: ((SessionChecker.Result) -> Unit) -> Unit
) : AutoCloseable {
    constructor(context: Context, view: WebView, onStatus: (String) -> Unit,
        onResult: (SessionChecker.Result) -> Unit) : this(context, view, onStatus, onResult,
        WebVpnCredentialStore(context, Kind.SSO), WebVpnCredentialStore(context, Kind.LIBRARY),
        { callback -> SessionChecker.probe(context.applicationContext, LibraryRoute.WEBVPN, updateSession = false, callback = callback) })
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sso = ssoStore
    private val library = libraryStore
    private val ssoScript = app.assets.open("webvpn-sso-login.js").bufferedReader().use { it.readText() }
    private val libraryScript = app.assets.open("webvpn-library-login.js").bufferedReader().use { it.readText() }
    private var epoch = 0L
    private var closed = false
    private var stopped = false
    private var active = false
    private var probing = false
    private var loading = false
    // A URL can be available before WebView's first page callbacks or redirect completes.
    // Only a matching onPageFinished establishes a document we may inspect/fill.
    private var pageReady = false
    private var finishedUrl: String? = null
    private var dispatchedEpoch = -1L
    private var prepared = false
    private var ssoSubmitted = false
    private var ssoConfirmed = false
    private var librarySubmitted = false
    private var ssoVersion = ""
    private var libraryVersion = ""
    private var portalRedirected = false
    private var preparing = false
    private var finishedWhilePaused = false
    private var captchaFailure: String? = null
    private var captchaReady = false
    internal val running get() = !closed && !stopped
    private var ocrEnabled = app.getSharedPreferences("webvpn_ocr", Context.MODE_PRIVATE).getBoolean("enabled", true)
    private var rememberMe = app.getSharedPreferences("webvpn_ocr", Context.MODE_PRIVATE).getBoolean("remember_me", true)
    private val captcha = WebVpnCaptchaController(app, view, onFilled = { captchaReady = true; submitSso() }, onStopped = {
        captchaReady = false
        captchaFailure = it
        if (prepared && !ssoSubmitted && !stopped) fail(it, "vpn_login_required", "expired")
    }) {
        if (!closed && (!stopped || CaptchaPagePolicy.isTrusted(view.url)))
            onStatus(if (stopped) "$it；自动登录已停止，可手动继续或检查两套账号设置"
                else if (ssoSubmitted) "统一认证已提交，正在等待学校结果（不能视为登录成功）" else it)
    }

    init {
        captcha.setEnabled(ocrEnabled)
        main.postDelayed({
            fail("WebVPN 两步登录超过 90 秒仍未验证成功，已停止；请核对网页或网络", "unknown", pause =
                if (librarySubmitted) Kind.LIBRARY else if (ssoSubmitted) Kind.SSO else null)
        }, 90_000L)
    }

    fun onResume() {
        if (closed) return
        active = true
        if (!ssoSubmitted && !stopped) captcha.onResume()
        if (!stopped && !loading && pageReady && finishedUrl == view.url) {
            if (ssoSubmitted && CaptchaPagePolicy.isTrusted(view.url) && !finishedWhilePaused)
                verifyLater(epoch, Kind.SSO)
            else dispatchReadyPage(view.url.orEmpty())
        }
    }
    /** Used on initial entry and explicit restart; neither requires a second button press. */
    fun openLoginPage() { navigate(LibraryRoute.WEBVPN.url("/login")) }

    private fun navigate(url: String) {
        if (closed || stopped) return
        // Invalidate the old document synchronously, before loadUrl schedules its callbacks.
        onPageStarted(url)
        view.loadUrl(url)
    }
    fun onPause() { active = false; epoch++; probing = false; preparing = false; captcha.onPause() }
    fun setOcrEnabled(enabled: Boolean) { ocrEnabled = enabled; captcha.setEnabled(enabled) }
    fun setRememberMe(enabled: Boolean) {
        rememberMe = enabled
        if (prepared && !ssoSubmitted && !stopped && CaptchaPagePolicy.isTrusted(view.url))
            evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.setRememberMe($enabled) || {kind:'stopped'})", epoch) {}
    }
    fun retryCaptcha() {
        if (ssoSubmitted) { onStatus("统一认证已经提交，不再刷新或重试验证码；请核对学校结果"); return }
        captchaFailure = null; captcha.onResume(); captcha.retry()
    }

    fun onPageStarted(url: String) {
        if (closed) return
        epoch++; loading = true; probing = false; preparing = false; prepared = false
        pageReady = false; finishedUrl = null
        captchaFailure = null; captchaReady = false; finishedWhilePaused = false
        captcha.onPageStarted(url)
    }

    fun onPageFinished(url: String) {
        if (closed || url != view.url) return
        loading = false
        if (!pageReady || finishedUrl != url) captcha.onPageFinished(url)
        pageReady = true
        finishedUrl = url
        if (!active) { finishedWhilePaused = true; return }
        finishedWhilePaused = false
        dispatchReadyPage(url)
    }

    private fun dispatchReadyPage(url: String) {
        if (!pageReady || loading || finishedUrl != url || url != view.url || !active || dispatchedEpoch == epoch) return
        if (stopped) return
        // Duplicate finish callbacks must not cancel an in-flight preparation or POST.
        dispatchedEpoch = epoch
        when {
            CaptchaPagePolicy.isTrusted(url) -> {
                if (ssoSubmitted) fail("统一认证仍停留在登录页，可能账号密码、验证码或校方校验未通过；已暂停自动重试", "vpn_login_required", "expired", Kind.SSO)
                else prepareSso()
            }
            LibraryRoute.WEBVPN.pathOf(url) != null -> checkLibrary()
            isPortal(url) && !portalRedirected -> {
                portalRedirected = true
                onStatus("正在恢复 WebVPN 会话并进入图书馆…")
                navigate(LibraryRoute.WEBVPN.url("/login"))
            }
            else -> fail("登录跳转地址尚未适配，已停止自动填写", "unknown")
        }
    }

    private fun live(id: Long) = !closed && !stopped && active && id == epoch

    private fun prepareSso() {
        if (preparing || !CaptchaPagePolicy.isTrusted(view.url)) return
        if (!ocrEnabled) { fail("未开启验证码识别，请手动认证或在网页开启识别", "vpn_login_required", "expired"); return }
        val credentials = sso.read()
        if (credentials == null) {
            fail(if (sso.paused) "统一认证自动登录已暂停，请在账号设置中确认后重新尝试"
                else "请先设置独立的统一认证学工号和密码；不会使用图书馆密码", "vpn_login_required", "expired")
            return
        }
        ssoVersion = sso.revision
        val id = epoch
        preparing = true
        evaluate(ssoScript, id) {
            val json = JSONObject().put("username", credentials.username).put("password", credentials.password)
                .put("rememberMe", rememberMe).toString()
            evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.prepare($json) || {kind:'stopped'})", id) { result ->
                preparing = false
                if (!sso.enabled || sso.paused || sso.revision != ssoVersion) { fail("统一认证账号设置已改变，已停止本次自动登录", "vpn_login_required"); return@evaluate }
                when (result.optString("kind")) {
                    "prepared" -> {
                        prepared = true
                        val failure = captchaFailure
                        if (failure != null) fail(failure, "vpn_login_required", "expired")
                        else if (captchaReady) submitSso()
                        else onStatus("统一认证账号已填写；等待验证码完成后自动提交登录")
                    }
                    "manual" -> fail("检测到手动填写的统一认证账号，自动登录已停止，不会覆盖", "vpn_login_required", "expired")
                    else -> fail("统一认证密码表单尚未适配，已停止自动填写（sso-form）", "vpn_login_required", "expired")
                }
            }
        }
    }

    private fun submitSso() {
        if (closed || stopped || !active || !ocrEnabled || !prepared || ssoSubmitted || !CaptchaPagePolicy.isTrusted(view.url)) return
        if (!sso.enabled || sso.paused || sso.revision != ssoVersion) { fail("统一认证账号设置已改变，已停止", "vpn_login_required"); return }
        val id = epoch
        // Lock before invoking the school's button: an exception or delayed response must not resubmit.
        ssoSubmitted = true
        // Once the credential POST may start, OCR must not refresh another challenge.
        captcha.onPause()
        onStatus("正在通过学校原登录按钮提交统一认证…")
        evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.submit() || {kind:'stopped'})", id) { result ->
            if (result.optString("kind") != "submitted") {
                if (result.optString("kind") == "waiting") {
                    // No submission took place; a newly current CAPTCHA may complete this preparation.
                    ssoSubmitted = false
                    captchaReady = false
                    captcha.onResume()
                    onStatus("验证码已更新，等待当前图片重新识别")
                } else fail("统一认证未能安全提交，已停止；请在网页核对", "vpn_login_required", "expired", Kind.SSO)
            } else verifyLater(id, Kind.SSO)
        }
    }

    private fun checkLibrary() {
        if (probing || stopped || !active) return
        probing = true
        val id = epoch
        CookieManager.getInstance().flush()
        probe { result ->
            if (!live(id)) return@probe
            probing = false
            if (result.vpnAuthState == "valid" && ssoSubmitted) {
                ssoConfirmed = true
                if (sso.revision == ssoVersion && sso.enabled) sso.markVerified()
            }
            when {
                result.state == "valid" -> {
                    if (librarySubmitted && library.revision == libraryVersion && library.enabled) library.markVerified()
                    stopped = true
                    onStatus("学校已验证 WebVPN 和图书馆两层登录有效")
                    onResult(result.copy(detail = "WebVPN 两步登录完成，学校已验证图书馆会话有效"))
                }
                result.state == "expired" && result.vpnAuthState == "valid" -> {
                    if (librarySubmitted) fail("WebVPN 已认证，但图书馆未接受本次登录；请核对图书馆账号密码，已暂停自动重试", "expired", "valid", Kind.LIBRARY)
                    else if (LibraryRoute.WEBVPN.pathOf(view.url.orEmpty()) == "/login") submitLibrary()
                    else navigate(LibraryRoute.WEBVPN.url("/login"))
                }
                result.state == "vpn_login_required" && !ssoSubmitted && !librarySubmitted -> navigate(LibraryRoute.WEBVPN.url("/login"))
                else -> fail(if (ssoSubmitted || librarySubmitted) "登录提交后无法确认学校结果，已停止且不会重发；请核对网页登录状态"
                    else result.detail, result.state, result.vpnAuthState, if (librarySubmitted) Kind.LIBRARY else if (ssoSubmitted) Kind.SSO else null)
            }
        }
    }

    private fun submitLibrary() {
        if (preparing || librarySubmitted || LibraryRoute.WEBVPN.pathOf(view.url.orEmpty()) != "/login") return
        val credentials = library.read()
        if (credentials == null) {
            fail(if (library.paused) "统一认证已通过；图书馆自动登录已暂停，请核对第二套账号"
                else "统一认证已通过；请设置 WebVPN 内图书馆账号密码，不会把统一认证密码发送给图书馆", "expired", "valid")
            return
        }
        libraryVersion = library.revision
        val id = epoch
        preparing = true
        evaluate(libraryScript.replace("__LOGIN_CREDENTIALS__", "null"), id) { state ->
            if (state.optString("kind") == "waiting") {
                preparing = false
                main.postDelayed({ if (live(id)) submitLibrary() }, 700L)
                return@evaluate
            }
            if (!library.enabled || library.paused || library.revision != libraryVersion) {
                fail("图书馆账号设置已改变；已停止本次自动登录", "expired", "valid")
                return@evaluate
            }
            if (state.optString("kind") != "ready") {
                fail("图书馆自动登录停止：${libraryReason(state.optString("detailCode"))}", "expired", "valid")
                return@evaluate
            }
            val json = JSONObject().put("username", credentials.username).put("password", credentials.password).toString()
            librarySubmitted = true
            onStatus("统一认证已通过，正在使用独立图书馆账号登录…")
            evaluate(libraryScript.replace("__LOGIN_CREDENTIALS__", json), id) { result ->
                preparing = false
                if (result.optString("kind") == "submitted") verifyLater(id, Kind.LIBRARY)
                else fail("图书馆未能安全提交或结果不明；不会自动重发，请核对网页", "expired", "valid", Kind.LIBRARY)
            }
        }
    }

    private fun libraryReason(code: String) = when (code) {
        "wrong-page" -> "未到达已适配的图书馆登录页"
        "wrong-action" -> "图书馆表单的提交地址未匹配"
        "unsupported-structure" -> "主登录表单的账号或密码输入框未匹配"
        "user-edited", "occupied-fields" -> "检测到手动或已有填写，不会覆盖"
        "additional-verification" -> "学校要求额外验证，请手动完成"
        else -> "图书馆表单尚未适配，请手动完成"
    }

    private fun verifyLater(id: Long, kind: Kind) {
        main.postDelayed({
            if (!live(id) || loading) return@postDelayed
            if (LibraryRoute.WEBVPN.pathOf(view.url.orEmpty()) != null) checkLibrary()
            else if (kind == Kind.SSO) fail("统一认证提交后没有确认通过；已停止并暂停自动重试，请核对网页提示", "vpn_login_required", "expired", Kind.SSO)
        }, 12_000L)
    }

    private fun evaluate(script: String, id: Long, callback: (JSONObject) -> Unit) {
        if (!live(id)) return
        try { view.evaluateJavascript(script) { raw ->
            if (!live(id)) return@evaluateJavascript
            val result = runCatching {
                require(raw != null && raw.length < 8192)
                when (val value = JSONTokener(raw).nextValue()) {
                    is JSONObject -> value
                    is String -> JSONObject(value)
                    else -> JSONObject().put("kind", "stopped")
                }
            }.getOrElse { JSONObject().put("kind", "stopped") }
            callback(result)
        } } catch (_: RuntimeException) { fail("登录页面已变化，未能确认登录结果；已停止", "unknown") }
    }

    fun fail(message: String, state: String = "unknown", auth: String? = null,
        pause: Kind? = if (librarySubmitted) Kind.LIBRARY else if (ssoSubmitted) Kind.SSO else null) {
        if (closed || stopped) return
        if (CaptchaPagePolicy.isTrusted(view.url)) runCatching { view.evaluateJavascript("window.SchoolSsoAutoLogin?.cancel()", null) }
        stopped = true
        captcha.onPause()
        if (pause == Kind.SSO && sso.revision == ssoVersion) sso.pause()
        if (pause == Kind.LIBRARY && library.revision == libraryVersion) library.pause()
        onStatus(message)
        onResult(SessionChecker.Result(state, message, vpnAuthState = auth))
    }

    override fun close() {
        if (closed) return
        // Cancelling after a POST cannot establish its outcome. Require explicit user retry.
        if (!stopped) {
            if (librarySubmitted && library.revision == libraryVersion) library.pause()
            if (ssoSubmitted && !ssoConfirmed && sso.revision == ssoVersion) sso.pause()
        }
        if (CaptchaPagePolicy.isTrusted(view.url))
            runCatching { view.evaluateJavascript("window.SchoolSsoAutoLogin?.cancel()", null) }
        closed = true; epoch++; main.removeCallbacksAndMessages(null); captcha.close()
    }

    companion object {
        fun isPortal(url: String?): Boolean = runCatching {
            val uri = java.net.URI(url ?: return@runCatching false)
            uri.scheme == "https" && uri.host == "webvpn.cppu.edu.cn" && uri.userInfo == null && uri.port in listOf(-1,443) &&
                uri.path.orEmpty() in setOf("", "/", "/login") && uri.fragment == null
        }.getOrDefault(false)
        fun allowsNavigation(url: String) = isPortal(url) || CaptchaPagePolicy.isTrusted(url) ||
            LibraryRoute.WEBVPN.pathOf(url) != null
    }
}
