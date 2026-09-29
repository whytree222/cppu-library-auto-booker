package cn.edu.cppu.libraryautobooker.booking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
import java.time.LocalDate

class BookingService : Service() {
    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var sequence: BookingSequence? = null
    private var generation = 0
    private var terminal = false

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:booking")
            .apply { acquire(330_000L) }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("正在连接校内预约系统…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Repeated taps/alarms must not restart an in-flight booking.
        if (sequence != null) return START_NOT_STICKY
        val config = ConfigStore(this).load()
        if (!config.enabled && intent?.action !in setOf(ACTION_RUN_ONCE, ACTION_SCHEDULED)) {
            stopSelf()
            return START_NOT_STICKY
        }

        sequence = BookingSequence(config)
        startBatch()
        return START_NOT_STICKY
    }

    private fun startBatch() {
        val run = sequence ?: return
        val config = run.configForDate(LocalDate.now())
        if (config == null) {
            fail("目标日期已不在今天或明天范围内")
            return
        }
        val currentGeneration = ++generation
        val bridge = Bridge(currentGeneration, run.batch)
        // Fresh fields and dialogs for each batch; app-wide cookies keep the login.
        webView?.destroy()
        var automationStarted = false
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " LibraryAutoBooker/0.1"
            addJavascriptInterface(bridge, "AutoBooker")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (terminal || generation != currentGeneration) return
                    val uri = Uri.parse(url)
                    if (uri.host != "mlib.cppu.edu.cn") {
                        fail("页面跳转到非校内站点，已停止")
                        return
                    }
                    if (uri.path == "/login") {
                        fail("登录已失效，请先在应用内重新登录")
                        return
                    }
                    if (uri.path == "/selectreadingroom" && !automationStarted) {
                        val template = assets.open("selection.js").bufferedReader().use { it.readText() }
                        view.evaluateJavascript(AutomationScript.build(template, config, run.targetDate.toString()), null)
                        return
                    }
                    if (uri.path != "/multireadingroomtablelist") {
                        fail("未进入预约座位图，无法确认本笔预约结果")
                        return
                    }
                    if (automationStarted) return
                    automationStarted = true
                    val template = assets.open("automation.js").bufferedReader().use { it.readText() }
                    view.evaluateJavascript(AutomationScript.build(template, config, run.targetDate.toString()), null)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (!terminal && generation == currentGeneration && request.isForMainFrame) {
                        fail("无法打开校内系统：${error.description}")
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
        main.postDelayed({
            if (!terminal && generation == currentGeneration) fail("本笔任务超时，未确认预约结果")
        }, 150_000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        terminal = true
        generation++
        main.removeCallbacksAndMessages(null)
        webView?.destroy()
        webView = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun fail(detail: String) {
        if (terminal) return
        complete(sequence?.failure(detail)?.detail ?: detail)
    }

    private fun complete(detail: String) {
        terminal = true
        showResult(detail)
        stopSelf()
    }

    private fun showResult(detail: String) {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID, notification(detail, ongoing = false)
        )
    }

    private inner class Bridge(private val sourceGeneration: Int, private val sourceBatch: Int) {
        private fun dispatch(action: () -> Unit) {
            main.post { if (!terminal && generation == sourceGeneration) action() }
        }

        @JavascriptInterface
        fun report(state: String, detail: String) = dispatch {
            when (state) {
                "progress" -> getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID, notification(sequence?.progress(detail) ?: detail)
                )
                "dry-run" -> complete(if (sequence?.hasSecondBatch == true)
                    "前四段演练：$detail；未提交两笔预约，后三段未运行" else detail)
                "error", "submitted" -> fail(detail)
                "success" -> fail("成功反馈缺少具体座位，无法继续追加预约；请核对学校记录")
            }
        }

        @JavascriptInterface
        fun booked(seatNumber: String, detail: String) = dispatch {
            when (val result = sequence?.success(sourceBatch, seatNumber)) {
                is BookingSequence.Result.Next -> {
                    showResult(result.detail)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(result.detail))
                    startBatch()
                }
                is BookingSequence.Result.Done -> complete(result.detail)
                is BookingSequence.Result.Failed -> complete(result.detail)
                else -> Unit
            }
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
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
