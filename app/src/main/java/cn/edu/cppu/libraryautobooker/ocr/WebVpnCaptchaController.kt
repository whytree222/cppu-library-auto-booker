package cn.edu.cppu.libraryautobooker.ocr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CAPTCHA reading/filling during an active foreground page or opted-in login run.
 * No password access, JS bridge, network client,
 * login submission, persistent images or diagnostic logging. Unreliable inference may
 * request at most two fresh images through the school's already-bound click handler.
 */
class WebVpnCaptchaController(
    context: Context,
    private val webView: WebView,
    private val onFilled: () -> Unit = {},
    private val onStopped: (String) -> Unit = {},
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val app = context.applicationContext
    private val script = app.assets.open("webvpn-captcha.js").bufferedReader().use { it.readText() }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    internal var recognizeForTest: ((ByteArray) -> DdddOcrEngine.Result)? = null
    private val closed = AtomicBoolean(false)
    private var engine: DdddOcrEngine? = null // Worker thread owns and closes this resource.
    private var generation = 0L
    private var enabled = false
    private var foreground = false
    private var pageReady = false
    private var halted = false
    private var pageUrl = ""
    private var budget = CaptchaAttemptBudget(SystemClock.elapsedRealtime())
    private var pending = false
    private var readRetries = 0
    private var refreshes = 0
    private var lastImageKey: String? = null
    private var awaitingImageKey: String? = null
    private var refreshAt = 0L
    private val poll = Runnable { capture() }

    fun setEnabled(value: Boolean) {
        if (closed.get()) return
        enabled = value
        invalidate()
        if (!value) status("自动识别已关闭，可手动输入验证码")
        else if (pageReady && foreground && CaptchaPagePolicy.isTrusted(pageUrl)) retry()
        else status("已启用；等待学校统一认证页面显示验证码")
    }

    fun onPageStarted(url: String) {
        if (closed.get()) return
        invalidate()
        pageReady = false
        halted = false
        pageUrl = url
        budget = CaptchaAttemptBudget(SystemClock.elapsedRealtime())
        resetRetries()
        status(if (enabled && CaptchaPagePolicy.isTrusted(url)) "统一认证页面正在加载验证码…"
            else if (enabled) "等待学校统一认证验证码页面"
            else "自动识别已关闭，可手动输入验证码")
    }

    fun onPageFinished(url: String) {
        if (closed.get() || url != webView.url) return
        if (!pageReady || pageUrl != url) {
            invalidate()
            halted = false
            budget = CaptchaAttemptBudget(SystemClock.elapsedRealtime())
            resetRetries()
        }
        pageUrl = url
        pageReady = CaptchaPagePolicy.isTrusted(url)
        if (pageReady && enabled && foreground && !halted) install(reset = false)
        else if (enabled && !pageReady) status("当前页面无需统一认证验证码识别；仍以学校验证结果为准")
    }

    fun onResume() {
        if (closed.get()) return
        foreground = true
        if (enabled && pageReady && !halted) install(reset = false)
    }

    fun onPause() {
        if (closed.get()) return
        foreground = false
        invalidate()
    }

    /** Explicitly starts a fresh bounded round; never overwrites manual input. */
    fun retry() {
        if (closed.get()) return
        if (!enabled) { status("请先勾选自动识别；也可直接手动输入"); return }
        if (!foreground || !pageReady || !CaptchaPagePolicy.isTrusted(webView.url)) {
            status("请先打开学校统一认证的验证码页面")
            return
        }
        invalidate()
        halted = false
        budget = CaptchaAttemptBudget(SystemClock.elapsedRealtime())
        resetRetries()
        install(reset = true)
    }

    private fun invalidate() {
        generation++
        pending = false
        handler.removeCallbacks(poll)
    }

    private fun active(token: Long = generation): Boolean = !closed.get() && enabled && foreground &&
        pageReady && !halted && token == generation && pageUrl == webView.url && CaptchaPagePolicy.isTrusted(pageUrl)

    private fun install(reset: Boolean) {
        if (!active() || pending) return
        val token = generation
        pending = true
        evaluate(script, token) {
            if (reset) evaluate("JSON.stringify(window.LibraryCaptchaOcr?.reset() || {kind:'stopped'})", token) { result ->
                pending = false
                if (result.optString("kind") in setOf("manual", "stopped")) {
                    stop("输入框有手动内容或页面不符合规则，请保留手动输入")
                } else schedule(0)
            } else {
                pending = false
                schedule(0)
            }
        }
    }

    private fun capture() {
        if (!active() || pending) return
        if (budget.expired(SystemClock.elapsedRealtime())) {
            stop("本轮自动识别观察已结束；需要时点击重新识别，已填内容不会被清除")
            return
        }
        pending = true
        val token = generation
        evaluate("JSON.stringify(window.LibraryCaptchaOcr?.snapshot() || {kind:'stopped'})", token) { result ->
            pending = false
            when (result.optString("kind")) {
                "ready" -> {
                    val key = result.optString("imageKey")
                    if (awaitingImageKey == key) {
                        if (SystemClock.elapsedRealtime() - refreshAt >= 8_000L)
                            stop("学校验证码刷新后图片未更新，已停止自动尝试；请检查网页")
                        else { status("等待学校返回新的验证码图片…"); schedule() }
                    } else {
                        awaitingImageKey = null
                        readRetries = 0
                        lastImageKey = key
                        recognize(result, token)
                    }
                }
                "filled" -> { status("验证码已自动填写"); onFilled(); schedule() }
                "waiting" -> { status("等待当前页面的验证码图片加载；不会额外刷新图片"); schedule() }
                "unavailable" -> retryRead("当前网页验证码图片暂时无法读取")
                "manual" -> stop("检测到手动输入，自动填写已停止；不会覆盖你的内容")
                else -> stop("验证码定位失败：${explain(result.optString("detailCode"))}；请手动输入")
            }
        }
    }

    private fun resetRetries() {
        readRetries = 0; refreshes = 0; lastImageKey = null; awaitingImageKey = null; refreshAt = 0L
    }

    private fun retryRead(reason: String) {
        if (++readRetries > 2) { stop("$reason，自动读取重试已达上限；请手动输入"); return }
        status("$reason；正在自动重试读取（$readRetries/2）")
        schedule(1_500L)
    }

    private fun refreshAfterFailure(reason: String, token: Long) {
        if (!active(token)) return
        if (refreshes >= 2 || budget.expired(SystemClock.elapsedRealtime())) {
            stop("$reason；本轮自动识别已达上限，请手动完成")
            return
        }
        refreshes++
        pending = true
        status("$reason；将自动刷新验证码后重试（$refreshes/2）")
        handler.postDelayed({
            if (!active(token)) return@postDelayed
            evaluate("JSON.stringify(window.LibraryCaptchaOcr?.refresh() || {kind:'stopped'})", token) { result ->
                pending = false
                if (result.optString("kind") == "refreshing") {
                    awaitingImageKey = lastImageKey
                    refreshAt = SystemClock.elapsedRealtime()
                    schedule()
                } else stop("验证码无法安全刷新或已有手动输入，已停止自动尝试")
            }
        }, 1_500L)
    }

    private fun recognize(snapshot: JSONObject, token: Long) {
        val imageKey = snapshot.optString("imageKey")
        val ticket = snapshot.optString("token")
        val data = snapshot.optString("data")
        if (imageKey.length !in 1..128 || ticket.length !in 1..160 || data.length !in 1..MAX_BASE64) {
            stop("网页验证码数据不符合规则，请手动输入")
            return
        }
        when (budget.begin(imageKey, SystemClock.elapsedRealtime())) {
            CaptchaAttemptBudget.Decision.ALREADY_TRIED -> {
                refreshAfterFailure("当前图片本轮已尝试", token); return
            }
            CaptchaAttemptBudget.Decision.LIMIT -> {
                stop("本轮已识别三张图片，自动尝试已停止；可手动输入"); return
            }
            CaptchaAttemptBudget.Decision.EXPIRED -> {
                stop("本轮识别观察已结束，需要时点击重新识别"); return
            }
            CaptchaAttemptBudget.Decision.START -> Unit
        }
        val bytes = try { Base64.decode(data, Base64.NO_WRAP) } catch (_: IllegalArgumentException) {
            stop("验证码图片格式不正确，请手动输入"); return
        }
        if (bytes.size !in 1..MAX_IMAGE_BYTES) { bytes.fill(0); stop("验证码图片大小不符合规则，请手动输入"); return }
        pending = true
        status("正在本机识别当前验证码，不上传图片…")
        worker.execute {
            if (closed.get()) { bytes.fill(0); return@execute }
            try {
                val result = recognizeForTest?.invoke(bytes) ?: run {
                    val local = engine ?: DdddOcrEngine(app).also { engine = it }
                    local.recognize(bytes, digitsOnly = true)
                }
                handler.post {
                    if (active(token)) {
                        pending = false
                        if (!CaptchaPagePolicy.accepts(result.text, result.unrestrictedText, result.confidence))
                            refreshAfterFailure("识别内容不完整或模型置信信号不足", token)
                        else fill(ticket, result.text, token)
                    }
                }
            } catch (_: Exception) { failedInference(token) }
            catch (_: LinkageError) { failedInference(token) }
            catch (_: OutOfMemoryError) { failedInference(token) }
            finally { bytes.fill(0) }
        }
    }

    private fun failedInference(token: Long) {
        if (closed.get()) return
        handler.post {
            if (active(token)) { pending = false; refreshAfterFailure("本机识别失败", token) }
        }
    }

    private fun fill(ticket: String, text: String, token: Long) {
        if (!active(token)) return
        pending = true
        val ticketJson = JSONObject.quote(ticket)
        val textJson = JSONObject.quote(text)
        evaluate("JSON.stringify(window.LibraryCaptchaOcr?.fill($ticketJson,$textJson) || {kind:'stopped'})", token) { result ->
            pending = false
            when (result.optString("kind")) {
                "filled" -> { status("验证码已自动填写"); onFilled(); schedule() }
                "stale", "waiting" -> { status("验证码已变化，旧识别结果已丢弃；检查当前图片…"); schedule() }
                "manual" -> stop("你已手动输入，识别结果已丢弃；不会覆盖你的内容")
                else -> if (result.optString("detailCode") == "image-changed") {
                    status("验证码或页面已变化，旧结果已丢弃；检查当前图片…")
                    schedule()
                } else stop("未能安全填写验证码，请手动输入")
            }
        }
    }

    private fun evaluate(source: String, token: Long, callback: (JSONObject) -> Unit) {
        if (!active(token)) return
        try {
            webView.evaluateJavascript(source) { raw ->
                if (!active(token)) return@evaluateJavascript
                val parsed = runCatching {
                    require(raw != null && raw.length <= MAX_BASE64 + 16_384)
                    when (val value = JSONTokener(raw).nextValue()) {
                        is JSONObject -> value
                        is String -> JSONObject(value)
                        else -> JSONObject().put("kind", "stopped")
                    }
                }.getOrElse { JSONObject().put("kind", "stopped") }
                callback(parsed)
            }
        } catch (_: RuntimeException) {
            if (active(token)) stop("当前页面无法执行安全识别，请手动输入")
        }
    }

    private fun schedule(delayMillis: Long = 800L) {
        if (active()) { handler.removeCallbacks(poll); handler.postDelayed(poll, delayMillis) }
    }

    private fun stop(message: String) {
        halted = true
        pending = false
        handler.removeCallbacks(poll)
        status(message)
        onStopped(message)
    }

    private fun explain(code: String): String = when (code) {
        "unsupported-page" -> "当前不是已适配的统一认证地址"
        "unsupported-structure" -> "密码登录表单或验证码元素不唯一/尚未就绪"
        "wrong-action" -> "登录表单的代理目标尚未匹配"
        "wrong-action-attribute" -> "登录表单原始地址不在学校适配范围"
        "wrong-action-property" -> "登录表单实际提交地址不在学校适配范围"
        "wrong-action-rewrite" -> "学校相对地址未能转换为有效代理提交地址"
        "unsupported-image" -> "验证码图片的代理路径尚未匹配"
        "image-too-large" -> "验证码图片尺寸超出限制"
        "invalid-image-data" -> "图片数据不符合规则"
        "document-changed" -> "网页已切换"
        else -> "网页没有返回可安全识别的验证码"
    }

    private fun status(message: String) { if (!closed.get()) onStatus(message) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidate()
        worker.execute { runCatching { engine?.close() }; engine = null }
        // Let a running inference finish before closing its session; never interrupt native code.
        worker.shutdown()
    }

    companion object {
        private const val MAX_BASE64 = 350_000
        private const val MAX_IMAGE_BYTES = 256 * 1024
    }
}
