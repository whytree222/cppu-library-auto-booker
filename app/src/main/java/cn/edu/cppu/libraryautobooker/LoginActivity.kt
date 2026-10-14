package cn.edu.cppu.libraryautobooker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.TextView
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.ocr.WebVpnCaptchaController

class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private var captchaController: WebVpnCaptchaController? = null
    private var captchaStatus: TextView? = null
    private val route get() = if (intent.getBooleanExtra("webvpn", false)) LibraryRoute.WEBVPN else LibraryRoute.DIRECT

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (route == LibraryRoute.WEBVPN) container.addView(TextView(this).apply {
            text = "验证码可在本机识别并填写；请自行输入学校统一认证账号和密码、核对验证码并点击学校登录按钮。可在学校页面勾选五天内自动登录，然后登录图书馆。应用不保存统一认证账号或密码，不自动提交登录。"
            setPadding(16, 12, 16, 12)
        })
        if (route == LibraryRoute.WEBVPN) {
            val ocrPreferences = getSharedPreferences("webvpn_ocr", MODE_PRIVATE)
            container.addView(CheckBox(this).apply {
                text = "自动识别并填写验证码"
                isChecked = ocrPreferences.getBoolean("enabled", true)
                setOnCheckedChangeListener { _, enabled ->
                    ocrPreferences.edit().putBoolean("enabled", enabled).apply()
                    captchaController?.setEnabled(enabled)
                }
            })
            captchaStatus = TextView(this).apply {
                text = "待打开统一认证页面；识别仅填写验证码，不会提交登录"
                setPadding(16, 0, 16, 8)
            }
            container.addView(captchaStatus)
            container.addView(Button(this).apply {
                text = "重新识别当前验证码"
                setOnClickListener { captchaController?.retry() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        container.addView(Button(this).apply {
            text = "返回应用"
            setOnClickListener { verifyLogin(returnOnFailure = true) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (route == LibraryRoute.WEBVPN) container.addView(Button(this).apply {
            text = "本地验证码 OCR 试验（导入图片）"
            setOnClickListener { startActivity(Intent(this@LoginActivity, OcrTrialActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    captchaController?.onPageStarted(url)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    captchaController?.onPageFinished(url)
                    val path = route.pathOf(url) ?: return
                    if (path.trimEnd('/') == "/login") {
                        view.evaluateJavascript(
                            "document.querySelector('form#fromuser input#url')?.setAttribute('value', 'selectreadingroom')",
                            null
                        )
                        if (route == LibraryRoute.WEBVPN) verifyLogin()
                    } else {
                        CookieManager.getInstance().flush()
                        verifyLogin()
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }
        if (route == LibraryRoute.WEBVPN) {
            captchaController = WebVpnCaptchaController(this, webView) { status ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) captchaStatus?.text = status
                }
            }.also {
                it.setEnabled(getSharedPreferences("webvpn_ocr", MODE_PRIVATE).getBoolean("enabled", true))
            }
        }
        container.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(container)
        webView.loadUrl(route.url("/login"))
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

    override fun onResume() {
        super.onResume()
        captchaController?.onResume()
    }

    override fun onPause() {
        captchaController?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        captchaController?.close()
        captchaController = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
