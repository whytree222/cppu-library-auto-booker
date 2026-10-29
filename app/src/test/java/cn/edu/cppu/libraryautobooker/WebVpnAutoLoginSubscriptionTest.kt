package cn.edu.cppu.libraryautobooker

import android.app.Application
import android.os.Looper
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebVpnAutoLoginSubscriptionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var harness: QueuedNativeRecoveryHarness

    @Before fun setup() { harness = QueuedNativeRecoveryHarness(app); harness.clearPreferences() }
    @After fun cleanup() { harness.close() }

    @Test fun foregroundPreferredCheckJoinsExistingRecoveryWithoutStartingDirectProbe() {
        harness.result = SessionChecker.Result("valid", "synthetic-verified", vpnAuthState = "valid")
        var automatic = 0; var foreground = 0
        WebVpnAutoLogin.ensure(app) { automatic++ }
        val check = SessionCoordinator.check(app) { valid, _ -> assertTrue(valid); foreground++ }
        assertEquals("logging_in", RuntimeStore(app).prefs.getString("session_state", ""))
        harness.runAllWorkers()
        assertEquals(1, harness.created)
        assertEquals(1, automatic); assertEquals(1, foreground)
        check.close()
    }

    @Test fun releasingOneSubscriberDoesNotCancelAnotherOrNotifyTheReleasedTask() {
        var first = 0
        var second = 0
        val firstRequest = WebVpnAutoLogin.ensure(app) { first++ }
        val secondRequest = WebVpnAutoLogin.ensure(app) { second++ }
        assertTrue(WebVpnAutoLogin.running)
        firstRequest.close()
        firstRequest.close()
        assertTrue(WebVpnAutoLogin.running)
        assertEquals(0, first)
        assertEquals(0, second)
        WebVpnAutoLogin.cancel()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, first)
        assertEquals(1, second)
        secondRequest.close()
        assertEquals(1, second)
    }

    @Test fun releasingLastSubscriberStopsRunWithoutCancellationCallbackAndAllowsFreshOwnership() {
        var callbacks = 0
        val request = WebVpnAutoLogin.ensure(app) { callbacks++ }
        assertTrue(WebVpnAutoLogin.running)
        request.close()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, callbacks)
        val next = WebVpnAutoLogin.ensure(app) { callbacks++ }
        assertTrue(WebVpnAutoLogin.running)
        request.close() // A stale handle must never stop the new run.
        assertTrue(WebVpnAutoLogin.running)
        next.close()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, callbacks)
    }

    @Test fun releasingAnotherSubscriberDuringDeliverySuppressesItsPendingCallback() {
        var secondCallbacks = 0
        lateinit var second: AutoCloseable
        WebVpnAutoLogin.ensure(app) { second.close() }
        second = WebVpnAutoLogin.ensure(app) { secondCallbacks++ }
        WebVpnAutoLogin.cancel()
        assertFalse(WebVpnAutoLogin.running)
        assertEquals(0, secondCallbacks)
    }

    @Test fun canceledProbeCannotAttachRecoveryOrDeliverItsLateResult() {
        val request = SessionCoordinator.CheckRequest()
        var closed = 0
        var delivered = 0
        request.close()
        request.attach(AutoCloseable { closed++ })
        request.deliver { delivered++ }
        assertFalse(request.active)
        assertEquals(1, closed)
        assertEquals(0, delivered)
    }

    @Test fun normalCompletionReleasesRecoveryExactlyOnceAndRejectsRepeatedCallbacks() {
        val request = SessionCoordinator.CheckRequest()
        var closed = 0
        var delivered = 0
        request.attach(AutoCloseable { closed++ })
        request.deliver { delivered++ }
        request.deliver { delivered++ }
        request.close()
        assertEquals(1, closed)
        assertEquals(1, delivered)
        assertFalse(request.active)
    }

    @Test fun visibleLoginCancelsHiddenOwnerAndRejectsLateRecoveryUntilClosed() {
        var oldCallbacks = 0
        val old = WebVpnAutoLogin.ensure(app) { oldCallbacks++ }
        val visible = WebVpnAutoLogin.acquireInteractiveLogin()
        try {
            assertFalse(WebVpnAutoLogin.running)
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
            assertEquals(1, oldCallbacks)
            var late: SessionChecker.Result? = null
            val blocked = WebVpnAutoLogin.ensure(app) { late = it }
            assertEquals("unknown", late!!.state)
            assertTrue(late!!.detail.contains("不启动另一轮"))
            assertFalse(WebVpnAutoLogin.running)
            blocked.close(); old.close()
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
        } finally { visible.close(); old.close() }
        assertFalse(WebVpnAutoLogin.interactiveLoginOpen)
        val next = WebVpnAutoLogin.ensure(app) {}
        assertTrue(WebVpnAutoLogin.running)
        visible.close() // An old release must not cancel a newer recovery.
        assertTrue(WebVpnAutoLogin.running)
        next.close()
    }

    @Test fun eachVisibleOwnerMustCloseAndStaleReleaseCannotUnlockAnother() {
        val first = WebVpnAutoLogin.acquireInteractiveLogin()
        val second = WebVpnAutoLogin.acquireInteractiveLogin()
        try {
            first.close(); first.close()
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
            val expired = SessionChecker.Result("vpn_login_required", "expired", vpnAuthState = "expired")
            assertFalse(WebVpnAutoLogin.canRecover(app, expired))
            assertTrue(WebVpnAutoLogin.recoveryUnavailableReason(app, expired)!!.contains("登录页面"))
        } finally { first.close(); second.close() }
        assertFalse(WebVpnAutoLogin.interactiveLoginOpen)
    }

    @Test fun queuedCancellationCannotConstructNativeSessionOrTouchTheNetwork() {
        val request = WebVpnAutoLogin.ensure(app) {}
        assertEquals(1, harness.queue.pending.size)
        assertEquals(0, harness.created)
        request.close()
        harness.runAllWorkers()
        assertEquals(0, harness.created)
        assertFalse(WebVpnAutoLogin.authenticationBusy)
    }

    @Test fun aStartedCanceledRunKeepsOwnershipUntilTheWorkerFinishesDrain() {
        harness.enable(Kind.LIBRARY)
        var callbacks = 0
        var blocked: SessionChecker.Result? = null
        harness.onRun = {
            WebVpnAutoLogin.cancel()
            assertFalse(WebVpnAutoLogin.running)
            assertTrue(WebVpnAutoLogin.authenticationBusy)
            WebVpnAutoLogin.ensure(app) { blocked = it }.close()
            assertEquals("unknown", blocked!!.state)
            assertTrue(blocked!!.detail.contains("收尾"))
        }
        WebVpnAutoLogin.ensure(app) { callbacks++ }
        harness.queue.runNext()
        assertEquals(1, callbacks)
        assertTrue(WebVpnAutoLogin.authenticationBusy) // Main-thread drain completion is still pending.
        assertFalse(WebVpnAutoLogin.canRecover(app, SessionChecker.Result("expired", "expired", vpnAuthState = "valid")))
        harness.idleMain()
        assertFalse(WebVpnAutoLogin.authenticationBusy)
        assertTrue(WebVpnAutoLogin.canRecover(app, SessionChecker.Result("expired", "expired", vpnAuthState = "valid")))
        assertEquals(1, harness.created)
    }

    @Test fun aLateSuccessfulWorkerResultCannotNotifyAgainAfterCancellation() {
        val delivered = mutableListOf<SessionChecker.Result>()
        harness.result = SessionChecker.Result("valid", "synthetic-verified", vpnAuthState = "valid")
        harness.onRun = { WebVpnAutoLogin.cancel() }
        WebVpnAutoLogin.ensure(app) { delivered.add(it) }
        harness.runAllWorkers()
        assertEquals(1, delivered.size)
        assertEquals("unknown", delivered.single().state)
        assertFalse(WebVpnAutoLogin.running)
        assertFalse(WebVpnAutoLogin.authenticationBusy)
    }

    @Test fun visibleTakeoverWaitsForStartedRecoveryDrainBeforeItsFormCanLoad() {
        var ready: Boolean? = null
        var visible: AutoCloseable? = null
        harness.onRun = {
            visible = WebVpnAutoLogin.acquireInteractiveLogin { ready = it }
            assertNull(ready)
            assertTrue(WebVpnAutoLogin.authenticationBusy)
        }
        WebVpnAutoLogin.ensure(app) {}
        try {
            harness.queue.runNext()
            assertNull(ready)
            harness.idleMain() // The first readiness poll observes the retiring owner, then release runs.
            assertNull(ready)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25))
            assertEquals(true, ready)
            assertEquals(1, harness.closedTasks)
            assertTrue(WebVpnAutoLogin.authenticationBusy) // Now held by the visible owner.
        } finally { visible?.close(); harness.idleMain() }
        assertFalse(WebVpnAutoLogin.authenticationBusy)
    }

    @Test fun sharedStatusAndBookingSubscribersReceiveOneResultAndOnlyBookingUpdatesPreferredRoute() {
        val runtime = RuntimeStore(app)
        runtime.session("valid", "direct-valid")
        runtime.prefs.edit().putString("active_route", "DIRECT").putString("session_route", "DIRECT").commit()
        harness.result = SessionChecker.Result("valid", "synthetic-verified", vpnAuthState = "valid")
        var statusCallbacks = 0; var bookingCallbacks = 0
        WebVpnAutoLogin.ensure(app, updateSession = false) { statusCallbacks++ }
        WebVpnAutoLogin.ensure(app, updateSession = true) { bookingCallbacks++ }
        harness.runAllWorkers()
        assertEquals(1, harness.created)
        assertEquals(1, statusCallbacks); assertEquals(1, bookingCallbacks)
        assertEquals("WEBVPN", runtime.prefs.getString("active_route", ""))
        assertEquals("valid", runtime.prefs.getString("session_state", ""))
    }

    @Test fun canceledBookingSubscriberCannotMakeStatusOnlyCompletionReplaceTheDirectRoute() {
        val runtime = RuntimeStore(app)
        runtime.session("valid", "direct-valid")
        runtime.prefs.edit().putString("active_route", "DIRECT").putString("session_route", "DIRECT").commit()
        harness.result = SessionChecker.Result("valid", "synthetic-verified", vpnAuthState = "valid")
        var statusCallbacks = 0; var canceledCallbacks = 0
        WebVpnAutoLogin.ensure(app, updateSession = false) { statusCallbacks++ }
        val booking = WebVpnAutoLogin.ensure(app, updateSession = true) { canceledCallbacks++ }
        booking.close()
        harness.runAllWorkers()
        assertEquals(1, statusCallbacks); assertEquals(0, canceledCallbacks)
        assertEquals("DIRECT", runtime.prefs.getString("active_route", ""))
        assertEquals("DIRECT", runtime.prefs.getString("session_route", ""))
        assertEquals("direct-valid", runtime.prefs.getString("session_detail", ""))
    }

    @Test fun onlyFirstVisibleFormCanBecomeReadyAndStaleReleaseCannotUnlockIt() {
        var firstReady: Boolean? = null; var secondReady: Boolean? = null
        val first = WebVpnAutoLogin.acquireInteractiveLogin { firstReady = it }
        val second = WebVpnAutoLogin.acquireInteractiveLogin { secondReady = it }
        try {
            harness.idleMain()
            assertEquals(true, firstReady); assertEquals(false, secondReady)
            assertTrue(WebVpnAutoLogin.authenticationBusy)
            second.close()
            assertTrue(WebVpnAutoLogin.authenticationBusy)
            assertTrue(WebVpnAutoLogin.interactiveLoginOpen)
        } finally { second.close(); first.close(); harness.idleMain() }
        assertFalse(WebVpnAutoLogin.authenticationBusy)
    }
}

/** No ensure() in these tests can construct a real native client or start network I/O. */
internal class QueuedNativeRecoveryHarness(private val app: Application) : AutoCloseable {
    private val oldWorker = WebVpnAutoLogin.workerFactory
    private val oldRecovery = WebVpnAutoLogin.recoveryFactory
    val queue = QueueExecutor()
    var result = SessionChecker.Result("unknown", "synthetic-failure")
    var onRun: () -> Unit = {}
    var onDrain: (Int) -> Boolean = { true }
    var created = 0
    var canceled = 0
    var closedTasks = 0
    var drains = 0
    val ssoPermissions = mutableListOf<Boolean>()

    init {
        WebVpnAutoLogin.cancel()
        idleMain()
        WebVpnAutoLogin.workerFactory = { queue }
        WebVpnAutoLogin.recoveryFactory = { _, allowSso, _, _, _ ->
            created++; ssoPermissions.add(allowSso)
            object : WebVpnRecoveryTask {
                override fun run(): SessionChecker.Result { onRun(); return result }
                override fun cancel() { canceled++ }
                override fun close() { closedTasks++ }
                override fun awaitQuiescence(timeoutMs: Long): Boolean = onDrain(++drains)
            }
        }
    }
    fun clearPreferences() {
        Kind.entries.forEach { app.getSharedPreferences(it.storageName, 0).edit().clear().commit() }
        listOf("webvpn_ocr", "native_webvpn_auto_login", "native_webvpn_recovery", "runtime_status", "login_status")
            .forEach { app.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    fun enable(kind: Kind, paused: Boolean = false, enabled: Boolean = true) {
        app.getSharedPreferences(kind.storageName, 0).edit().putString("encrypted", "synthetic-placeholder")
            .putString("iv", "synthetic-placeholder").putBoolean("enabled", enabled).putBoolean("paused", paused)
            .putString("revision", java.util.UUID.randomUUID().toString()).commit()
    }
    fun idleMain() { Shadows.shadowOf(Looper.getMainLooper()).idle() }
    fun runAllWorkers() {
        repeat(20) {
            if (queue.pending.isEmpty()) { idleMain(); return }
            queue.runNext(); idleMain()
        }
        error("Unexpected endlessly queued recovery work")
    }
    override fun close() {
        WebVpnAutoLogin.cancel()
        runAllWorkers()
        WebVpnAutoLogin.workerFactory = oldWorker
        WebVpnAutoLogin.recoveryFactory = oldRecovery
        clearPreferences()
    }
    internal class QueueExecutor : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun runNext() { pending.removeFirst().run() }
    }
}
