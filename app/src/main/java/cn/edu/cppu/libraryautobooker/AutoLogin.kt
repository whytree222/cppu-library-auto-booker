package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.edu.cppu.libraryautobooker.data.CredentialStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.json.JSONObject

/** All callers share one login request and the app's WebView cookie store. */
object AutoLogin {
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var serial = 0
    private val callbacks = mutableListOf<(Boolean, String) -> Unit>()
    val running get() = view != null

    fun cancel(context: Context) {
        if (!running) return
        serial++
        val old = view; view = null
        old?.stopLoading(); old?.destroy()
        RuntimeStore(context).session("expired", "自动登录已取消")
        val pending = callbacks.toList(); callbacks.clear()
        pending.forEach { it(false, "自动登录已取消") }
    }

    fun login(context: Context, force: Boolean = false, callback: (Boolean, String) -> Unit = { _, _ -> }) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (running) { callbacks.add(callback); return }
        val app = context.applicationContext
        val store = CredentialStore(app)
        if (!store.enabled || (store.paused && !force)) {
            callback(false, if (store.paused) "自动登录已暂停，请检查账号密码并点击重新登录" else "未开启自动登录，请先保存账号密码或手动登录")
            return
        }
        val credentials = try { store.read() } catch (_: Exception) {
            store.pause(); callback(false, "无法读取保存的登录信息，请重新填写保存"); return
        } ?: run { callback(false, "没有可用的登录信息，请重新填写"); return }
        if (force) store.resume()
        beginLogin(app, credentials, automatic = true, callback = callback)
    }

    /** One-off form login. The caller decides whether to encrypt credentials AFTER verification. */
    fun loginWithCredentials(context: Context, username: String, password: String, callback: (Boolean, String) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (running) { callback(false, "已有登录正在进行，请稍后重试"); return }
        val error = LoginInput.error(username, password)
        if (error != null) { callback(false, error); return }
        beginLogin(context.applicationContext, CredentialStore.Credentials(username.trim(), password),
            automatic = false, callback = callback)
    }

    private fun beginLogin(app: Context, credentials: CredentialStore.Credentials, automatic: Boolean,
                           callback: (Boolean, String) -> Unit) {
        val store = CredentialStore(app)
        callbacks.add(callback)
        val token = ++serial
        var submitted = false
        var verifying = false
        fun finish(ok: Boolean, detail: String, pause: Boolean = false, unknown: Boolean = false) {
            if (token != serial || view == null) return
            serial++
            if (pause && automatic) store.pause()
            RuntimeStore(app).session(if (ok) "valid" else if (unknown) "unknown" else "expired", detail)
            CookieManager.getInstance().flush()
            val old = view
            view = null
            old?.stopLoading(); old?.destroy()
            val pending = callbacks.toList(); callbacks.clear()
            pending.forEach { it(ok, detail) }
        }
        RuntimeStore(app).session("logging_in", if (automatic) "登录已失效，正在自动重新登录…" else "正在登录学校系统…")
        CookieManager.getInstance().setAcceptCookie(true)
        view = WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = request.url.scheme == "http" && request.url.host == "mlib.cppu.edu.cn" && request.url.port == -1
                    if (!allowed) finish(false, "登录页面跳转到其他站点，已停止自动登录", true)
                    return !allowed
                }
                override fun onPageFinished(web: WebView, url: String) {
                    if (token != serial) return
                    val uri = Uri.parse(url)
                    if (uri.scheme != "http" || uri.host != "mlib.cppu.edu.cn" || uri.port != -1) {
                        finish(false, "登录页面地址不符，已停止", true); return
                    }
                    if (!submitted) {
                        submitted = true
                        val json = JSONObject().put("username", credentials.username).put("password", credentials.password).toString()
                        val script = app.assets.open("login.js").bufferedReader().use { it.readText() }.replace("__LOGIN_CREDENTIALS__", json)
                        web.evaluateJavascript(script) { result ->
                            if (token != serial) return@evaluateJavascript
                            if (result != "\"submitted\"") finish(false, "学校登录页面不支持自动填写，或需要验证码；请手动登录", true)
                        }
                    } else if (!verifying) {
                        verifying = true
                        CookieManager.getInstance().flush()
                        SessionChecker.checkDetailed(app) { state ->
                            if (token != serial) return@checkDetailed
                            finish(state == "valid", when (state) {
                                "valid" -> if (automatic) "自动重新登录成功（学校系统验证通过）" else "登录成功（学校系统验证通过）"
                                "expired" -> if (automatic) "自动登录失败：学校未接受登录，请核对账号密码或手动登录；已暂停自动重试"
                                    else "登录失败：学校未接受登录，请核对账号密码或使用学校网页登录"
                                else -> "登录后无法验证，请检查校园网络并重试"
                            }, state == "expired", state == "unknown")
                        }
                    }
                }
                override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) finish(false, "自动登录无法连接学校系统，请检查校园网络", unknown = true)
                }
            }
            loadUrl("http://mlib.cppu.edu.cn/login")
        }
        main.postDelayed({ finish(false, "自动登录超时，请检查校园网络或手动登录", unknown = true) }, 30_000L)
    }
}

object SessionCoordinator {
    fun check(context: Context, callback: (Boolean, String) -> Unit = { _, _ -> }): AutoCloseable {
        return checkPreferred(context) { result -> callback(result.state == "valid", result.detail) }
    }

    private fun checkPreferred(context: Context, callback: (SessionChecker.Result) -> Unit): AutoCloseable {
        val request = CheckRequest()
        request.attach(checkRoute(context, LibraryRoute.DIRECT) { result ->
            if (!request.active) return@checkRoute
            if (result.networkFailure && ConfigStore(context).load().webVpnBackup) {
                request.attach(checkRoute(context, LibraryRoute.WEBVPN) { recovered ->
                    request.deliver { callback(recovered) }
                })
            } else request.deliver { callback(result) }
        })
        return request
    }

    fun checkState(context: Context, callback: (String) -> Unit): AutoCloseable {
        return checkPreferred(context) { callback(it.state) }
    }

    fun checkRoute(context: Context, route: LibraryRoute, updateSession: Boolean = true,
                   callback: (SessionChecker.Result) -> Unit): AutoCloseable {
        val request = CheckRequest()
        SessionChecker.probe(context, route, updateSession = updateSession) { result ->
            // The read-only probe may already be in flight. Cancellation still prevents it
            // from starting a credential-bearing login or waking a stopped reservation task.
            if (!request.active) return@probe
            if (route == LibraryRoute.DIRECT && result.state == "expired" && CredentialStore(context).enabled) {
                AutoLogin.login(context) { valid, detail ->
                    request.deliver {
                        callback(SessionChecker.Result(if (valid) "valid" else RuntimeStore(context).prefs.getString("session_state", "unknown").orEmpty(), detail))
                    }
                }
            } else if (route == LibraryRoute.WEBVPN) handleWebVpnCheck(context, result, updateSession, request, callback)
            else request.deliver { callback(result) }
        }
        return request
    }

    internal fun handleWebVpnCheck(context: Context, result: SessionChecker.Result, updateSession: Boolean,
        request: CheckRequest, callback: (SessionChecker.Result) -> Unit,
        ensure: ((SessionChecker.Result) -> Unit) -> AutoCloseable = { listener ->
            WebVpnAutoLogin.ensure(context, updateSession = updateSession, callback = listener)
        }) {
        if (!request.active) return
        if (WebVpnAutoLogin.canRecover(context, result, statusOnly = !updateSession)) {
            request.attach(ensure { recovered -> request.deliver { callback(recovered) } })
        } else request.deliver {
            val reason = WebVpnAutoLogin.recoveryUnavailableReason(context, result)
            val explained = if (reason == null) result else result.copy(detail = "${result.detail}；$reason")
            if (reason != null) cn.edu.cppu.libraryautobooker.data.LoginStatusStore(context).record(LibraryRoute.WEBVPN, explained)
            callback(explained)
        }
    }

    /** Status cards must use recovery too, without replacing the preferred booking route. */
    fun checkWebVpnStatus(context: Context, callback: (SessionChecker.Result) -> Unit = {}): AutoCloseable {
        // Reservation-owned checks decide recovery once a task starts. A status-card
        // refresh must never change authentication cookies underneath an in-flight POST.
        if (cn.edu.cppu.libraryautobooker.booking.BookingService.running ||
            RuntimeStore(context).prefs.getString("task_state", "") in setOf("RUNNING", "TRIGGERED")) {
            val request = CheckRequest()
            SessionChecker.probe(context, LibraryRoute.WEBVPN, updateSession = false) { result ->
                request.deliver { callback(result) }
            }
            return request
        }
        return checkRoute(context, LibraryRoute.WEBVPN, updateSession = false, callback = callback)
    }

    /** Main-thread ownership of an asynchronous probe and its optional shared recovery. */
    internal class CheckRequest : AutoCloseable {
        private var closed = false
        private var completed = false
        private var child: AutoCloseable? = null
        val active get() = !closed && !completed

        fun attach(value: AutoCloseable) {
            if (!active) value.close()
            else {
                child?.close()
                child = value
            }
        }

        fun deliver(action: () -> Unit) {
            if (!active) return
            completed = true
            val previous = child
            child = null
            previous?.close()
            action()
        }

        override fun close() {
            if (closed) return
            closed = true
            val previous = child
            child = null
            previous?.close()
        }
    }
}
