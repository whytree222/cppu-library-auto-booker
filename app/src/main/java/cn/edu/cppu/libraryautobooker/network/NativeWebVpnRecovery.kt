package cn.edu.cppu.libraryautobooker.network

import android.content.Context
import android.os.SystemClock
import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.ocr.CaptchaPagePolicy
import cn.edu.cppu.libraryautobooker.ocr.DdddOcrEngine
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded native recovery. Construct/run/close on a worker; cancel may run on main. */
internal class NativeWebVpnRecovery internal constructor(
    private val session: NativeRecoverySession,
    private val accounts: NativeRecoveryAccounts,
    private val reader: NativeCaptchaReader,
    private val allowSso: Boolean,
    private val rememberFiveDays: Boolean,
    private val isOwnerCurrent: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = Thread::sleep
) : AutoCloseable {
    constructor(context: Context, allowSso: Boolean, rememberFiveDays: Boolean,
        isOwnerCurrent: () -> Boolean, onStatus: (String) -> Unit) : this(
        NativeRecoverySessionAdapter(NativeWebVpnSession()),
        StoredNativeRecoveryAccounts(context.applicationContext),
        LocalNativeCaptchaReader(context.applicationContext),
        allowSso, rememberFiveDays, isOwnerCurrent, onStatus)

    private class Attempt(val form: NativeVpnForm, val kind: Kind, val revision: String,
        var settled: Boolean = false)
    private val started = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val resourcesClosed = AtomicBoolean()
    private val attempts = mutableListOf<Attempt>() // Only the worker reads or writes these.
    private var deadline = 0L
    private var images = 0
    private var librarySubmitted = false
    private var lastState = SessionChecker.Result("unknown", "正在检查 WebVPN 登录状态")

    fun run(): SessionChecker.Result {
        check(started.compareAndSet(false, true)) { "Native recovery can run only once" }
        deadline = clock() + WINDOW_MILLIS
        try {
            requireCurrent()
            status("正在检查 WebVPN 与图书馆会话")
            var step = loadStep()
            while (true) {
                requireCurrent()
                lastState = step.result
                if (!configurationCurrent())
                    return stop("登录期间账号设置已改变，已停止本轮自动登录")
                confirmAttempts(step.result)
                if (!configurationCurrent()) return stop("确认登录期间账号设置已改变，已停止本轮自动登录")
                if (step.result.state == "valid") {
                    status("WebVPN 与图书馆登录均已确认")
                    return step.result
                }
                if (step.failureKind != NativeVpnFailureKind.NONE) {
                    when (step.failureKind) {
                        NativeVpnFailureKind.CAPTCHA_REJECTED -> {
                            settleLastCaptchaRejection()
                            if (!allowSso || images >= MAX_IMAGES) return stop("验证码尝试已达三张上限，请在应用内手动登录")
                            status("学校未接受验证码，正在等待新的验证码")
                            pauseForRefresh()
                            if (step.form?.kind != NativeVpnForm.Kind.SSO || step.image == null) step = loadStep()
                        }
                        NativeVpnFailureKind.CREDENTIAL_REJECTED -> {
                            rejectLastAccount()
                            return stop("学校未接受本次账号密码，已暂停对应账号自动登录；请核对后重新开启")
                        }
                        NativeVpnFailureKind.RATE_LIMITED -> {
                            settleLastKnownResponse()
                            return stop("学校要求稍后再试或账号被锁定，已停止自动登录")
                        }
                        NativeVpnFailureKind.EXTRA_VERIFICATION -> {
                            settleLastKnownResponse()
                            return stop("学校要求额外认证，请打开应用内登录手动处理")
                        }
                        else -> return stop("本次登录结果尚未确认，已停止自动提交；请检查登录状态")
                    }
                } else if (step.error) {
                    // A legacy/unclassified error is never permission to send the password again.
                    return stop("学校登录结果未明确，已停止自动登录；请手动检查")
                }

                val form = step.form ?: return stop("无法获取可用的学校登录表单，请在应用内手动登录")
                if (!session.validate(form, ::current)) return stop("学校登录表单或会话已经变化，已停止自动登录")
                val kind = if (form.kind == NativeVpnForm.Kind.SSO) Kind.SSO else Kind.LIBRARY
                if (kind == Kind.SSO && !allowSso) return stop("统一认证已过期，请在应用内手动输入验证码")
                val account = accounts.snapshot(kind) ?: return stop(
                    if (kind == Kind.SSO) "统一认证账号未保存开启或已暂停，请检查账号设置"
                    else "WebVPN 图书馆账号未保存开启或已暂停，请检查账号设置")
                if (!accounts.isCurrent(kind, account.revision)) return stop("账号设置已改变，已停止本轮自动登录")

                val captcha = if (kind == Kind.SSO) {
                    if (images >= MAX_IMAGES) return stop("验证码尝试已达三张上限，请在应用内手动登录")
                    val image = step.image ?: return stop("当前验证码图片不可用，请在应用内手动登录")
                    images++
                    status("正在本机识别验证码（$images/$MAX_IMAGES），图片不会上传")
                    val recognized = try { reader.recognize(image) }
                    finally { image.fill(0) }
                    requireCurrent()
                    if (!accounts.isCurrent(kind, account.revision) || !session.validate(form, ::current))
                        return stop("识别期间账号或登录会话已变化，旧结果已丢弃")
                    if (!CaptchaPagePolicy.accepts(recognized.text, recognized.unrestrictedText, recognized.confidence)) {
                        if (images >= MAX_IMAGES) return stop("三张验证码均未可靠识别，请在应用内手动登录")
                        status("验证码识别不可靠，正在等待换取新图片")
                        pauseForRefresh()
                        step = loadStep()
                        continue
                    }
                    recognized.text
                } else {
                    if (librarySubmitted) return stop("本轮图书馆登录已尝试，未重复提交；请检查登录状态")
                    ""
                }

                requireCurrent()
                if (!configurationCurrent() || !accounts.isCurrent(kind, account.revision)) return stop("账号设置已改变，未发送更多登录信息")
                val attempt = Attempt(form, kind, account.revision)
                attempts.add(attempt)
                if (kind == Kind.LIBRARY) librarySubmitted = true
                status(if (kind == Kind.SSO) "正在通过学校原生接口提交统一认证（验证码 $images/$MAX_IMAGES）"
                    else "统一认证已通过，正在登录图书馆")
                step = session.submit(form, account.username, account.password, captcha, rememberFiveDays) {
                    current() && configurationCurrent() && accounts.isCurrent(kind, account.revision)
                }
            }
        } catch (failure: NativeVpnFailure) {
            return when (failure.kind) {
                NativeVpnFailureKind.CAPTCHA_REJECTED -> {
                    settleLastCaptchaRejection()
                    stop("学校未接受验证码且未提供可继续的表单，请在应用内手动登录")
                }
                NativeVpnFailureKind.CREDENTIAL_REJECTED -> {
                    rejectLastAccount()
                    stop("学校未接受本次账号密码，已暂停对应账号自动登录；请核对后重新开启")
                }
                NativeVpnFailureKind.RATE_LIMITED -> {
                    settleLastKnownResponse()
                    stop("学校要求稍后再试或账号被锁定，已停止自动登录")
                }
                NativeVpnFailureKind.EXTRA_VERIFICATION -> {
                    settleLastKnownResponse()
                    stop("学校要求额外认证，请打开应用内登录手动处理")
                }
                else -> stop(if (failure.mayHaveSubmitted)
                    "登录已提交但结果未确认，已停止并暂停对应账号自动恢复；请先检查登录状态"
                    else failure.explanation)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return stop("自动登录已取消；如已提交，请先检查登录状态")
        } catch (_: Exception) {
            return stop("原生自动登录未完成，请在应用内检查登录状态或手动登录")
        } catch (_: LinkageError) {
            return stop("本机验证码识别组件不可用，请在应用内手动登录")
        } catch (_: OutOfMemoryError) {
            return stop("本机识别资源不足，请在应用内手动登录")
        } finally {
            // Cancellation is not evidence that a consumed form was never sent.
            attempts.filter { !it.settled && it.form.consumed.get() }.forEach {
                accounts.pause(it.kind, it.revision)
            }
            attempts.clear()
        }
    }

    private fun current() = !cancelled.get() && isOwnerCurrent() && clock() < deadline
    private fun configurationCurrent() = attempts.all { accounts.isCurrent(it.kind, it.revision) }
    private fun loadStep(): NativeVpnStep {
        requireCurrent()
        status("正在获取学校登录表单及当前验证码")
        return session.load()
    }
    private fun requireCurrent() {
        if (!current()) throw NativeVpnFailure(
            if (clock() >= deadline) "原生自动登录超过 90 秒，已停止" else "本轮自动登录已取消或由手动登录接管",
            kind = NativeVpnFailureKind.CANCELLED)
    }
    private fun status(message: String) { if (current()) onStatus(message) }
    private fun stop(detail: String): SessionChecker.Result = SessionChecker.Result(
        if (lastState.vpnAuthState == "valid") "expired" else if (lastState.state == "vpn_login_required") "vpn_login_required" else "unknown",
        detail, vpnAuthState = lastState.vpnAuthState)

    private fun pauseForRefresh() {
        val until = clock() + REFRESH_DELAY_MILLIS
        while (clock() < until) {
            requireCurrent()
            sleep(minOf(100L, until - clock()))
        }
        requireCurrent()
    }

    private fun confirmAttempts(result: SessionChecker.Result) {
        for (attempt in attempts) {
            if (!attempt.settled && attempt.form.consumed.get() &&
                (if (attempt.kind == Kind.SSO) result.vpnAuthState == "valid" else result.state == "valid")) {
                accounts.markVerified(attempt.kind, attempt.revision)
                attempt.settled = true
                if (attempt.kind == Kind.SSO) status("学校统一认证会话已确认")
            }
        }
    }
    private fun settleLastCaptchaRejection() {
        attempts.lastOrNull()?.takeIf { it.kind == Kind.SSO }?.settled = true
    }
    private fun settleLastKnownResponse() { attempts.lastOrNull()?.settled = true }
    private fun rejectLastAccount() {
        attempts.lastOrNull()?.let {
            if (it.form.consumed.get()) accounts.pause(it.kind, it.revision)
            it.settled = true
        }
    }

    /** Quick cancellation only. The owning worker must close OCR and await Cookie drain. */
    fun cancel() { cancelled.set(true); session.cancel() }
    fun awaitQuiescence(timeoutMs: Long): Boolean = session.awaitQuiescence(timeoutMs)
    override fun close() {
        cancel()
        if (resourcesClosed.compareAndSet(false, true)) reader.close()
    }

    companion object {
        const val WINDOW_MILLIS = 90_000L
        const val MAX_IMAGES = 3
        const val REFRESH_DELAY_MILLIS = 1_500L
    }
}

/** Minimal injectable seams; no implementation logs or persists their contents. */
internal interface NativeRecoverySession {
    fun load(): NativeVpnStep
    fun validate(form: NativeVpnForm, guard: () -> Boolean): Boolean
    fun submit(form: NativeVpnForm, username: String, password: String, captcha: String,
        remember: Boolean, guard: () -> Boolean): NativeVpnStep
    fun cancel()
    fun awaitQuiescence(timeoutMs: Long): Boolean
}

internal class NativeRecoveryAccount(val revision: String, val username: String, val password: String) {
    override fun toString() = "NativeRecoveryAccount(credentials=<redacted>)"
}
internal interface NativeRecoveryAccounts {
    fun snapshot(kind: Kind): NativeRecoveryAccount?
    fun isCurrent(kind: Kind, revision: String): Boolean
    fun markVerified(kind: Kind, revision: String)
    fun pause(kind: Kind, revision: String)
}
internal interface NativeCaptchaReader : AutoCloseable {
    fun recognize(image: ByteArray): DdddOcrEngine.Result
}

private class NativeRecoverySessionAdapter(private val session: NativeWebVpnSession) : NativeRecoverySession {
    override fun load() = session.load()
    override fun validate(form: NativeVpnForm, guard: () -> Boolean) = session.validate(form, guard)
    override fun submit(form: NativeVpnForm, username: String, password: String, captcha: String,
        remember: Boolean, guard: () -> Boolean) = session.submit(form, username, password, captcha, remember, guard)
    override fun cancel() = session.close()
    override fun awaitQuiescence(timeoutMs: Long) = session.awaitQuiescence(timeoutMs)
}
private class StoredNativeRecoveryAccounts(context: Context) : NativeRecoveryAccounts {
    private val stores = Kind.entries.associateWith { WebVpnCredentialStore(context, it) }
    override fun snapshot(kind: Kind): NativeRecoveryAccount? {
        val store = stores.getValue(kind)
        val revision = store.revision
        val value = store.read() ?: return null
        return if (isCurrent(kind, revision)) NativeRecoveryAccount(revision, value.username, value.password) else null
    }
    override fun isCurrent(kind: Kind, revision: String) = stores.getValue(kind).let {
        it.enabled && !it.paused && it.revision == revision
    }
    override fun markVerified(kind: Kind, revision: String) { stores.getValue(kind).markVerifiedIfRevision(revision) }
    override fun pause(kind: Kind, revision: String) { stores.getValue(kind).pauseIfRevision(revision) }
}
private class LocalNativeCaptchaReader(private val context: Context) : NativeCaptchaReader {
    private var engine: DdddOcrEngine? = null
    override fun recognize(image: ByteArray): DdddOcrEngine.Result =
        (engine ?: DdddOcrEngine(context).also { engine = it }).recognize(image, digitsOnly = true)
    override fun close() { engine?.close(); engine = null }
}
