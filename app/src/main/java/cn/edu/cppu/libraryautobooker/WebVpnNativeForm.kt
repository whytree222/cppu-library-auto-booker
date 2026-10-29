package cn.edu.cppu.libraryautobooker

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.network.NativeVpnFailure
import cn.edu.cppu.libraryautobooker.network.NativeVpnForm
import cn.edu.cppu.libraryautobooker.network.NativeVpnStep
import cn.edu.cppu.libraryautobooker.network.NativeWebVpnSession
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** All visible controls are native. Challenges/passwords are never put in saved state. */
internal class WebVpnNativeForm(
    private val activity: Activity,
    private val onVerified: () -> Unit,
    private val session: NativeWebVpnSession = sessionFactory(),
    private val worker: Executor = workerFactory(),
    autoStart: Boolean = true
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var authenticationReady = autoStart
    private val activeActions = AtomicInteger()
    private var busy = false
    private var resultDialog: AlertDialog? = null
    private var lastOperation = "读取登录表单"
    private val diagnostics = TextView(activity).apply { textSize = 12f }
    private var step: NativeVpnStep? = null
    private var kind: NativeVpnForm.Kind? = null
    // Session validity does not prove that a stored password was ever used. Keep
    // provenance only for this screen's actual submission and its saved revision.
    private class CredentialVerification(val form: NativeVpnForm, val revision: String)
    private var credentialVerification: CredentialVerification? = null
    private val preferences = activity.getSharedPreferences("webvpn_ocr", Activity.MODE_PRIVATE)
    val root = ScrollView(activity).apply { isFillViewport = true; isSaveEnabled = false }
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }
    private val title = TextView(activity).apply { text = "WebVPN 应用内登录"; textSize = 24f }
    private val status = TextView(activity).apply { text = "正在获取学校登录表单…"; textSize = 16f }
    private val progress = ProgressBar(activity)
    private fun field(label: String, type: Int) = EditText(activity).apply {
        hint = label; contentDescription = label; inputType = type; isSingleLine = true
        isSaveEnabled = false; isSaveFromParentEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private val username = field("统一认证学工号", InputType.TYPE_CLASS_TEXT)
    private val password = field("统一认证密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    private val captcha = field("四位数字验证码", InputType.TYPE_CLASS_NUMBER).apply { filters = arrayOf(InputFilter.LengthFilter(4)) }
    private val image = ImageView(activity).apply {
        contentDescription = "学校验证码图片，请在下方输入四位数字"
        adjustViewBounds = true
        minimumHeight = (80 * resources.displayMetrics.density).toInt()
        setOnClickListener { if (!busy) load() }
    }
    private val captchaNote = TextView(activity).apply { text = "正在获取验证码…"; textSize = 14f }
    private val remember = CheckBox(activity).apply { text = "在本机加密记住这套账号密码"; isSaveEnabled = false }
    private val fiveDays = CheckBox(activity).apply {
        text = "学校五天内自动登录"
        isChecked = preferences.getBoolean("remember_me", true)
        setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("remember_me", checked).apply() }
    }
    private val refresh = Button(activity).apply { text = "换一张验证码 / 重新获取表单"; setOnClickListener { load() } }
    private val submit = Button(activity).apply { text = "登录统一认证"; setOnClickListener { submit() } }
    private val check = Button(activity).apply { text = "检查登录状态"; setOnClickListener { execute { session.check() } } }

    init {
        root.addView(content)
        if (BuildConfig.DEBUG) content.addView(TextView(activity).apply { text = "原生自动登录调试版 · 10-28"; textSize = 12f })
        for (view in listOf(title, TextView(activity).apply {
            text = "这里是应用内手动登录入口。自动登录失败时，可手动输入验证码完成两层登录；自动恢复在账号设置中开启。五天有效期由学校决定。"
        }, status, progress, username, password, image, captchaNote, captcha, fiveDays, remember, submit, refresh, check,
            Button(activity).apply { text = "返回应用"; setOnClickListener { activity.finish() } })) {
            content.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (BuildConfig.DEBUG) {
            content.addView(diagnostics)
            content.addView(Button(activity).apply { text = "复制脱敏诊断"; setOnClickListener { copyDiagnostics() } })
        }
        if (authenticationReady) load() else {
            status.text = "正在等待上一轮认证和会话写入结束…"
            updateControls()
        }
    }

    fun authenticationReady(ready: Boolean) {
        if (closed) return
        authenticationReady = ready
        if (ready) load() else {
            status.text = "预约或上一轮认证尚未结束，或已有登录窗口。请先结束并核对预约，稍后重新打开登录。"
            updateControls()
        }
    }

    private fun load() {
        if (busy || !authenticationReady || closed) return
        credentialVerification = null
        step = null; captcha.setText(""); image.setImageDrawable(null)
        image.visibility = View.GONE
        captchaNote.visibility = View.VISIBLE; captchaNote.text = "正在重新获取验证码…"
        status.text = "正在获取学校登录表单…"
        execute(operation = "重新获取登录表单") { session.load() }
    }

    private fun submit() {
        val form = step?.form ?: return
        if (busy || !authenticationReady || closed) return
        if (cn.edu.cppu.libraryautobooker.booking.BookingService.running) {
            status.text = "预约正在运行，请先结束并核对预约；本次未执行登录"
            return
        }
        val user = username.text.toString().trim()
        val secret = password.text.toString()
        val code = captcha.text.toString()
        val rememberFiveDays = fiveDays.isChecked
        if (WebVpnCredentialStore.inputError(user, secret) != null) {
            status.text = "请填写有效的账号和密码"; return
        }
        if (form.kind == NativeVpnForm.Kind.SSO && !code.matches(Regex("[0-9]{4}"))) {
            status.text = "请输入图片中的四位数字验证码"; captcha.error = "需要四位数字"; return
        }
        try {
            val store = store(form.kind)
            if (remember.isChecked) store.save(user, secret)
            else if (store.configured) store.clear()
        } catch (_: Exception) {
            status.text = "本机加密保存失败，尚未提交登录；请关闭记住账号后重试"; return
        }
        val account = store(form.kind)
        val verification = if (account.enabled && !account.paused)
            CredentialVerification(form, account.revision) else null
        credentialVerification = verification
        status.text = if (form.kind == NativeVpnForm.Kind.SSO) "正在提交统一认证并等待学校响应…" else "正在登录 WebVPN 图书馆…"
        execute(submitted = verification, loginSubmission = true,
            operation = if (form.kind == NativeVpnForm.Kind.SSO) "点击登录统一认证" else "点击登录图书馆") {
            session.submit(form, user, secret, code, rememberFiveDays) {
                authenticationReady && !closed && !cn.edu.cppu.libraryautobooker.booking.BookingService.running
            }
        }
    }

    private fun store(formKind: NativeVpnForm.Kind) = credentialStoreFactory(activity,
        if (formKind == NativeVpnForm.Kind.SSO) WebVpnCredentialStore.Kind.SSO else WebVpnCredentialStore.Kind.LIBRARY)

    private fun execute(submitted: CredentialVerification? = null, loginSubmission: Boolean = false,
        operation: String = "检查登录状态", action: () -> NativeVpnStep) {
        if (busy || closed || !authenticationReady) return
        if (cn.edu.cppu.libraryautobooker.booking.BookingService.running) {
            status.text = "预约正在运行，请先结束并核对预约；本次未执行登录"
            return
        }
        lastOperation = operation
        busy = true; updateControls(); updateDiagnostics()
        SessionChecker.invalidateAuthentication(LibraryRoute.WEBVPN)
        activeActions.incrementAndGet()
        try { worker.execute {
            try {
            if (closed) return@execute
            val result = runCatching(action)
            main.post {
                if (closed || activity.isFinishing || activity.isDestroyed) return@post
                busy = false
                result.fold(onSuccess = { next ->
                    if (submitted != null && (!submitted.form.consumed.get() || next.error))
                        discardVerification(submitted)
                    show(next)
                    updateDiagnostics()
                    if (loginSubmission && next.error) showFailureDialog(next.result.detail)
                }, onFailure = { error ->
                    // Unknown post-submit outcomes may later be confirmed by a
                    // read-only check. Proven pre-send failures cannot verify it.
                    if (submitted != null && (!submitted.form.consumed.get() ||
                        error !is NativeVpnFailure || !error.mayHaveSubmitted))
                        discardVerification(submitted)
                    step = null; password.setText(""); captcha.setText(""); image.setImageDrawable(null)
                    image.visibility = View.GONE
                    captchaNote.visibility = View.VISIBLE
                    captchaNote.text = "当前验证码不可用，请重新获取表单；这不表示登录成功。"
                    status.text = (error as? NativeVpnFailure)?.explanation
                        ?: NativeVpnFailure.from(error, "U01 处理应用内登录").explanation
                    updateControls()
                    updateDiagnostics()
                    if (loginSubmission) showFailureDialog(status.text.toString())
                })
            }
            } finally { activeActions.decrementAndGet() }
        } } catch (_: RuntimeException) {
            activeActions.decrementAndGet()
            busy = false
            status.text = "无法启动本机登录组件，请返回应用后重试"
            updateControls()
        }
    }

    private fun show(next: NativeVpnStep) {
        val sameForm = step?.form != null && step?.form === next.form
        step = next
        val result = next.result
        status.text = result.detail
        LoginStatusStore(activity).record(LibraryRoute.WEBVPN, result)
        RuntimeStore(activity).apply {
            session(result.state, result.detail)
            prefs.edit().putString("session_route", "WEBVPN").apply()
            if (result.state == "valid") prefs.edit().putString("active_route", "WEBVPN").apply()
        }
        verifyOwnedCredentials(result)
        if (result.state == "valid") {
            password.setText(""); captcha.setText("")
            onVerified(); return
        }
        val newKind = next.form?.kind
        if (kind != newKind && newKind != null) {
            kind = newKind
            val account = store(newKind)
            val saved = account.read()
            username.setText(saved?.username ?: account.savedUsername())
            password.setText(saved?.password.orEmpty())
            remember.isChecked = account.configured
        }
        val sso = newKind == NativeVpnForm.Kind.SSO
        username.hint = if (sso) "统一认证学工号" else "WebVPN 图书馆账号"
        password.hint = if (sso) "统一认证密码" else "WebVPN 图书馆密码"
        username.contentDescription = username.hint; password.contentDescription = password.hint
        title.text = if (sso) "第一步 · 学校统一认证" else "第二步 · 图书馆登录"
        submit.text = if (sso) "登录统一认证" else "登录图书馆"
        if (!sameForm) captcha.setText("")
        image.setImageDrawable(null)
        captchaNote.text = "请根据学校验证码图片填写四位数字"
        if (sso && next.image != null) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(next.image, 0, next.image.size, options)
            if (options.outWidth in 1..2048 && options.outHeight in 1..2048) {
                image.setImageBitmap(BitmapFactory.decodeByteArray(next.image, 0, next.image.size))
            } else { step = null; status.text = "验证码图片无法读取，请重新获取"; captchaNote.text = status.text }
        }
        listOf(captchaNote, captcha, fiveDays).forEach { it.visibility = if (sso) View.VISIBLE else View.GONE }
        image.visibility = if (sso && image.drawable != null) View.VISIBLE else View.GONE
        updateControls()
    }

    private fun updateControls() {
        listOf(username, password, captcha, remember, fiveDays, refresh, check).forEach { it.isEnabled = !busy && authenticationReady }
        captcha.isEnabled = !busy && authenticationReady && step?.form?.kind == NativeVpnForm.Kind.SSO && image.drawable != null
        submit.isEnabled = !busy && authenticationReady && step?.form != null
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showFailureDialog(message: String) {
        resultDialog?.dismiss()
        val builder = AlertDialog.Builder(activity).setTitle("登录未完成").setMessage(message)
            .setPositiveButton("知道了", null)
        if (BuildConfig.DEBUG) builder.setNeutralButton("复制诊断") { _, _ -> copyDiagnostics() }
        resultDialog = builder.show()
    }

    private fun updateDiagnostics() {
        if (BuildConfig.DEBUG) diagnostics.text = "最近操作：$lastOperation\n${session.diagnostics()}"
    }

    private fun copyDiagnostics() {
        if (!BuildConfig.DEBUG) return
        val safe = "原生自动登录调试版\n最近操作：$lastOperation\n结果：${status.text}\n${session.diagnostics()}"
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("登录脱敏诊断", safe))
        Toast.makeText(activity, "已复制脱敏诊断，不含账号、密码或验证码", Toast.LENGTH_SHORT).show()
    }

    private fun discardVerification(candidate: CredentialVerification) {
        if (credentialVerification === candidate) credentialVerification = null
    }

    private fun verifyOwnedCredentials(result: SessionChecker.Result) {
        val owned = credentialVerification ?: return
        val account = store(owned.form.kind)
        if (!owned.form.consumed.get() || !account.enabled || account.paused ||
            account.revision != owned.revision) {
            discardVerification(owned)
            return
        }
        val confirmed = if (owned.form.kind == NativeVpnForm.Kind.SSO)
            result.vpnAuthState == "valid" else result.state == "valid"
        if (confirmed) {
            account.markVerifiedIfRevision(owned.revision)
            discardVerification(owned)
        }
    }

    override fun close() {
        credentialVerification?.let { owned ->
            if (owned.form.consumed.get()) store(owned.form.kind).pauseIfRevision(owned.revision)
        }
        credentialVerification = null
        resultDialog?.dismiss(); resultDialog = null
        closed = true; session.close(); (worker as? ExecutorService)?.shutdown()
        password.setText(""); captcha.setText(""); image.setImageDrawable(null)
    }

    /** The visible owner's cookie lease is released only after this callback. */
    fun closeWhenIdle(onIdle: () -> Unit) {
        close()
        if (activeActions.get() == 0 && session.isQuiescent()) { onIdle(); return }
        cleanup.execute {
            while (activeActions.get() != 0) Thread.sleep(10L)
            while (!session.awaitQuiescence(1_000L)) { /* cookie callbacks must finish */ }
            main.post(onIdle)
        }
    }

    companion object {
        private val cleanup = Executors.newSingleThreadExecutor { command ->
            Thread(command, "native-login-cleanup").apply { isDaemon = true }
        }
        internal var sessionFactory: () -> NativeWebVpnSession = { NativeWebVpnSession() }
        internal var workerFactory: () -> Executor = { Executors.newSingleThreadExecutor() }
        internal var credentialStoreFactory: (android.content.Context, WebVpnCredentialStore.Kind) -> WebVpnCredentialStore =
            { context, kind -> WebVpnCredentialStore(context, kind) }
    }
}
