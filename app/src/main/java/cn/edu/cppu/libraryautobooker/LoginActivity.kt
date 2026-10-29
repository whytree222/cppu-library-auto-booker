package cn.edu.cppu.libraryautobooker

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.edu.cppu.libraryautobooker.network.LibraryRoute

/** WebVPN has a native form; the direct library's manual web login stays available. */
class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private var nativeForm: WebVpnNativeForm? = null
    private var interactiveLogin: AutoCloseable? = null
    private val route get() = if (intent.getBooleanExtra(EXTRA_WEBVPN, false)) LibraryRoute.WEBVPN else LibraryRoute.DIRECT

    @SuppressLint("SetJavaScriptEnabled")
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
        if (route == LibraryRoute.WEBVPN) {
            nativeForm = WebVpnNativeForm(this, onVerified = { finishLogin(true) }, autoStart = false)
            interactiveLogin = WebVpnAutoLogin.acquireInteractiveLogin { ready ->
                if (!isFinishing && !isDestroyed) nativeForm?.authenticationReady(ready)
            }
            container.addView(nativeForm!!.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            container.addView(Button(this).apply { text = "返回应用"; setOnClickListener { verifyLogin(true) } })
            webView = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        LibraryRoute.DIRECT.pathOf(request.url.toString()) == null
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        SessionChecker.invalidateAuthentication(LibraryRoute.DIRECT)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (url != view.url || LibraryRoute.DIRECT.pathOf(url) == null) return
                        if (LibraryRoute.DIRECT.pathOf(url) == "/login") view.evaluateJavascript(
                            "document.querySelector('form#fromuser input#url')?.setAttribute('value','selectreadingroom')", null)
                        verifyLogin(false)
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
                }
            }
            container.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            webView.loadUrl(LibraryRoute.DIRECT.url("/login"))
        }
        setContentView(container)
        ViewCompat.requestApplyInsets(container)
    }

    private fun verifyLogin(returnOnFailure: Boolean) {
        CookieManager.getInstance().flush()
        SessionChecker.probe(this, route) { result ->
            if (!isFinishing && !isDestroyed && result.state == "valid") finishLogin(true)
            else if (!isFinishing && !isDestroyed && returnOnFailure) finishLogin(false)
        }
    }
    private fun finishLogin(verified: Boolean) {
        CookieManager.getInstance().flush()
        setResult(if (verified) RESULT_OK else RESULT_CANCELED)
        finish()
    }
    override fun onDestroy() {
        val retiringForm = nativeForm
        val retiringLease = interactiveLogin
        nativeForm = null; interactiveLogin = null
        if (retiringForm != null) retiringForm.closeWhenIdle { retiringLease?.close() }
        else retiringLease?.close()
        if (::webView.isInitialized) webView.destroy()
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
    companion object { const val EXTRA_WEBVPN = "webvpn" }
}
