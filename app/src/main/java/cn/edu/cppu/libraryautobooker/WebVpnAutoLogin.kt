package cn.edu.cppu.libraryautobooker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

/** Single shared recovery run, callable by the foreground app or its scheduled foreground service. */
object WebVpnAutoLogin {
    private var run: Run? = null
    val running get() = run != null

    fun canRecover(context: Context, result: SessionChecker.Result): Boolean {
        val kind = if (result.state == "vpn_login_required") Kind.SSO
            else if (result.state == "expired" && result.vpnAuthState == "valid") Kind.LIBRARY else return false
        val store = WebVpnCredentialStore(context, kind)
        return store.enabled && !store.paused && (kind != Kind.SSO ||
            context.getSharedPreferences("webvpn_ocr", Context.MODE_PRIVATE).getBoolean("enabled", true))
    }

    /** Closing one subscription never cancels another caller's login or delivers a failure callback. */
    fun ensure(context: Context, callback: (SessionChecker.Result) -> Unit): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        val existing = run
        val target = existing ?: Run(context.applicationContext).also { run = it }
        val listener = Listener(callback)
        target.listeners.add(listener)
        val subscription = AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!listener.cancelled) {
                listener.cancelled = true
                target.unsubscribe(listener)
            }
        }
        if (existing == null) target.start()
        return subscription
    }

    fun cancel() {
        run?.finish(SessionChecker.Result("unknown", "自动 WebVPN 登录已取消，可手动继续"))
    }

    private class Listener(val callback: (SessionChecker.Result) -> Unit, var cancelled: Boolean = false)

    private class Run(private val app: Context) {
        val listeners = linkedSetOf<Listener>()
        private var view: WebView? = null
        private var driver: WebVpnLoginDriver? = null
        private var done = false
        private val handler = Handler(Looper.getMainLooper())

        @SuppressLint("SetJavaScriptEnabled")
        fun start() {
            RuntimeStore(app).session("logging_in", "正在恢复 WebVPN：先统一认证，再登录图书馆…")
            CookieManager.getInstance().setAcceptCookie(true)
            val web = WebView(app)
            view = web
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!WebVpnLoginDriver.allowsNavigation(request.url.toString())) {
                        driver?.fail("认证跳转到未授权地址，已停止，未发送更多登录信息")
                        return true
                    }
                    return false
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { driver?.onPageStarted(url) }
                override fun onPageFinished(view: WebView, url: String) { if (!done) driver?.onPageFinished(url) }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) driver?.fail("WebVPN 登录页面连接失败（${error.errorCode}）；结果不明时不重发")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) driver?.fail("WebVPN 登录返回 HTTP ${response.statusCode}；已停止自动重试")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); driver?.fail("WebVPN 证书校验失败，已停止；不会绕过证书检查")
                }
            }
            driver = WebVpnLoginDriver(app, web, onStatus = {
                if (!done) RuntimeStore(app).session("logging_in", it)
            }, onResult = ::finish)
            // Like the reservation renderer, give the off-screen WebView an actual layout.
            // This is an explicitly opted-in login run; no Activity or lockscreen is opened.
            val metrics = app.resources.displayMetrics
            web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            driver?.onResume()
            web.loadUrl(LibraryRoute.WEBVPN.url("/login"))
            handler.postDelayed({ finish(SessionChecker.Result("unknown", "WebVPN 自动登录超时；未继续预约，请核对登录状态")) }, 95_000L)
        }

        fun finish(result: SessionChecker.Result) {
            if (done) return
            done = true
            dispose()
            RuntimeStore(app).session(result.state, result.detail)
            RuntimeStore(app).prefs.edit().putString("session_route", "WEBVPN").apply()
            if (result.state == "valid") RuntimeStore(app).prefs.edit().putString("active_route", "WEBVPN").apply()
            LoginStatusStore(app).record(LibraryRoute.WEBVPN, result)
            if (run === this) run = null
            val pending = listeners.toList(); listeners.clear()
            pending.forEach { if (!it.cancelled) it.callback(result) }
        }

        fun unsubscribe(listener: Listener) {
            if (done || !listeners.remove(listener) || listeners.isNotEmpty()) return
            // No task still needs this hidden WebView. Stop it without reporting a synthetic
            // authentication failure to callers that have explicitly released their requests.
            done = true
            dispose()
            if (run === this) {
                run = null
                val runtime = RuntimeStore(app)
                if (runtime.prefs.getString("session_state", "") == "logging_in")
                    runtime.session("unknown", "WebVPN 自动登录已停止；登录结果尚未确认")
            }
        }

        private fun dispose() {
            handler.removeCallbacksAndMessages(null)
            CookieManager.getInstance().flush()
            driver?.close(); driver = null
            view?.stopLoading(); view?.destroy(); view = null
        }
    }
}
