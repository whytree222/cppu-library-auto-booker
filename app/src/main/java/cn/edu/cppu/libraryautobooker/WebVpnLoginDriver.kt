package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val loginTap = WebViewLoginTap(view)
    private val deadlineElapsed = SystemClock.elapsedRealtime() + 90_000L
    private var epoch = 0L
    // Unlike epoch, this changes only for main-frame navigation, never for pause/resume.
    private var documentSerial = 0L
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
    private var ssoCallSerial = 0L
    private var ssoSubmittedDocument = -1L
    private var ssoAttempts = 0
    private var loginTapAttempts = 0
    // Bounded local recovery, keyed by ssoCallSerial so each submission round gets at
    // most one re-arm and at most one school-handler fallback, on the same document.
    private var ssoRearmedCall = -1L
    private var ssoFallbackCall = -1L
    // Shared by explicit CAPTCHA rejection and proven unsubmitted-page recovery.
    private var freshRestarts = 0
    private var restartGuardMethod = "captchaRestartState"
    private var restartPending = false
    private var restartAtElapsed = 0L
    private var ssoConfirmed = false
    private var librarySubmitted = false
    private var ssoVersion = ""
    private var libraryVersion = ""
    private var portalRedirected = false
    private var preparing = false
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
        main.postDelayed({ timeout() }, 90_000L)
    }

    private fun timeout() = fail("WebVPN 两步登录超过 90 秒仍未验证成功，已停止；请核对网页或网络", "unknown", pause =
        if (librarySubmitted) Kind.LIBRARY else if (ssoSubmitted) Kind.SSO else null)

    private fun withinDeadline(): Boolean {
        if (closed || stopped) return false
        if (SystemClock.elapsedRealtime() >= deadlineElapsed) { timeout(); return false }
        return true
    }

    fun onResume() {
        if (closed) return
        active = true
        if (!withinDeadline()) return
        if (restartPending) { scheduleFreshRestart(); return }
        if (!ssoSubmitted && !stopped) captcha.onResume()
        if (!stopped && !loading && pageReady && finishedUrl == view.url) {
            if (ssoSubmitted && CaptchaPagePolicy.isTrusted(view.url) && documentSerial == ssoSubmittedDocument)
                verifyLater(epoch, Kind.SSO)
            else dispatchReadyPage(view.url.orEmpty())
        }
    }
    /** Used on initial entry and explicit restart; neither requires a second button press. */
    fun openLoginPage() { navigate(LibraryRoute.WEBVPN.url("/login")) }

    private fun navigate(url: String) {
        if (!withinDeadline()) return
        // Invalidate the old document synchronously, before loadUrl schedules its callbacks.
        onPageStarted(url)
        view.loadUrl(url)
    }
    fun onPause() { active = false; epoch++; loginTap.cancel(); probing = false; preparing = false; captcha.onPause() }
    fun setOcrEnabled(enabled: Boolean) {
        ocrEnabled = enabled; captcha.setEnabled(enabled)
        if (!enabled && restartPending) fail("验证码识别已关闭，已取消统一认证自动重新开始", "vpn_login_required", "expired", pause = null)
    }
    fun setRememberMe(enabled: Boolean) {
        rememberMe = enabled
        if (prepared && !ssoSubmitted && !stopped && CaptchaPagePolicy.isTrusted(view.url))
            evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.setRememberMe($enabled) || {kind:'stopped'})", epoch) {}
    }
    fun retryCaptcha() {
        if (restartPending) { onStatus("正在等待新登录页面重新开始；不在旧页面重复提交"); return }
        if (ssoSubmitted) { onStatus("统一认证已经提交，不再刷新或重试验证码；请核对学校结果"); return }
        captchaFailure = null; captcha.onResume(); captcha.retry()
    }

    fun onPageStarted(url: String) {
        if (closed) return
        SessionChecker.invalidateAuthentication(LibraryRoute.WEBVPN)
        if (restartPending) {
            fail("等待重新开始期间网页已由其它操作切换，已停止自动登录", "vpn_login_required", "expired", pause = null)
        }
        documentSerial++
        epoch++; loginTap.cancel(); loading = true; probing = false; preparing = false; prepared = false
        pageReady = false; finishedUrl = null
        captchaFailure = null; captchaReady = false
        captcha.onPageStarted(url)
    }

    fun onPageFinished(url: String) {
        if (closed || url != view.url) return
        loading = false
        if (!pageReady || finishedUrl != url) captcha.onPageFinished(url)
        pageReady = true
        finishedUrl = url
        if (!active) return
        dispatchReadyPage(url)
    }

    private fun dispatchReadyPage(url: String) {
        if (!pageReady || loading || finishedUrl != url || url != view.url || !active || dispatchedEpoch == epoch) return
        if (!withinDeadline() || restartPending) return
        // Duplicate finish callbacks must not cancel an in-flight preparation or POST.
        dispatchedEpoch = epoch
        when {
            CaptchaPagePolicy.isTrusted(url) -> {
                if (ssoSubmitted && documentSerial > ssoSubmittedDocument) inspectReturnedSsoPage()
                else if (ssoSubmitted) verifyLater(epoch, Kind.SSO)
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

    private fun live(id: Long) = !closed && !stopped && active && id == epoch && withinDeadline()

    /** Only a newly loaded document after our POST can prove this attempt was rejected. */
    private fun inspectReturnedSsoPage() {
        if (!ssoSubmitted || librarySubmitted || documentSerial <= ssoSubmittedDocument || !withinDeadline()) return
        val id = epoch
        captcha.onPause()
        onStatus("统一认证返回登录页，正在只读核对学校的明确拒绝原因…")
        evaluate(ssoScript, id) {
            // Read the outcome and arm event-only manual-interaction protection in
            // the same JavaScript task, so no user event can slip between them.
            evaluate("JSON.stringify((() => { const h = window.SchoolSsoAutoLogin; const r = h?.outcome() || {kind:'unknown'}; return r.kind === 'captcha-rejected' ? {...r, restartGuard:h.armCaptchaRestart().kind} : r; })())", id) { result ->
                if (!ssoSubmitted || librarySubmitted || documentSerial <= ssoSubmittedDocument) return@evaluate
                when (result.optString("kind")) {
                    "captcha-rejected" -> {
                        // This POST has a known rejection. Never retry the same form/challenge.
                        ssoSubmitted = false
                        ssoCallSerial++
                        prepared = false; captchaReady = false; captchaFailure = null
                        if (result.optString("restartGuard") !in setOf("armed", "ready")) {
                            fail("学校已拒绝验证码，但检测到手动操作或页面变化，已停止自动重新开始", "vpn_login_required", "expired", pause = null)
                        } else if (!ocrEnabled || !sso.enabled || sso.paused || sso.revision != ssoVersion) {
                            fail("验证码已被学校拒绝，但识别或账号设置已改变，已停止自动重新开始", "vpn_login_required", "expired", pause = null)
                        } else if (freshRestarts >= 2 || ssoAttempts >= 3 || loginTapAttempts >= 3) {
                            fail("学校连续拒绝验证码，已达到本轮两次自动重新开始上限；未将密码标记为错误，请手动核对", "vpn_login_required", "expired", pause = null)
                        } else {
                            freshRestarts++
                            restartGuardMethod = "captchaRestartState"
                            restartPending = true
                            restartAtElapsed = SystemClock.elapsedRealtime() + 1_500L
                            onStatus("学校明确提示验证码无效，将自动获取新登录页面重新开始（$freshRestarts/2）；不会重复提交旧表单")
                            scheduleFreshRestart()
                        }
                    }
                    "other-rejected" -> fail("学校拒绝统一认证，可能涉及账号密码、锁定、访问限制或额外验证；已停止自动重试，请核对学校页面", "vpn_login_required", "expired", Kind.SSO)
                    else -> fail("统一认证返回登录页，但没有可确认的验证码拒绝结果；已停止，不重复提交，请核对学校页面", "vpn_login_required", "expired", Kind.SSO)
                }
            }
        }
    }

    private fun scheduleFreshRestart() {
        if (!restartPending || !active || !withinDeadline()) return
        val id = epoch
        main.postDelayed({
            if (!live(id) || !restartPending) return@postDelayed
            if (!ocrEnabled || !sso.enabled || sso.paused || sso.revision != ssoVersion || librarySubmitted) {
                fail("识别或账号设置已改变，已取消统一认证自动重新开始", "vpn_login_required", "expired", pause = null)
                return@postDelayed
            }
            evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.$restartGuardMethod() || {kind:'unknown'})", id) { guard ->
                if (!restartPending) return@evaluate
                if (guard.optString("kind") != "ready") {
                    fail("检测到手动操作或登录页面变化，已取消自动重新开始；保留当前页面", "vpn_login_required", "expired", pause = null)
                    return@evaluate
                }
                if (!ocrEnabled || !sso.enabled || sso.paused || sso.revision != ssoVersion || librarySubmitted) {
                    fail("识别或账号设置已改变，已取消统一认证自动重新开始", "vpn_login_required", "expired", pause = null)
                    return@evaluate
                }
                restartPending = false
                portalRedirected = false
                ssoConfirmed = false
                navigate(LibraryRoute.WEBVPN.url("/login"))
                // navigate() invalidates the rejected document before OCR resumes.
                if (active && !closed && !stopped) captcha.onResume()
            }
        }, maxOf(0L, restartAtElapsed - SystemClock.elapsedRealtime()))
    }

    private fun prepareSso() {
        if (preparing || restartPending || !CaptchaPagePolicy.isTrusted(view.url) || !withinDeadline()) return
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
        if (!withinDeadline() || restartPending) return
        if (!sso.enabled || sso.paused || sso.revision != ssoVersion) { fail("统一认证账号设置已改变，已停止", "vpn_login_required"); return }
        if (ssoAttempts >= 3) { fail("统一认证已达到本轮三次提交上限，已停止", "vpn_login_required", "expired", pause = null); return }
        val id = epoch
        // Lock before preparing a one-shot input sequence. Unknown or delayed callbacks
        // must never fall back to requestSubmit(), JavaScript click(), or another touch.
        ssoSubmitted = true
        ssoSubmittedDocument = documentSerial
        ssoAttempts++
        val call = ++ssoCallSerial
        // Once the credential POST may start, OCR must not refresh another challenge.
        captcha.onPause()
        onStatus("正在核对学校原登录按钮及当前验证码，准备一次登录触控…")
        // Arm observation BEFORE evaluateJavascript: navigation can discard its callback.
        verifyLater(id, Kind.SSO, call)
        SessionChecker.invalidateAuthentication(LibraryRoute.WEBVPN)
        evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.prepareActivation() || {kind:'stopped'})", id) { result ->
            if (result.optString("kind") == "activation-ready") {
                activateSsoButton(id, call)
            } else {
                if (result.optString("kind") == "waiting") {
                    ssoSubmitted = false
                    ssoAttempts-- // Proven pre-submit waits do not consume a submission round.
                    ssoCallSerial++ // Waiting is proven pre-submit; discard this call's watchdog.
                    if (result.optString("detailCode") == "school-scripts-loading") {
                        onStatus("账号及验证码已就绪，等待学校密码加密脚本加载后自动提交…")
                        main.postDelayed({ if (live(id) && !ssoSubmitted) submitSso() }, 700L)
                    } else {
                        captchaReady = false
                        captcha.onResume()
                        onStatus("验证码已更新，等待当前图片重新识别")
                    }
                } else if (result.optString("kind") in setOf("blocked", "manual")) {
                    ssoSubmitted = false
                    fail(when (result.optString("detailCode")) {
                        "school-validation-rejected" -> "学校页面本地校验未通过，未调用登录表单提交；请核对页面提示"
                        "browser-validation-rejected" -> "浏览器表单校验未通过，未调用登录表单提交；请核对必填项"
                        "form-changed-before-submit" -> "校验后登录表单或目标发生变化，未调用登录表单提交"
                        "user-edited" -> "检测到手动填写或账号变化，未触发学校登录按钮；请手动继续"
                        else -> "统一认证表单未能启动，已停止；请核对学校页面"
                    }, "vpn_login_required", "expired", pause = null)
                } else fail("统一认证未能安全提交，已停止；请在网页核对", "vpn_login_required", "expired", Kind.SSO)
            }
        }
    }

    private fun activationCurrent(id: Long, call: Long, url: String) =
        live(id) && ssoSubmitted && call == ssoCallSerial && prepared && !loading && pageReady &&
            finishedUrl == url && view.url == url && CaptchaPagePolicy.isTrusted(url) && ocrEnabled &&
            sso.enabled && !sso.paused && sso.revision == ssoVersion

    /** The JavaScript stage only verifies and locates the school's original button.
     * Its original click handler, including school validation/RSA, runs once through
     * WebView input. No native or helper code invokes that school validator first. */
    private fun activateSsoButton(id: Long, call: Long) {
        val url = view.url.orEmpty()
        // Let scrollIntoView settle before measuring. This is not a second login attempt.
        main.postDelayed({
            if (!live(id) || !ssoSubmitted || call != ssoCallSerial) return@postDelayed
            if (!activationCurrent(id, call, url)) {
                ssoSubmitted = false
                fail("登录页面或账号设置在触控前发生变化，未触发学校登录按钮", "vpn_login_required", "expired", pause = null)
                return@postDelayed
            }
            evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.activationPoint() || {kind:'stopped'})", id) { point ->
                val x = point.optDouble("x", Double.NaN)
                val y = point.optDouble("y", Double.NaN)
                val width = point.optDouble("viewportWidth", Double.NaN)
                val height = point.optDouble("viewportHeight", Double.NaN)
                if (point.optString("kind") != "ready" || !activationCurrent(id, call, url) ||
                    !x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() ||
                    width <= 0.0 || height <= 0.0 || x <= 0.0 || y <= 0.0 || x >= width || y >= height ||
                    view.width <= 0 || view.height <= 0) {
                    ssoSubmitted = false
                    fail("原登录按钮定位或当前表单校验未通过，未发送登录触控；请核对页面", "vpn_login_required", "expired", pause = null)
                    return@evaluate
                }
                if (loginTapAttempts >= 3) {
                    // Reaching this point proves that no gesture ever produced a click or a
                    // submission, so the saved credential is not implicated. Report the
                    // shared limit without pausing the account, as the documented rule for
                    // exhausted proven-unsubmitted recovery requires.
                    fail("统一认证登录按钮已尝试三次，均未观察到点击或提交，已停止；未判定账号错误，请核对学校页面",
                        "vpn_login_required", "expired", pause = null)
                    return@evaluate
                }
                loginTapAttempts++
                onStatus("正在尝试学校原登录按钮（$loginTapAttempts/3）…")
                loginTap.tap(WebViewLoginTap.Target(x, y, width, height),
                    isCurrent = { activationCurrent(id, call, url) }) { dispatched ->
                    if (!live(id) || !ssoSubmitted || call != ssoCallSerial) return@tap
                    val failureCode = loginTap.lastFailureCode
                    val observe = {
                        if (activationCurrent(id, call, url)) observeLoginTap(id, call, url, failureCode)
                    }
                    // A false Android dispatch result is not proof that the web page
                    // received no click. Inspect DOM/session before deciding to retry.
                    if (dispatched) observe() else {
                        onStatus("登录触控未获完整确认（$failureCode）；正在检查网页事件和学校登录状态…")
                        main.postDelayed({ observe() }, 300L)
                    }
                }
            }
        }, 100L)
    }

    private fun observeLoginTap(id: Long, call: Long, url: String, failureCode: String) {
        evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.activationOutcome() || {kind:'unknown'})", id) { outcome ->
            if (!activationCurrent(id, call, url)) return@evaluate
            when (outcome.optString("detailCode")) {
                // No click and no submission were observed. The guard may have closed
                // because our own point missed the button, which is not a submission.
                "activation-unobserved", "activation-blocked" ->
                    checkBeforePageRecovery(id, call, url, failureCode, outcome)
                "original-submit-observed" -> onStatus("已通过学校原登录按钮触发表单，正在等待学校确认登录（不代表成功）")
                "original-click-observed" -> onStatus("已观察到学校原登录按钮点击，正在只读核对登录结果；不重复点击")
                else -> onStatus("网页事件结果不明确，正在核对学校登录状态；不盲目连点")
            }
        }
    }

    private fun checkBeforePageRecovery(id: Long, call: Long, url: String, failureCode: String, outcome: JSONObject) {
        if (!activationCurrent(id, call, url) || probing) return
        probing = true
        CookieManager.getInstance().flush()
        probe { result ->
            if (!live(id) || call != ssoCallSerial) return@probe
            probing = false
            if (!activationCurrent(id, call, url)) {
                fail("账号设置或页面已改变，已取消后续登录点击", "vpn_login_required", pause = null)
                return@probe
            }
            when {
                result.vpnAuthState == "valid" && result.state in setOf("valid", "expired") -> {
                    ssoConfirmed = true
                    if (sso.revision == ssoVersion && sso.enabled) sso.markVerified()
                    if (result.state == "valid") {
                        stopped = true
                        onResult(result.copy(detail = "学校验证两层登录有效，已停止后续点击"))
                    } else navigate(LibraryRoute.WEBVPN.url("/login"))
                }
                result.networkFailure -> fail("未观察到有效登录事件；${result.detail}；已停止，未判定密码错误", result.state, result.vpnAuthState, pause = null)
                // A failed read-only probe is NOT proof of either login failure or a
                // credential POST. Retire the still-unsubmitted DOM atomically first.
                result.state == "unknown" || (result.state == "vpn_login_required" && result.vpnAuthState == "expired") ->
                    retryActivationLocally(id, call, url, failureCode, outcome, result)
                else -> fail("未观察到有效登录事件；${result.detail}；已停止自动恢复", result.state, result.vpnAuthState, pause = null)
            }
        }
    }

    /**
     * The read-only probe proved this document still carries no school session. Before
     * abandoning it, try the two bounded local recoveries in order: re-arm the same
     * one-shot window and touch once more (re-arming re-measures the button, so a widget
     * that appeared over the previous point no longer hides it), then fall back to the
     * school's own inline handler plus a standard submitter. Both are used at most once
     * per submission round, on the same document, and only for verdicts that prove no
     * click and no submission ever reached the school.
     */
    private fun retryActivationLocally(id: Long, call: Long, url: String, failureCode: String, outcome: JSONObject, result: SessionChecker.Result) {
        if (!activationCurrent(id, call, url)) return
        val verdict = outcome.optString("detailCode")
        val reason = outcome.optString("reason")
        val provenMiss = verdict == "activation-unobserved" ||
            (verdict == "activation-blocked" && reason in setOf("point-miss", "click-off-target"))
        if (!provenMiss) {
            // Fields, CAPTCHA, target or a manual event changed. Never re-drive this
            // guarded document; the conservative fresh-restart path owns the decision.
            restartUnsubmittedPage(id, call, url, failureCode, result)
            return
        }
        if (!ocrEnabled || !sso.enabled || sso.paused || sso.revision != ssoVersion) {
            fail("识别或账号设置已改变，已取消统一认证自动恢复", "vpn_login_required", "expired", pause = null)
            return
        }
        if (ssoRearmedCall != call) {
            ssoRearmedCall = call
            evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.rearmActivation() || {kind:'blocked'})", id) { rearm ->
                if (!activationCurrent(id, call, url)) return@evaluate
                if (rearm.optString("kind") == "activation-ready") {
                    onStatus("登录触控未产生任何网页事件，正在同一页面重新定位学校原登录按钮（1/1）…")
                    activateSsoButton(id, call)
                } else {
                    schoolHandlerFallback(id, call, url, failureCode, result)
                }
            }
            return
        }
        schoolHandlerFallback(id, call, url, failureCode, result)
    }

    /**
     * The last-resort engine. It still runs the school's own validator/RSA through the
     * button's original inline handler, so it is not a re-implementation of the school
     * login; it is only used when the native gesture provably produced no event at all.
     */
    private fun schoolHandlerFallback(id: Long, call: Long, url: String, failureCode: String, result: SessionChecker.Result) {
        if (!activationCurrent(id, call, url)) return
        if (ssoFallbackCall == call) {
            restartUnsubmittedPage(id, call, url, failureCode, result)
            return
        }
        ssoFallbackCall = call
        onStatus("原生触控未触发学校按钮，正在改用学校自身校验处理器提交一次（不代表成功）…")
        evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.schoolHandlerSubmit() || {kind:'stopped'})", id) { answer ->
            if (!activationCurrent(id, call, url)) return@evaluate
            when (answer.optString("kind")) {
                "submitted" -> {
                    onStatus("已改用学校自身校验处理器提交统一认证，正在等待学校确认登录（不代表成功）")
                    verifyLater(id, Kind.SSO, call)
                }
                "blocked" -> when (answer.optString("detailCode")) {
                    // Nothing was sent by any of these, so the document is still an
                    // unsubmitted one and the conservative restart path may continue.
                    "school-handler-unavailable", "browser-validation-rejected", "school-validation-rejected" ->
                        restartUnsubmittedPage(id, call, url, failureCode, result)
                    else -> fail("未能安全改用学校自身处理器提交（${answer.optString("detailCode")}）；已停止，不重复提交，请核对学校页面",
                        "vpn_login_required", "expired", Kind.SSO)
                }
                else -> fail("学校自身处理器提交结果不明；已停止，不重复提交，请核对学校页面", "vpn_login_required", "expired", Kind.SSO)
            }
        }
    }

    private fun restartUnsubmittedPage(id: Long, call: Long, url: String, failureCode: String, result: SessionChecker.Result) {
        if (!activationCurrent(id, call, url)) return
        evaluate("JSON.stringify(window.SchoolSsoAutoLogin?.abandonUnsubmittedActivation() || {kind:'blocked'})", id) { guard ->
            if (!activationCurrent(id, call, url)) return@evaluate
            if (guard.optString("kind") != "ready") {
                fail("页面已有事件、手动操作、验证码变化或学校反馈，不能确认未提交；已停止重新开始（$failureCode）", result.state, result.vpnAuthState, Kind.SSO)
                return@evaluate
            }
            // Proven no submission and the old DOM is now closed to late input.
            ssoSubmitted = false
            ssoCallSerial++
            loginTap.cancel()
            prepared = false; captchaReady = false; captchaFailure = null
            if (freshRestarts >= 2 || ssoAttempts >= 3 || loginTapAttempts >= 3) {
                fail("已达到本轮三次登录尝试／两次重新开始上限；未观察到提交，未判定密码错误。检查结果：${result.detail}", result.state, result.vpnAuthState, pause = null)
                return@evaluate
            }
            freshRestarts++
            restartGuardMethod = "unsubmittedRestartState"
            restartPending = true
            restartAtElapsed = SystemClock.elapsedRealtime() + 1_500L
            onStatus("旧页面未触发登录，已封闭旧按钮；1.5 秒后用已保存账号重新开始（$freshRestarts/2）。检查结果：${result.detail}")
            scheduleFreshRestart()
        }
    }

    private fun checkLibrary() {
        if (probing || stopped || !active || !withinDeadline()) return
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
        if (preparing || librarySubmitted || LibraryRoute.WEBVPN.pathOf(view.url.orEmpty()) != "/login" || !withinDeadline()) return
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
            SessionChecker.invalidateAuthentication(LibraryRoute.WEBVPN)
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

    private fun verifyLater(id: Long, kind: Kind, call: Long = ssoCallSerial) {
        main.postDelayed({
            if (!live(id) || loading) return@postDelayed
            if (kind == Kind.SSO && (!ssoSubmitted || call != ssoCallSerial)) return@postDelayed
            if (LibraryRoute.WEBVPN.pathOf(view.url.orEmpty()) != null) checkLibrary()
            else if (kind == Kind.SSO) checkSsoOutcome(id)
        }, 12_000L)
    }

    private fun checkSsoOutcome(id: Long) {
        if (probing || !live(id) || !ssoSubmitted) return
        probing = true
        CookieManager.getInstance().flush()
        probe { result ->
            if (!live(id)) return@probe
            probing = false
            if (result.vpnAuthState == "valid" && result.state in setOf("valid", "expired")) {
                ssoConfirmed = true
                if (sso.revision == ssoVersion && sso.enabled) sso.markVerified()
                if (result.state == "valid") {
                    stopped = true
                    onResult(result.copy(detail = "学校验证两层登录有效"))
                } else navigate(LibraryRoute.WEBVPN.url("/login"))
            } else fail("统一认证调用后仍未验证通过：${result.detail}；已停止，不重复提交，请核对学校页面", result.state, result.vpnAuthState, Kind.SSO)
        }
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
        restartPending = false
        loginTap.cancel()
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
        closed = true; epoch++; loginTap.close(); main.removeCallbacksAndMessages(null); captcha.close()
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
