package cn.edu.cppu.libraryautobooker

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.TextView
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private val route get() = if (intent.getBooleanExtra("webvpn", false)) LibraryRoute.WEBVPN else LibraryRoute.DIRECT

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (route == LibraryRoute.WEBVPN) container.addView(TextView(this).apply {
            text = "请手动完成学校 WebVPN 统一认证和验证码，可在学校页面勾选五天内自动登录，然后登录图书馆。应用仅复用会话，不保存统一认证密码。"
            setPadding(16, 12, 16, 12)
        })
        container.addView(Button(this).apply {
            text = "返回应用"
            setOnClickListener { verifyLogin(returnOnFailure = true) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val allowed = if (route == LibraryRoute.WEBVPN) uri.scheme == "https" &&
                        uri.host.orEmpty().endsWith(".cppu.edu.cn") && uri.userInfo == null && uri.port in listOf(-1, 443)
                        else route.pathOf(uri.toString()) != null
                    return !allowed
                }
                override fun onPageFinished(view: WebView, url: String) {
                    val path = route.pathOf(url) ?: return
                    if (path.trimEnd('/') == "/login") {
                        view.evaluateJavascript(
                            "document.querySelector('form#fromuser input#url')?.setAttribute('value', 'selectreadingroom')",
                            null
                        )
                    } else {
                        CookieManager.getInstance().flush()
                        verifyLogin()
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(route.url("/login"))
        }
        container.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(container)
    }

    private fun verifyLogin(returnOnFailure: Boolean = false) {
        CookieManager.getInstance().flush()
        SessionChecker.probe(this, route) { result ->
            if (!isFinishing && result.state == "valid") finishLogin(true)
            else if (!isFinishing && returnOnFailure) finishLogin(false)
        }
    }

    private fun finishLogin(verified: Boolean = false) {
        CookieManager.getInstance().flush()
        setResult(if (verified) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}

