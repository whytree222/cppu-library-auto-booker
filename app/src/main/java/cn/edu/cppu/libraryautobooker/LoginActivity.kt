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

class LoginActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(Button(this).apply {
            text = "返回应用"
            setOnClickListener { finishLogin() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val uri = Uri.parse(url)
                    if (uri.host != "mlib.cppu.edu.cn") return
                    if (uri.path?.trimEnd('/') == "/login") {
                        view.evaluateJavascript(
                            "document.querySelector('form#fromuser input#url')?.setAttribute('value', 'multireadingroomtablelist')",
                            null
                        )
                    } else {
                        view.evaluateJavascript(
                            "document.querySelector('input[type=password], form#fromuser input#passwd') === null"
                        ) { noLoginForm ->
                            if (noLoginForm == "true" && !isFinishing) finishLogin()
                        }
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("http://mlib.cppu.edu.cn/login")
        }
        container.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(container)
    }

    private fun finishLogin() {
        CookieManager.getInstance().flush()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}
