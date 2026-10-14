package cn.edu.cppu.libraryautobooker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private var driver: WebVpnLoginDriver? = null
    private var status: TextView? = null
    private var resumed = false
    private var accountConfigBefore: String? = null
    private val route get() = if (intent.getBooleanExtra("webvpn", false)) LibraryRoute.WEBVPN else LibraryRoute.DIRECT

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (route == LibraryRoute.WEBVPN) WebVpnAutoLogin.cancel()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        CookieManager.getInstance().setAcceptCookie(true)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(Button(this).apply {
            text = "返回应用"; setOnClickListener { verifyLogin(true) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (route == LibraryRoute.WEBVPN) tools.addView(Button(this).apply {
            text = "两套账号设置"
            setOnClickListener {
                accountConfigBefore = accountConfig()
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this@LoginActivity, WebVpnAccountsActivity::class.java), 201)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        if (route == LibraryRoute.WEBVPN) tools.addView(Button(this).apply {
            text = "更多操作"
            setOnClickListener { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(tools)
        if (route == LibraryRoute.WEBVPN) {
            val preferences = getSharedPreferences("webvpn_ocr", MODE_PRIVATE)
            container.addView(CheckBox(this).apply {
                text = "自动识别并填写验证码"
                isChecked = preferences.getBoolean("enabled", true)
                setOnCheckedChangeListener { _, checked ->
                    preferences.edit().putBoolean("enabled", checked).apply()
                    driver?.setOcrEnabled(checked)
                }
            })
            status = TextView(this).apply { text = "正在检查两层登录…"; setPadding(12, 0, 12, 8) }
            container.addView(status)
            details.addView(TextView(this).apply {
                text = "请分别保存学校统一认证学工号/密码与 WebVPN 内图书馆账号/密码。仅本机加密保存，明确开启后才自动登录。可勾选学校页面五天内自动登录；失败后不会连续重发。未配置时可手动登录。"
                setPadding(12, 0, 12, 8)
            })
            details.addView(Button(this).apply {
                text = "使用已保存账号重新开始"
                setOnClickListener { createDriver(); webView.loadUrl(route.url("/login")) }
            })
            details.addView(Button(this).apply {
                text = "重新识别当前验证码"; setOnClickListener { driver?.retryCaptcha() }
            })
            details.addView(Button(this).apply {
                text = "本地验证码 OCR 试验（导入图片）"
                setOnClickListener { startActivity(Intent(this@LoginActivity, OcrTrialActivity::class.java)) }
            })
            container.addView(details)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return if (route == LibraryRoute.WEBVPN) !WebVpnLoginDriver.allowsNavigation(request.url.toString())
                        else route.pathOf(request.url.toString()) == null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { driver?.onPageStarted(url) }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url != view.url) return
                    driver?.onPageFinished(url)
                    val path = route.pathOf(url) ?: return
                    if (path == "/login") view.evaluateJavascript(
                        "document.querySelector('form#fromuser input#url')?.setAttribute('value','selectreadingroom')", null)
                    CookieManager.getInstance().flush()
                    verifyLogin(false)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) driver?.fail("登录页面连接失败（${error.errorCode}），请检查网络；未确认的登录不会自动重发")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) driver?.fail("登录页面返回 HTTP ${response.statusCode}，已停止自动登录")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    driver?.fail("登录页面证书检查失败，已停止；不会绕过证书验证")
                }
            }
        }
        if (route == LibraryRoute.WEBVPN) createDriver()
        container.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(container)
        ViewCompat.requestApplyInsets(container)
        webView.loadUrl(route.url("/login"))
    }
    private fun accountConfig() = WebVpnCredentialStore.Kind.entries.joinToString("|") { kind ->
        val store = WebVpnCredentialStore(this, kind)
        "${store.revision}:${store.enabled}:${store.paused}"
    }
    @Deprecated("Legacy Activity callback required by the existing Activity base class")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201 && accountConfigBefore != null && accountConfigBefore != accountConfig()) {
            createDriver()
            webView.loadUrl(route.url("/login"))
        }
        accountConfigBefore = null
    }

    private fun createDriver() {
        driver?.close()
        driver = WebVpnLoginDriver(this, webView, onStatus = { message ->
            if (!isFinishing && !isDestroyed) status?.text = message
        }, onResult = { result ->
            if (!isFinishing && !isDestroyed) {
                RuntimeStore(this).session(result.state, result.detail)
                RuntimeStore(this).prefs.edit().putString("session_route", "WEBVPN").apply()
                LoginStatusStore(this).record(LibraryRoute.WEBVPN, result)
                status?.text = result.detail
                if (result.state == "valid") finishLogin(true)
            }
        }).also { if (resumed) it.onResume() }
    }
    private fun verifyLogin(returnOnFailure: Boolean) {
        CookieManager.getInstance().flush()
        SessionChecker.probe(this, route) { result ->
            if (!isFinishing && result.state == "valid") finishLogin(true)
            else if (!isFinishing && returnOnFailure) finishLogin(false)
        }
    }
    private fun finishLogin(verified: Boolean) {
        CookieManager.getInstance().flush()
        setResult(if (verified) RESULT_OK else RESULT_CANCELED)
        finish()
    }
    override fun onResume() { super.onResume(); resumed = true; driver?.onResume() }
    override fun onPause() { resumed = false; driver?.onPause(); super.onPause() }
    override fun onDestroy() {
        driver?.close(); driver = null
        CookieManager.getInstance().flush()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
