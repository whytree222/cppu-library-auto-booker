package cn.edu.cppu.libraryautobooker

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.SeatChoice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class SeatPickerActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var captureLayer: View
    private lateinit var message: TextView
    private lateinit var toggle: Button
    private val store by lazy { ConfigStore(this) }
    private var captureEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        message = TextView(this).apply {
            text = "先在网页中进入一楼座位图，然后开启位置录入。"
            setPadding(16, 16, 16, 8)
        }
        root.addView(message)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Button(this).apply {
            text = "开启位置录入"
            setOnClickListener {
                captureEnabled = !captureEnabled
                captureLayer.visibility = if (captureEnabled) View.VISIBLE else View.GONE
                text = if (captureEnabled) "暂停录入 / 滚动网页" else "开启位置录入"
                showSelections()
            }
        }
        controls.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            text = "完成"
            setOnClickListener {
                setResult(RESULT_OK)
                finish()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)

        val frame = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!captureEnabled) showSelections()
                }
            }
            loadUrl("http://mlib.cppu.edu.cn/selectreadingroom")
        }
        frame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        captureLayer = View(this).apply {
            visibility = View.GONE
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    captureAt(event.x / view.width, event.y / view.height)
                }
                true
            }
        }
        frame.addView(captureLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun captureAt(x: Float, y: Float) {
        if (!webView.url.orEmpty().contains("multireadingroomtablelist")) {
            message.text = "请先在网页中进入一楼座位图。"
            return
        }
        val config = store.load()
        if (config.seatChoices.size >= 10) {
            message.text = "最多保存 10 个候选位置，请先清除旧位置。"
            return
        }
        val script = assets.open("capture_seat.js").bufferedReader().use { it.readText() }
            .replace("__X_FRACTION__", String.format(Locale.US, "%.6f", x.coerceIn(0f, 1f)))
            .replace("__Y_FRACTION__", String.format(Locale.US, "%.6f", y.coerceIn(0f, 1f)))
        webView.evaluateJavascript(script) { result ->
            try {
                val json = JSONArray("[$result]").getString(0)
                if (json.isBlank()) {
                    message.text = "未识别到座位，请点座位图标的中央。"
                    return@evaluateJavascript
                }
                val item = JSONObject(json)
                val current = store.load()
                val selector = item.optString("selector")
                if (selector.isBlank()) return@evaluateJavascript
                if (current.seatChoices.any { it.selector == selector }) {
                    message.text = "该位置已经记录。"
                    return@evaluateJavascript
                }
                val choice = SeatChoice(
                    label = item.optString("label").ifBlank { "位置 ${current.seatChoices.size + 1}" },
                    selector = selector,
                    xFraction = item.optDouble("x", x.toDouble()),
                    yFraction = item.optDouble("y", y.toDouble())
                )
                store.save(current.copy(seatChoices = current.seatChoices + choice))
                showSelections()
            } catch (_: Exception) {
                message.text = "未能读取座位，请重新点击图标中央。"
            }
        }
    }

    private fun showSelections() {
        val seats = store.load().seatChoices
        message.text = if (seats.isEmpty()) {
            "已保存 0 个位置。${if (captureEnabled) "依次点击想预约的座位，先点的优先。" else "先进入一楼座位图，再开启位置录入。"}"
        } else {
            "已保存 ${seats.size} 个位置，优先顺序：${seats.joinToString(" → ") { it.label }}"
        }
    }

    override fun onBackPressed() {
        if (captureEnabled) {
            toggle.performClick()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            setResult(RESULT_OK)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}
