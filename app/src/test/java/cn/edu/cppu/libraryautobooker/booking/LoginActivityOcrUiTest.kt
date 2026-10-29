package cn.edu.cppu.libraryautobooker.booking

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import cn.edu.cppu.libraryautobooker.LoginActivity
import cn.edu.cppu.libraryautobooker.WebVpnNativeForm
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore
import cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind
import cn.edu.cppu.libraryautobooker.network.NativeLoginFixture
import cn.edu.cppu.libraryautobooker.network.NativeVpnTransport
import cn.edu.cppu.libraryautobooker.network.NativeVpnResponse
import cn.edu.cppu.libraryautobooker.network.NativeWebVpnSession
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec

/** Replaces the old web-page OCR controls with the requested native/manual flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LoginActivityOcrUiTest {
    private lateinit var fixture: NativeLoginFixture
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val app get() = RuntimeEnvironment.getApplication()
    private fun account(kind: Kind) = WebVpnCredentialStore(app, kind, { key })
    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("webvpn_ocr", 0).edit().clear().commit()
        cn.edu.cppu.libraryautobooker.data.WebVpnCredentialStore.Kind.entries.forEach {
            RuntimeEnvironment.getApplication().getSharedPreferences(it.storageName, 0).edit().clear().commit()
        }
        WebVpnNativeForm.sessionFactory = { NativeLoginFixture().also { fixture = it }.client }
        WebVpnNativeForm.workerFactory = { Executor { it.run() } }
        WebVpnNativeForm.credentialStoreFactory = { context, kind -> WebVpnCredentialStore(context, kind, { key }) }
    }
    @After fun resetFactories() {
        WebVpnNativeForm.sessionFactory = { cn.edu.cppu.libraryautobooker.network.NativeWebVpnSession() }
        WebVpnNativeForm.workerFactory = { Executors.newSingleThreadExecutor() }
        WebVpnNativeForm.credentialStoreFactory = { context, kind -> WebVpnCredentialStore(context, kind) }
    }
    private fun open() = Robolectric.buildActivity(LoginActivity::class.java,
        Intent(RuntimeEnvironment.getApplication(), LoginActivity::class.java).putExtra("webvpn", true)).setup().also {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun closingBeforeQueuedLoadRunsStillReleasesTheManualCookieLease() {
        val queued = java.util.ArrayDeque<Runnable>()
        WebVpnNativeForm.workerFactory = { Executor { queued.add(it) } }
        val controller = open()
        assertEquals(1, queued.size)
        assertTrue(cn.edu.cppu.libraryautobooker.WebVpnAutoLogin.authenticationBusy)
        controller.pause().stop().destroy()
        queued.removeFirst().run() // Closed wrapper must skip I/O AND decrement its action counter.
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (cn.edu.cppu.libraryautobooker.WebVpnAutoLogin.authenticationBusy && System.nanoTime() < until) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(cn.edu.cppu.libraryautobooker.WebVpnAutoLogin.authenticationBusy)
        assertFalse(cn.edu.cppu.libraryautobooker.WebVpnAutoLogin.interactiveLoginOpen)
        assertTrue(fixture.posts.isEmpty())
    }

    @Test fun checkingBeforeSubmissionKeepsTheImageAndTypedCodeWithoutSendingCredentials() {
        val controller = open()
        try {
            fun views() = children(controller.get().window.decorView)
            val code = views().filterIsInstance<EditText>().single { it.hint == "四位数字验证码" }
            code.setText("1234")
            click(controller.get(), "检查登录状态")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("1234", code.text.toString())
            assertTrue(code.isEnabled)
            assertTrue(views().filterIsInstance<ImageView>().any { it.visibility == View.VISIBLE && it.drawable != null })
            assertTrue(views().filterIsInstance<Button>().single { it.text == "登录统一认证" }.isEnabled)
            assertTrue(fixture.posts.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unknownSubmissionShowsItsErrorInADialogAndExplainsTheUnavailableCaptcha() {
        saveSyntheticAccounts()
        val controller = open()
        try {
            fixture.failPost = true
            submitSso(controller.get())
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            val message = org.robolectric.Shadows.shadowOf(dialog).message.toString()
            assertTrue(message.contains("不会自动重发"))
            assertFalse(message.contains("synthetic-password"))
            val views = children(controller.get().window.decorView)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.contains("当前验证码不可用") })
            assertFalse(views.filterIsInstance<EditText>().single { it.hint == "四位数字验证码" }.isEnabled)
            assertFalse(views.filterIsInstance<Button>().single { it.text == "登录统一认证" }.isEnabled)
            assertEquals(1, fixture.posts.size)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, fixture.posts.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun copiedDiagnosticsIdentifyTheOperationWithoutCopyingAnyTypedCredentialOrCaptcha() {
        saveSyntheticAccounts()
        val controller = open()
        try {
            fixture.failPost = true
            submitSso(controller.get())
            shadowOf(Looper.getMainLooper()).idle()
            click(controller.get(), "复制脱敏诊断")
            val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val detail = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(detail.contains("原生自动登录调试版"))
            assertTrue(detail.contains("最近操作：点击登录统一认证"))
            assertTrue(detail.contains("登录提交尝试：1"))
            assertTrue(detail.contains("P01"))
            assertFalse(detail.contains("synthetic"))
            assertFalse(detail.contains("1234"))
            assertEquals(1, fixture.posts.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun webVpnShowsNativeCaptchaFieldsAndNoWebPageOrOcrOption() {
        val controller = open()
        try {
            val views = children(controller.get().window.decorView)
            assertTrue(views.none { it is WebView })
            assertTrue(views.filterIsInstance<EditText>().any { it.hint == "四位数字验证码" })
            assertTrue(views.filterIsInstance<ImageView>().any { it.drawable != null })
            assertTrue(views.filterIsInstance<CheckBox>().single { it.text == "学校五天内自动登录" }.isChecked)
            assertFalse(views.filterIsInstance<CheckBox>().any { it.text.contains("识别") })
            assertTrue(views.filterIsInstance<Button>().any { it.text == "登录统一认证" })
            assertTrue(fixture.posts.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun nativeManualSubmitMovesToLibraryAndOnlyFinishesAfterBothLoginsAreVerified() {
        val controller = open()
        try {
            fun views() = children(controller.get().window.decorView)
            views().filterIsInstance<EditText>().single { it.hint == "统一认证学工号" }.setText("synthetic-sso")
            views().filterIsInstance<EditText>().single { it.hint == "统一认证密码" }.setText("synthetic-password")
            views().filterIsInstance<EditText>().single { it.hint == "四位数字验证码" }.setText("1234")
            views().filterIsInstance<Button>().single { it.text == "登录统一认证" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(controller.get().isFinishing)
            assertEquals(1, fixture.posts.size)
            views().filterIsInstance<EditText>().single { it.hint == "WebVPN 图书馆账号" }.setText("synthetic-library")
            views().filterIsInstance<EditText>().single { it.hint == "WebVPN 图书馆密码" }.setText("different-secret")
            views().filterIsInstance<Button>().single { it.text == "登录图书馆" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.get().isFinishing)
            assertEquals(2, fixture.posts.size)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun invalidCaptchaNeverSubmitsAndPasswordFieldsNeverEnterSavedViewState() {
        val controller = open()
        try {
            val views = children(controller.get().window.decorView)
            val fields = views.filterIsInstance<EditText>()
            fields.single { it.hint == "统一认证学工号" }.setText("synthetic-sso")
            fields.single { it.hint == "统一认证密码" }.setText("synthetic-secret")
            views.filterIsInstance<Button>().single { it.text == "登录统一认证" }.performClick()
            assertTrue(fixture.posts.isEmpty())
            fields.forEach { assertFalse(it.isSaveEnabled); assertFalse(it.isSaveFromParentEnabled) }
            val saved = android.util.SparseArray<android.os.Parcelable>()
            controller.get().window.decorView.saveHierarchyState(saved)
            for (index in 0 until saved.size()) assertFalse(saved.valueAt(index).toString().contains("synthetic-secret"))
        } finally { controller.pause().stop().destroy() }
        assertTrue(fixture.closed)
    }
    @Test fun fiveDayOptOutPersistsWhileDirectLoginRemainsSeparate() {
        val first = open()
        try { children(first.get().window.decorView).filterIsInstance<CheckBox>()
            .single { it.text == "学校五天内自动登录" }.isChecked = false }
        finally { first.pause().stop().destroy() }
        val second = open()
        try { assertFalse(children(second.get().window.decorView).filterIsInstance<CheckBox>()
            .single { it.text == "学校五天内自动登录" }.isChecked) }
        finally { second.pause().stop().destroy() }
        val direct = Robolectric.buildActivity(LoginActivity::class.java).setup()
        try { assertTrue(children(direct.get().window.decorView).any { it is WebView }) }
        finally { direct.pause().stop().destroy() }
    }

    @Test fun anExistingSessionLoadedAndCheckedDoesNotVerifyUnusedSavedPasswords() {
        fixture = NativeLoginFixture()
        establishSyntheticSession(fixture)
        WebVpnNativeForm.sessionFactory = { fixture.client }
        account(Kind.SSO).save("new-saved-sso", "new-unverified-sso-password")
        account(Kind.LIBRARY).save("new-saved-library", "new-unverified-library-password")
        val initialPosts = fixture.posts.size
        val controller = open()
        try {
            assertFalse(account(Kind.SSO).verified)
            assertFalse(account(Kind.LIBRARY).verified)
            click(controller.get(), "检查登录状态")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.get().isFinishing)
            assertFalse(account(Kind.SSO).verified)
            assertFalse(account(Kind.LIBRARY).verified)
            assertEquals(initialPosts, fixture.posts.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun eachSuccessfulNativeSubmissionVerifiesOnlyItsOwnSavedCredential() {
        saveSyntheticAccounts()
        val controller = open()
        try {
            submitSso(controller.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(account(Kind.SSO).verified)
            assertFalse(account(Kind.LIBRARY).verified)
            click(controller.get(), "登录图书馆")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(account(Kind.SSO).verified)
            assertTrue(account(Kind.LIBRARY).verified)
            assertEquals(2, fixture.posts.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun responsesQueuedBeforeAnAccountChangeCannotVerifyTheNewRevision() {
        for (changedKind in Kind.entries) {
            saveSyntheticAccounts()
            val pending = ArrayDeque<Runnable>()
            WebVpnNativeForm.workerFactory = { Executor { pending.addLast(it) } }
            val controller = open()
            try {
                pending.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle()
                submitSso(controller.get())
                pending.removeFirst().run()
                if (changedKind == Kind.LIBRARY) {
                    shadowOf(Looper.getMainLooper()).idle()
                    click(controller.get(), "登录图书馆")
                    pending.removeFirst().run()
                }
                account(changedKind).save("changed-synthetic-${changedKind.name}", "new-synthetic-secret")
                val newRevision = account(changedKind).revision
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(newRevision, account(changedKind).revision)
                assertFalse(account(changedKind).verified)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun explicitRejectionAndProvenPreSendFailureDiscardCredentialAttribution() {
        repeat(2) { failure ->
            saveSyntheticAccounts()
            val controller = open()
            try {
                if (failure == 0) fixture.rejectCaptcha = true else fixture.time += 120_001
                submitSso(controller.get())
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(account(Kind.SSO).verified)
                // A later pre-existing session must not make a rejected/unsent
                // password look verified. Establish it outside this UI submission.
                fixture.rejectCaptcha = false
                establishSyntheticSession(fixture)
                click(controller.get(), "检查登录状态")
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(controller.get().isFinishing)
                assertFalse(account(Kind.SSO).verified)
                assertFalse(account(Kind.LIBRARY).verified)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun anUnknownSubmissionCanOnlyVerifyItsUnchangedOwnerOnExplicitReadOnlyCheck() {
        repeat(2) { changeAccount ->
            saveSyntheticAccounts()
            fixture = NativeLoginFixture().apply { failPost = true }
            var acceptedLater = false
            val transport = delayedSessionTransport { acceptedLater }
            WebVpnNativeForm.sessionFactory = { NativeWebVpnSession(transport) }
            val controller = open()
            try {
                submitSso(controller.get())
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(account(Kind.SSO).verified)
                if (changeAccount == 1) account(Kind.SSO).save("changed-after-unknown", "changed-secret")
                acceptedLater = true
                click(controller.get(), "检查登录状态")
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(controller.get().isFinishing)
                assertEquals(changeAccount == 0, account(Kind.SSO).verified)
                assertFalse(account(Kind.LIBRARY).verified)
                assertEquals(1, fixture.posts.size)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun restartingTheFormDiscardsAnUnknownSubmissionBeforeAReadOnlyCheck() {
        saveSyntheticAccounts()
        fixture = NativeLoginFixture().apply { failPost = true }
        var acceptedLater = false
        val transport = delayedSessionTransport { acceptedLater }
        WebVpnNativeForm.sessionFactory = { NativeWebVpnSession(transport) }
        val controller = open()
        try {
            submitSso(controller.get()); shadowOf(Looper.getMainLooper()).idle()
            click(controller.get(), "换一张验证码 / 重新获取表单")
            shadowOf(Looper.getMainLooper()).idle()
            acceptedLater = true
            click(controller.get(), "检查登录状态")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(account(Kind.SSO).verified)
            assertFalse(account(Kind.LIBRARY).verified)
            assertEquals(1, fixture.posts.size)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun closingTheNativeFormDiscardsItsQueuedVerificationCallback() {
        saveSyntheticAccounts()
        val pending = ArrayDeque<Runnable>()
        WebVpnNativeForm.workerFactory = { Executor { pending.addLast(it) } }
        val controller = open()
        pending.removeFirst().run(); shadowOf(Looper.getMainLooper()).idle()
        submitSso(controller.get()); pending.removeFirst().run()
        controller.pause().stop().destroy()
        account(Kind.SSO).save("new-after-close", "new-after-close-secret")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(account(Kind.SSO).verified)
        assertFalse(account(Kind.LIBRARY).verified)
    }

    private fun saveSyntheticAccounts() {
        account(Kind.SSO).save("synthetic-sso", "synthetic-password")
        account(Kind.LIBRARY).save("synthetic-library", "different-secret")
    }
    private fun submitSso(activity: LoginActivity) {
        children(activity.window.decorView).filterIsInstance<EditText>()
            .single { it.hint == "四位数字验证码" }.setText("1234")
        click(activity, "登录统一认证")
    }
    private fun click(activity: LoginActivity, label: String) {
        children(activity.window.decorView).filterIsInstance<Button>().single { it.text == label }.performClick()
    }
    private fun establishSyntheticSession(server: NativeLoginFixture) {
        val sso = server.client.load().form!!
        val library = server.client.submit(sso, "existing-session-user", "existing-secret", "1234", false).form!!
        server.client.submit(library, "existing-library-user", "existing-library-secret", "", false)
    }
    private fun delayedSessionTransport(accepted: () -> Boolean): NativeVpnTransport {
        val server = fixture
        return object : NativeVpnTransport {
            override fun request(url: String, method: String, body: ByteArray?, referer: String?, follow: Boolean): NativeVpnResponse =
                if (LibraryRoute.WEBVPN.pathOf(url) == "/isLogin" && accepted())
                    NativeVpnResponse(url, 200, "{\"ReturnValue\":1}".toByteArray(), "application/json")
                else server.request(url, method, body, referer, follow)
            override fun cookieBinding(url: String) = server.cookieBinding(url)
            override fun close() = server.close()
        }
    }
    private fun children(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(children(view.getChildAt(index)))
    }
}
