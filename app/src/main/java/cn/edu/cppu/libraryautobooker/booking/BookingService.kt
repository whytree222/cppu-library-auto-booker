package cn.edu.cppu.libraryautobooker.booking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import cn.edu.cppu.libraryautobooker.MainActivity
import cn.edu.cppu.libraryautobooker.data.ConfigStore

class BookingService : Service() {
    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var automationStarted = false

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:booking")
            .apply { acquire(120_000L) }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("正在连接校内预约系统…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = ConfigStore(this).load()
        if (!config.enabled && intent?.action !in setOf(ACTION_RUN_ONCE, ACTION_SCHEDULED)) {
            stopSelf()
            return START_NOT_STICKY
        }

        webView?.destroy()
        automationStarted = false
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " LibraryAutoBooker/0.1"
            addJavascriptInterface(Bridge(), "AutoBooker")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val uri = Uri.parse(url)
                    if (uri.host != "mlib.cppu.edu.cn") {
                        Bridge().report("error", "页面跳转到非校内站点，已停止")
                        return
                    }
                    if (uri.path == "/login") {
                        Bridge().report("error", "登录已失效，请先在应用内重新登录")
                        return
                    }
                    if (uri.path == "/selectreadingroom") {
                        val template = assets.open("selection.js").bufferedReader().use { it.readText() }
                        view.evaluateJavascript(AutomationScript.build(template, config), null)
                        return
                    }
                    if (uri.path != "/multireadingroomtablelist") {
                        Bridge().report("error", "没有进入过刊阅览室的预约页面")
                        return
                    }
                    if (automationStarted) {
                        view.evaluateJavascript(
                            "window.AutoBooker.report(/预约成功|预定成功/.test(document.body.innerText) ? 'success' : 'error', /预约成功|预定成功/.test(document.body.innerText) ? '网页显示预约成功；请在学校系统核对记录' : '页面已跳转，但没有明确成功提示；不能视为预约成功')",
                            null
                        )
                        return
                    }
                    automationStarted = true
                    val template = assets.open("automation.js").bufferedReader().use { it.readText() }
                    view.evaluateJavascript(AutomationScript.build(template, config), null)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Bridge().report("error", "无法打开校内系统：${error.description}")
                    }
                }
            }
            val metrics = resources.displayMetrics
            measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            loadUrl("http://mlib.cppu.edu.cn/selectreadingroom")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun report(state: String, detail: String) {
            if (state == "progress") {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID, notification(detail)
                )
                return
            }
            getSystemService(NotificationManager::class.java).notify(
                RESULT_NOTIFICATION_ID,
                notification(
                    if (state == "success") "预约成功：$detail" else "预约状态：$detail",
                    ongoing = false
                )
            )
            if (state in setOf("success", "submitted", "dry-run", "error")) stopSelf()
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自动预约", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun notification(text: String, ongoing: Boolean = true) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(cn.edu.cppu.libraryautobooker.R.drawable.ic_launcher_foreground)
        .setContentTitle("图书馆自动预约")
        .setContentText(text)
        .setOngoing(ongoing)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        const val ACTION_RUN_ONCE = "cn.edu.cppu.libraryautobooker.RUN_ONCE"
        const val ACTION_SCHEDULED = "cn.edu.cppu.libraryautobooker.SCHEDULED"
        private const val CHANNEL_ID = "booking"
        private const val NOTIFICATION_ID = 4402
        private const val RESULT_NOTIFICATION_ID = 4403
    }
}
