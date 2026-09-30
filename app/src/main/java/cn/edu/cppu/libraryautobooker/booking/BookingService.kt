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
import cn.edu.cppu.libraryautobooker.SessionCoordinator
import cn.edu.cppu.libraryautobooker.AutoLogin
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

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
            .apply { acquire(420_000L) }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("正在连接校内预约系统…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WAKE_TEST) {
            RuntimeStore(this).test("DONE", "唤醒链路通过：闹钟和后台服务均已运行；未执行预约")
            getSystemService(NotificationManager::class.java).notify(4405,
                notification("后台唤醒测试成功，未执行预约", ongoing = false))
            if (sequence == null) stopSelf()
            return START_NOT_STICKY
        }
        // Repeated taps/alarms must not restart an in-flight booking.
        if (sequence != null) return START_NOT_STICKY
        val config = ConfigStore(this).load()
        if (!config.enabled && intent?.action !in setOf(ACTION_RUN_ONCE, ACTION_SCHEDULED)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val plannedAt = intent?.getLongExtra("expected_at", 0L) ?: 0L
        val taskDay = if (intent?.action == ACTION_SCHEDULED && plannedAt > 0)
            Instant.ofEpochMilli(plannedAt).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
        sequence = BookingSequence(config, taskDay)
        RuntimeStore(this).record("RUNNING", "后台服务已启动：${if (intent?.action == ACTION_SCHEDULED) "定时" else "手动"}${if (config.dryRun) "演练（不提交）" else "真实预约"}")
        startBatch()
        return START_NOT_STICKY
    }

    private fun startBatch() {
        val authGeneration = ++generation
        RuntimeStore(this).record("RUNNING", sequence?.progress("正在验证登录，过期时自动重新登录") ?: "正在验证登录")
        SessionCoordinator.check(this) { valid, detail ->
            if (terminal || generation != authGeneration) return@check
            if (valid) launchBatch() else fail(detail)
        }
    }

    private fun launchBatch() {
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
        var recoveredLogin = false
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
                        RuntimeStore(this@BookingService).session("expired", "运行预约时发现登录已失效")
                        if (automationStarted || recoveredLogin) {
                            fail("预约过程中登录失效，未确认预约结果；请核对记录后重试")
                            return
                        }
                        recoveredLogin = true
                        AutoLogin.login(this@BookingService) { valid, detail ->
                            if (terminal || generation != currentGeneration) return@login
                            if (valid) view.loadUrl("http://mlib.cppu.edu.cn/selectreadingroom") else fail(detail)
                        }
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
        if (!terminal && sequence != null) RuntimeStore(this).record("INTERRUPTED", sequence!!.progress("预约服务提前结束，未确认最终结果；请核对学校记录"))
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
        complete(sequence?.failure(detail)?.detail ?: detail, "FAILED")
    }

    private fun complete(detail: String, state: String = "DONE") {
        terminal = true
        RuntimeStore(this).record(state, detail)
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
                "progress" -> {
                    val progress = sequence?.progress(detail) ?: detail
                    RuntimeStore(this@BookingService).record("RUNNING", progress)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
                }
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
                    RuntimeStore(this@BookingService).record("RUNNING", result.detail)
                    showResult(result.detail)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(result.detail))
                    startBatch()
                }
                is BookingSequence.Result.Done -> complete(result.detail)
                is BookingSequence.Result.Failed -> complete(result.detail, "FAILED")
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
        const val ACTION_WAKE_TEST = "cn.edu.cppu.libraryautobooker.WAKE_TEST"
        const val ACTION_SCHEDULED = "cn.edu.cppu.libraryautobooker.SCHEDULED"
        private const val CHANNEL_ID = "booking"
        private const val NOTIFICATION_ID = 4402
        private const val RESULT_NOTIFICATION_ID = 4403
    }
}

