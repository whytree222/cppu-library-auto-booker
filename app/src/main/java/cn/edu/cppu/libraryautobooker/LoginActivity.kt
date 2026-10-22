package cn.edu.cppu.libraryautobooker

import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

/** Manual campus-library login only; never opens school unified authentication or a proxy. */
class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var checking = false
    private var returnRequested = false
    private val route = LibraryRoute.DIRECT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        CookieManager.getInstance().setAcceptCookie(true)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        container.addView(Button(this).apply {
            text = "返回应用"; setOnClickListener { returnRequested = true; verifyLogin() }
        })
        status = TextView(this).apply {
            text = "请连接校园网络，在学校原页面登录图书馆。"
            setPadding(12, 8, 12, 8)
        }
        container.addView(status)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    route.pathOf(request.url.toString()) == null
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String) = route.pathOf(url) == null
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    SessionChecker.invalidateAuthentication(route)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url != view.url || route.pathOf(url) == null) return
                    if (route.pathOf(url) == "/login") view.evaluateJavascript(
                        "document.querySelector('form#fromuser input#url')?.setAttribute('value','selectreadingroom')", null)
                    CookieManager.getInstance().flush()
                    verifyLogin()
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) status.text = "图书馆页面连接失败，请检查校园网络。"
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) status.text = "图书馆返回 HTTP ${response.statusCode}，请稍后重试。"
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel(); status.text = "证书验证失败，已停止加载。"
                }
            }
        }
        container.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(container)
        ViewCompat.requestApplyInsets(container)
        webView.loadUrl(route.url("/login"))
    }

    private fun verifyLogin() {
        if (checking || isFinishing || isDestroyed) return
        checking = true
        CookieManager.getInstance().flush()
        SessionChecker.probe(this, route) { result ->
            checking = false
            if (isFinishing || isDestroyed) return@probe
            status.text = result.detail
            if (result.state == "valid" || returnRequested) {
                setResult(if (result.state == "valid") RESULT_OK else RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }
}
