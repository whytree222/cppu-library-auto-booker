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
import android.os.SystemClock
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import androidx.core.app.NotificationCompat
import cn.edu.cppu.libraryautobooker.MainActivity
import cn.edu.cppu.libraryautobooker.SessionCoordinator
import cn.edu.cppu.libraryautobooker.AutoLogin
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.network.SubmissionGuard
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.SeatMapCatalog
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class BookingService : Service() {
    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var sequence: BookingSequence? = null
    @Volatile private var generation = 0
    @Volatile private var terminal = false
    private var historyId: String? = null
    private var releaseDeadline = 0L
    private var gateActive = false
    private val submission = SubmissionGuard()
    private var loginCheck: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:booking")
            .apply { acquire(if (ConfigStore(this@BookingService).load().waitForRelease) 720_000L else 420_000L) }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("正在连接校内预约系统…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (sequence != null) fail("用户已停止当前任务；如曾确认提交，请核对学校记录；已成功预约不会被取消")
            else stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_WAKE_TEST) {
            RuntimeStore(this).test("DONE", "唤醒链路通过：闹钟和后台服务均已运行；未执行预约")
            getSystemService(NotificationManager::class.java).notify(4405,
                notification("后台唤醒测试成功，未执行预约", ongoing = false))
            if (sequence == null) stopSelf()
            return START_NOT_STICKY
        }
        val manualActionId = if (intent?.action == ACTION_RUN_ONCE)
            ImmediateBookingPlan.actionId(intent) ?: "manual:${UUID.randomUUID()}" else null
        // Repeated taps/alarms must not restart an in-flight booking.
        if (sequence != null) {
            if (intent?.action == ACTION_SCHEDULED) {
                val expected = intent.getLongExtra("expected_at", 0L)
                RuntimeStore(this).record("FAILED", "上一笔预约仍在运行，本次时间未执行，避免并发重复提交；请将抢座时间适当分开", RuntimeStore.scheduledAction(expected), "定时任务")
            } else if (intent?.action == ACTION_RUN_ONCE) {
                RuntimeStore(this).record("FAILED", "已有预约任务运行中，本次直接预约未重复启动",
                    manualActionId, "直接预约未启动")
            }
            return START_NOT_STICKY
        }
        val manual = if (intent?.action == ACTION_RUN_ONCE)
            ImmediateBookingPlan.decode(intent.getStringExtra(ImmediateBookingPlan.EXTRA_REQUEST)) else null
        if (intent?.action == ACTION_RUN_ONCE && manual == null) {
            RuntimeStore(this).record("FAILED", "直接预约参数缺失或无效，未进入网页预约",
                manualActionId, "直接预约")
            stopSelf()
            return START_NOT_STICKY
        }
        val config = manual?.config ?: ConfigStore(this).load()
        if (manual != null) {
            val matchesMap = runCatching {
                val map = requireNotNull(SeatMapCatalog.load(this).forRoom(config.room))
                map.bind(config).mapSeats == config.mapSeats
            }.getOrDefault(false)
            if (!matchesMap) {
                RuntimeStore(this).record("FAILED", "直接预约的座位图定位不匹配，请重新选座",
                    manualActionId, "直接预约")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_SCHEDULED && intent.getStringExtra("booking_fingerprint") != config.reservationFingerprint()) {
            val expected = intent.getLongExtra("expected_at", 0L)
            RuntimeStore(this).record("CANCELLED", "闹钟触发后阅览室、座位或时间设置已变化；旧任务已停止，未进入网页预约",
                RuntimeStore.scheduledAction(expected))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!config.enabled && intent?.action !in setOf(ACTION_RUN_ONCE, ACTION_SCHEDULED)) {
            stopSelf()
            return START_NOT_STICKY
        }
        config.validationError()?.let { error ->
            RuntimeStore(this).record("FAILED", "任务参数未通过检查：$error；未进入网页预约", manualActionId)
            showResult(error)
            stopSelf()
            return START_NOT_STICKY
        }

        val plannedAt = intent?.getLongExtra("expected_at", 0L) ?: 0L
        historyId = if (intent?.action == ACTION_SCHEDULED) RuntimeStore.scheduledAction(plannedAt)
            else manualActionId ?: "manual:${UUID.randomUUID()}"
        val taskDay = if (intent?.action == ACTION_SCHEDULED && plannedAt > 0)
            Instant.ofEpochMilli(plannedAt).atZone(ZoneId.systemDefault()).toLocalDate() else manual?.anchorDate ?: LocalDate.now()
        sequence = BookingSequence(config, taskDay)
        running = true
        if (config.waitForRelease) {
            releaseDeadline = SystemClock.elapsedRealtime() + 600_000L
            main.postDelayed({
                if (!terminal) fail("任务已达到 10 分钟上限，已停止；如曾确认提交但未收到结果，请核对学校记录")
            }, 600_000L)
        }
        RuntimeStore(this).record("RUNNING", "后台服务已启动：${if (intent?.action == ACTION_SCHEDULED) "定时" else "手动"}${if (config.dryRun) "演练（不提交）" else "真实预约"}", historyId,
            "${if (intent?.action == ACTION_SCHEDULED) "定时" else "立即"}${if (config.dryRun) "演练" else "预约"}")
        startBatch()
        return START_NOT_STICKY
    }

    private fun startBatch() {
        releaseLoginCheck()
        val authGeneration = ++generation
        val route = LibraryRoute.DIRECT
        record("RUNNING", sequence?.progress("${route.label}：正在验证图书馆登录，过期时自动重新登录") ?: "正在验证登录")
        loginCheck = SessionCoordinator.checkRoute(this, route) { result ->
            if (terminal || generation != authGeneration) return@checkRoute
            releaseLoginCheck()
            if (result.state == "valid") launchBatch()
            else if (result.networkFailure) networkFailure(result.detail)
            else fail(result.detail)
        }
    }

    private fun networkFailure(detail: String) {
        if (terminal) return
        fail(if (submission.hasSubmitted())
            "$detail；已经进入确认提交阶段，结果不明时不重发，请核对学校记录"
            else "$detail；请检查校园网络，未提交预约")
    }

    private fun launchBatch() {
        if (releaseDeadline > 0 && SystemClock.elapsedRealtime() >= releaseDeadline) {
            fail("10 分钟等待窗口已结束，已停止")
            return
        }
        val run = sequence ?: return
        val config = run.configForDate(LocalDate.now())
        if (config == null) {
            fail("目标日期已不在今天或明天范围内")
            return
        }
        val currentGeneration = ++generation
        val route = LibraryRoute.DIRECT
        gateActive = config.waitForRelease
        val bridge = Bridge(currentGeneration, run.batch, route)
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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (route.pathOf(request.url.toString()) == null) {
                        fail("页面跳转到非图书馆站点，已停止")
                        return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (terminal || generation != currentGeneration) return
                    val path = route.pathOf(url)
                    if (path == null) {
                        fail("页面跳转到非图书馆站点，已停止")
                        return
                    }
                    if (path == "/login") {
                        RuntimeStore(this@BookingService).session("expired", "运行预约时发现登录已失效")
                        if (automationStarted || recoveredLogin) {
                            fail("预约过程中登录失效，未确认预约结果；请核对记录后重试")
                            return
                        }
                        recoveredLogin = true
                        AutoLogin.login(this@BookingService) { valid, detail ->
                            if (terminal || generation != currentGeneration) return@login
                            if (valid) view.loadUrl(route.url(config.room.entryPath)) else fail(detail)
                        }
                        return
                    }
                    if (path == "/selectreadingroom" || path in setOf("/multireadingroomtablelist", "/readingroomtablelist"))
                        view.evaluateJavascript(AutomationScript.transport(this@BookingService, route), null)
                    if (path == "/selectreadingroom" && !automationStarted) {
                        val asset = if (config.waitForRelease) "release-wait.js" else "selection.js"
                        val template = assets.open(asset).bufferedReader().use { it.readText() }
                            .replace("__WAIT_MS__", (releaseDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0).toString())
                        view.evaluateJavascript(AutomationScript.build(template, config, run.targetDate.toString()), null)
                        return
                    }
                    if (path !in setOf("/multireadingroomtablelist", "/readingroomtablelist")) {
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
                        if (error.errorCode in listOf(WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
                                WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO))
                            networkFailure("${route.label}网页连接失败，错误代码 ${error.errorCode}")
                        else fail("${route.label}网页加载失败，错误代码 ${error.errorCode}")
                    }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (terminal || generation != currentGeneration || !request.isForMainFrame) return
                    if (response.statusCode in listOf(408, 502, 503, 504)) networkFailure("${route.label}网关返回 HTTP ${response.statusCode}")
                    else fail("${route.label}返回 HTTP ${response.statusCode}")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    if (!terminal && generation == currentGeneration) fail("证书校验失败，已停止；没有绕过 HTTPS 安全检查")
                }
            }
            val metrics = resources.displayMetrics
            measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            loadUrl(route.url(config.room.entryPath))
        }
        main.postDelayed({
            if (!terminal && generation == currentGeneration && !config.waitForRelease) fail("本笔任务超时，未确认预约结果")
        }, 150_000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!terminal && sequence != null) record("INTERRUPTED", sequence!!.progress("预约服务提前结束，未确认最终结果；请核对学校记录"))
        terminal = true
        running = false
        generation++
        releaseLoginCheck()
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
        releaseLoginCheck()
        val room = sequence?.config?.room
        val identified = if (room != null) "${room.groupName} · ${room.name}：$detail" else detail
        record(state, identified)
        showResult(identified)
        stopSelf()
    }

    private fun releaseLoginCheck() {
        val previous = loginCheck
        loginCheck = null
        previous?.close()
    }

    private fun showResult(detail: String) {
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID, notification(detail, ongoing = false)
        )
    }

    private fun record(state: String, detail: String) { RuntimeStore(this).record(state, detail, historyId) }

    private inner class Bridge(private val sourceGeneration: Int, private val sourceBatch: Int, private val sourceRoute: LibraryRoute) {
        private fun dispatch(action: () -> Unit) {
            main.post { if (!terminal && generation == sourceGeneration) action() }
        }

        @JavascriptInterface
        fun beginSubmission(): Boolean {
            if (terminal || generation != sourceGeneration) return false
            return submission.markSubmission(sourceRoute)
        }

        @JavascriptInterface
        fun report(state: String, detail: String) = dispatch {
            when (state) {
                "network-error" -> networkFailure(detail)
                "opened" -> {
                    if (!gateActive) return@dispatch
                    gateActive = false
                    val run = sequence ?: return@dispatch
                    val config = run.configForDate(LocalDate.now()) ?: return@dispatch fail("目标日期已不在今天或明天范围内")
                    record("RUNNING", run.progress(detail))
                    val template = assets.open("selection.js").bufferedReader().use { it.readText() }
                    webView?.evaluateJavascript(AutomationScript.build(template, config, run.targetDate.toString()), null)
                    main.postDelayed({
                        if (!terminal && generation == sourceGeneration && !gateActive) fail("本笔任务超时，未确认预约结果")
                    }, 150_000L)
                }
                "closed" -> {
                    if (sequence?.config?.waitForRelease == true && SystemClock.elapsedRealtime() < releaseDeadline) {
                        record("RUNNING", sequence!!.progress("$detail；继续检查，10 分钟窗口不重置"))
                        main.postDelayed({ if (!terminal && generation == sourceGeneration) launchBatch() }, 1000L)
                    } else fail(detail)
                }
                "progress" -> {
                    val progress = sequence?.progress(detail) ?: detail
                    record("RUNNING", progress)
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
                    submission.confirmedNextBatch()
                    record("RUNNING", result.detail)
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
        @Volatile var running: Boolean = false
            private set
        const val ACTION_RUN_ONCE = "cn.edu.cppu.libraryautobooker.RUN_ONCE"
        const val ACTION_WAKE_TEST = "cn.edu.cppu.libraryautobooker.WAKE_TEST"
        const val ACTION_SCHEDULED = "cn.edu.cppu.libraryautobooker.SCHEDULED"
        const val ACTION_CANCEL = "cn.edu.cppu.libraryautobooker.CANCEL_CURRENT"
        private const val CHANNEL_ID = "booking"
        private const val NOTIFICATION_ID = 4402
        private const val RESULT_NOTIFICATION_ID = 4403
    }
}
