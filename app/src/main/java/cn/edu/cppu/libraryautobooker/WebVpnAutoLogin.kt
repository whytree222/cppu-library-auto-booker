package cn.edu.cppu.libraryautobooker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.data.NativeWebVpnAutoSettings
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import cn.edu.cppu.libraryautobooker.network.NativeWebVpnRecovery
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal interface WebVpnRecoveryTask : AutoCloseable {
    fun run(): SessionChecker.Result
    fun cancel()
    fun awaitQuiescence(timeoutMs: Long): Boolean
}

/** Single shared native run. Retired writers retain ownership until I/O and cookies drain. */
object WebVpnAutoLogin {
    private var run: Run? = null
    private val main = Handler(Looper.getMainLooper())
    private val interactiveOwners = linkedSetOf<Any>()
    val running get() = run?.active == true
    internal val interactiveLoginOpen get() = interactiveOwners.isNotEmpty()
    internal val authenticationBusy get() = WebVpnAuthOwnership.busy
    internal var workerFactory: () -> Executor = { Executors.newSingleThreadExecutor() }
    internal var wallClock: () -> Long = System::currentTimeMillis
    internal var recoveryFactory: (Context, Boolean, Boolean, () -> Boolean, (String) -> Unit) -> WebVpnRecoveryTask =
        { app, allowSso, remember, owner, status ->
            val native = NativeWebVpnRecovery(app, allowSso, remember, owner, status)
            object : WebVpnRecoveryTask {
                override fun run() = native.run()
                override fun cancel() = native.cancel()
                override fun close() = native.close()
                override fun awaitQuiescence(timeoutMs: Long) = native.awaitQuiescence(timeoutMs)
            }
        }

    /** Visible forms must wait asynchronously; Cookie callbacks also need the main thread. */
    fun acquireInteractiveLogin(onReady: (Boolean) -> Unit = {}): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = Any()
        val alreadyVisible = interactiveOwners.isNotEmpty()
        interactiveOwners.add(owner)
        SessionChecker.invalidateAuthentication(LibraryRoute.WEBVPN)
        run?.stop(SessionChecker.Result("unknown", "自动登录已停止，正在交由应用内手动登录处理"), false)
        var closed = false
        var delivered = false
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        fun waitReady() {
            if (closed || delivered) return
            if (alreadyVisible) { delivered = true; onReady(false); return }
            val bookingBusy = cn.edu.cppu.libraryautobooker.booking.BookingService.running
            val reserved = !bookingBusy && (WebVpnAuthOwnership.owns(owner) || WebVpnAuthOwnership.reserve(owner))
            if (reserved && WebVpnAuthOwnership.ready(owner)) {
                delivered = true; onReady(true)
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                if (reserved) WebVpnAuthOwnership.release(owner)
                delivered = true; onReady(false)
            } else main.postDelayed({ waitReady() }, 25L)
        }
        main.post { waitReady() }
        return AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!closed) {
                closed = true; interactiveOwners.remove(owner); WebVpnAuthOwnership.release(owner)
            }
        }
    }

    fun hasEnabledAccount(context: Context) = Kind.entries.any {
        val store = WebVpnCredentialStore(context, it)
        store.enabled && !store.paused
    }
    private fun accountKey(context: Context) = NativeWebVpnAutoSettings(context).enabled.toString() + "|" +
        Kind.entries.joinToString("|") {
            val store = WebVpnCredentialStore(context, it)
            "${store.revision}:${store.enabled}:${store.paused}"
        }
    private fun coolingDown(context: Context): Boolean {
        val prefs = context.getSharedPreferences("native_webvpn_recovery", Context.MODE_PRIVATE)
        return prefs.getString("failure_key", "") == accountKey(context) &&
            wallClock() < prefs.getLong("retry_after", 0L)
    }
    fun canRecover(context: Context, result: SessionChecker.Result, statusOnly: Boolean = false): Boolean {
        if (interactiveLoginOpen || (run != null && !running)) return false
        if (running) return result.state in setOf("expired", "vpn_login_required", "logging_in")
        val kind = when {
            result.state == "vpn_login_required" -> Kind.SSO
            result.state == "expired" && result.vpnAuthState == "valid" -> Kind.LIBRARY
            else -> return false
        }
        val store = WebVpnCredentialStore(context, kind)
        return (kind != Kind.SSO || NativeWebVpnAutoSettings(context).enabled) &&
            store.enabled && !store.paused && !coolingDown(context)
    }
    fun recoveryUnavailableReason(context: Context, result: SessionChecker.Result): String? {
        val kind = when {
            result.state == "vpn_login_required" -> Kind.SSO
            result.state == "expired" && result.vpnAuthState == "valid" -> Kind.LIBRARY
            else -> return null
        }
        val store = WebVpnCredentialStore(context, kind)
        val name = if (kind == Kind.SSO) "统一认证" else "WebVPN 图书馆"
        return when {
            interactiveLoginOpen -> "应用内登录页面正在处理认证，不启动另一轮自动登录"
            run != null && !running -> "上一轮认证正在收尾，请稍后重试或打开应用内登录"
            kind == Kind.SSO && !NativeWebVpnAutoSettings(context).enabled -> "全流程自动登录未开启，请在应用内手动输入验证码，或保存开启两套账号"
            !store.enabled -> "$name 自动登录尚未保存开启"
            store.paused -> "$name 自动登录已暂停，请核对学校结果后在账号设置中允许重试"
            coolingDown(context) -> "本轮自动登录未完成，五分钟冷却后可再次尝试；也可手动登录"
            else -> null
        }
    }
    fun settingsChanged(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        SessionChecker.invalidateAuthentication(LibraryRoute.WEBVPN)
        run?.stop(SessionChecker.Result("unknown", "账号或自动登录设置正在修改，本轮认证已停止"), false)
    }
    fun ensure(context: Context, updateSession: Boolean = true, callback: (SessionChecker.Result) -> Unit): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (interactiveLoginOpen || (run != null && !running)) {
            callback(SessionChecker.Result("unknown", "应用内登录或上一轮认证正在收尾，不启动另一轮后台登录"))
            return AutoCloseable {}
        }
        if (run == null && coolingDown(context)) {
            callback(SessionChecker.Result("unknown", "自动登录尚在五分钟冷却期，可手动登录"))
            return AutoCloseable {}
        }
        val existing = run
        val target = existing ?: Run(context.applicationContext).also { run = it }
        val listener = Listener(callback, updateSession)
        target.listeners.add(listener)
        val subscription = AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!listener.cancelled) { listener.cancelled = true; target.unsubscribe(listener) }
        }
        if (existing == null) target.start()
        return subscription
    }
    fun cancel() { run?.stop(SessionChecker.Result("unknown", "自动 WebVPN 登录已取消，可手动继续"), false) }
    private class Listener(val callback: (SessionChecker.Result) -> Unit, val updateSession: Boolean,
        var cancelled: Boolean = false)

    private class Run(private val app: Context) {
        val listeners = linkedSetOf<Listener>()
        private val stateLock = Any()
        @Volatile var active = true
            private set
        @Volatile private var recovery: WebVpnRecoveryTask? = null
        private var workerStarted = false
        private var published = false
        private var released = false
        private val worker = workerFactory()
        private val deadline = SystemClock.elapsedRealtime() + 90_000L
        private val startingAccountKey = accountKey(app)
        private val updatesSession get() = listeners.any { !it.cancelled && it.updateSession }
        private val timeout = Runnable {
            stop(SessionChecker.Result("unknown", "原生自动登录超过90秒，已停止；如已提交登录则请先核对状态，不会重发"), true)
        }
        fun start() {
            if (!WebVpnAuthOwnership.reserve(this)) {
                stop(SessionChecker.Result("unknown", "认证会话正在使用中，请稍后重试"), false); return
            }
            SessionChecker.invalidateAuthentication(LibraryRoute.WEBVPN)
            status("正在检查 WebVPN 与图书馆会话")
            main.postDelayed(timeout, 90_000L)
            fun launchWhenReady() {
                if (!active) return
                if (!WebVpnAuthOwnership.ready(this)) { main.postDelayed({ launchWhenReady() }, 25L); return }
                try { worker.execute { execute() } }
                catch (_: RuntimeException) { stop(SessionChecker.Result("unknown", "无法启动本机认证组件"), true) }
            }
            launchWhenReady()
        }
        private fun execute() {
            synchronized(stateLock) { if (!active) return; workerStarted = true }
            var result = SessionChecker.Result("unknown", "本机自动登录未完成，可手动登录")
            var task: WebVpnRecoveryTask? = null
            try {
                task = recoveryFactory(app, NativeWebVpnAutoSettings(app).enabled,
                    app.getSharedPreferences("webvpn_ocr", Context.MODE_PRIVATE).getBoolean("remember_me", true),
                    { active && WebVpnAuthOwnership.owns(this) && SystemClock.elapsedRealtime() < deadline },
                    { detail -> main.post { if (active && WebVpnAuthOwnership.owns(this)) status(detail) } })
                recovery = task
                if (active) result = task.run() else task.cancel()
            } catch (_: Exception) { result = SessionChecker.Result("unknown", "原生自动登录未完成，已停止；请检查登录状态或手动登录") }
            catch (_: LinkageError) { result = SessionChecker.Result("unknown", "本机登录组件不可用，请使用应用内手动登录") }
            finally {
                runCatching { task?.close() }
                Thread.interrupted() // Cancellation may interrupt a fake/platform wait; drain still owns the lease.
                // The worker retains the lease through OCR shutdown and all cookie receipts.
                fun awaitDrain(timeoutMs: Long): Boolean {
                    Thread.interrupted()
                    return try { task?.awaitQuiescence(timeoutMs) ?: true }
                    catch (_: Exception) {
                        // An interrupted/failed wait is not proof that Cookie writes finished.
                        Thread.interrupted()
                        runCatching { Thread.sleep(1000L) }
                        false
                    }
                }
                var drained = awaitDrain(10_000L)
                if (!drained) main.post {
                    if (active) stop(SessionChecker.Result("unknown", "认证请求仍在收尾，未继续预约；请稍后核对状态"), true)
                }
                while (!drained) drained = awaitDrain(1_000L)
                val completed = result
                main.post {
                    val current = active && WebVpnAuthOwnership.owns(this) && accountKey(app) == startingAccountKey
                    release()
                    if (active) {
                        active = false
                        val accepted = if (current || completed.state != "valid") completed
                            else SessionChecker.Result("unknown", "账号或认证所有权已变化，旧成功结果未用于继续预约")
                        publish(accepted, accepted.state != "valid")
                    }
                }
                (worker as? ExecutorService)?.shutdown()
            }
        }
        fun stop(result: SessionChecker.Result, cooldown: Boolean) {
            val started: Boolean
            synchronized(stateLock) { if (!active) return; active = false; started = workerStarted }
            recovery?.cancel()
            if (!started) { release(); (worker as? ExecutorService)?.shutdownNow() }
            publish(result, cooldown)
        }
        private fun status(detail: String) {
            if (updatesSession) RuntimeStore(app).session("logging_in", detail)
            LoginStatusStore(app).recovering(detail)
            if (updatesSession && cn.edu.cppu.libraryautobooker.booking.BookingService.running)
                RuntimeStore(app).record("RUNNING", detail)
        }
        private fun publish(result: SessionChecker.Result, cooldown: Boolean) {
            if (published) return
            published = true; main.removeCallbacks(timeout)
            val prefs = app.getSharedPreferences("native_webvpn_recovery", Context.MODE_PRIVATE)
            if (result.state == "valid") prefs.edit().clear().apply()
            else if (cooldown) prefs.edit().putString("failure_key", accountKey(app))
                .putLong("retry_after", wallClock() + 300_000L).apply()
            if (updatesSession) {
                RuntimeStore(app).session(result.state, result.detail)
                RuntimeStore(app).prefs.edit().putString("session_route", "WEBVPN").apply()
                if (result.state == "valid") RuntimeStore(app).prefs.edit().putString("active_route", "WEBVPN").apply()
            }
            LoginStatusStore(app).record(LibraryRoute.WEBVPN, result)
            val pending = listeners.toList(); listeners.clear()
            pending.forEach { if (!it.cancelled) it.callback(result) }
        }
        private fun release() {
            if (released) return
            released = true; WebVpnAuthOwnership.release(this)
            if (run === this) run = null
        }
        fun unsubscribe(listener: Listener) {
            if (!listeners.remove(listener) || listeners.isNotEmpty() || !active) return
            stop(SessionChecker.Result("unknown", "自动 WebVPN 登录已停止，结果尚未确认"), false)
        }
    }
}
