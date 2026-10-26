package cn.edu.cppu.libraryautobooker.booking

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import cn.edu.cppu.libraryautobooker.EntryLoginScreen
import cn.edu.cppu.libraryautobooker.data.CredentialStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EntryLoginUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entryShowsLoginOnlyAndCheckboxDefaultsOn() {
        compose.setContent { MaterialTheme { EntryLoginScreen(sessionCheck = { _, callback -> callback("expired") }) {} } }
        compose.onNodeWithText("学校账号").assertIsDisplayed()
        compose.onNodeWithText("密码").assertIsDisplayed()
        compose.onNodeWithText("登录并进入应用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("WebVPN 应用内登录（夜间/校外备用）").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("自动重新登录") and hasClickAction()).performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(CredentialStore(RuntimeEnvironment.getApplication()).preferredAutoLogin)
        compose.onNodeWithText("最近运行记录").assertDoesNotExist()
        compose.onNodeWithText("每天放号时间").assertDoesNotExist()
    }

    @Test fun passwordHintSaysDefaultIsStudentIdAndInputErrorClearsOnEdit() {
        compose.setContent { MaterialTheme { EntryLoginScreen(sessionCheck = { _, callback -> callback("expired") }) {} } }
        compose.onNodeWithText("默认密码是学号").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("登录并进入应用").performScrollTo().performClick()
        compose.onNodeWithText("请填写账号和密码").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("密码").performScrollTo().performTextInput("secret")
        compose.onNodeWithText("请填写账号和密码").assertDoesNotExist()
    }

    @Test fun verifiedExistingSessionCanContinueWithoutEnteringPassword() {
        var entered = false
        compose.setContent { MaterialTheme { EntryLoginScreen(sessionCheck = { _, callback -> callback("valid") }) { entered = true } } }
        compose.waitForIdle()
        assertTrue(entered)
        compose.onNodeWithText("学校账号").assertDoesNotExist()
        assertFalse(CredentialStore(RuntimeEnvironment.getApplication()).enabled)
    }

    @Test fun unknownNetworkDoesNotPermitUnverifiedEntry() {
        var entered = false
        compose.setContent { MaterialTheme { EntryLoginScreen(sessionCheck = { _, callback -> callback("unknown") }) { entered = true } } }
        compose.onNodeWithText("继续进入应用").assertDoesNotExist()
        compose.onNodeWithText("学校账号").assertDoesNotExist()
        compose.onNodeWithText("重新检查").assertIsDisplayed()
        assertFalse(entered)
    }

    @Test fun webVpnAuthenticationGateOffersManualWebVpnLogin() {
        compose.setContent { MaterialTheme { EntryLoginScreen(sessionCheck = { _, callback ->
            callback("vpn_login_required")
        }) {} } }
        compose.onNodeWithText("WebVPN 统一认证已过期，请在应用内登录并输入验证码").assertIsDisplayed()
        compose.onNodeWithText("WebVPN 应用内登录").assertIsDisplayed()
        compose.onNodeWithText("学校账号").assertDoesNotExist()
    }

    @Test fun checkingDoesNotFlashLoginFieldsAndRetryCanEnterAutomatically() {
        var checks = 0
        var entered = false
        compose.setContent { MaterialTheme { EntryLoginScreen(sessionCheck = { _, callback ->
            checks++
            callback(if (checks == 1) "unknown" else "valid")
        }) { entered = true } } }
        compose.onNodeWithText("学校账号").assertDoesNotExist()
        compose.onNodeWithText("重新检查").performClick()
        compose.waitForIdle()
        assertTrue(entered)
        assertEquals(2, checks)
    }

    @Test fun explicitAccountManagementStaysAccessibleForValidSession() {
        var entered = false
        compose.setContent { MaterialTheme { EntryLoginScreen(autoEnterValid = false,
            sessionCheck = { _, callback -> callback("valid") }) { entered = true } } }
        compose.onNodeWithText("学校账号").assertIsDisplayed()
        assertFalse(entered)
        compose.onNodeWithText("继续进入应用").performScrollTo().performClick()
        assertTrue(entered)
    }

    @Test fun pendingCheckShowsNoLoginForm() {
        var respond: ((String) -> Unit)? = null
        var entered = false
        compose.setContent { MaterialTheme { EntryLoginScreen(sessionCheck = { _, callback -> respond = callback }) { entered = true } } }
        compose.onNodeWithText("正在检查登录状态…").assertIsDisplayed()
        compose.onNodeWithText("学校账号").assertDoesNotExist()
        compose.runOnIdle { requireNotNull(respond)("valid") }
        assertTrue(entered)
    }

    @Test fun disposedEntryIgnoresLateSuccessfulCheck() {
        var visible by mutableStateOf(true)
        var respond: ((String) -> Unit)? = null
        var entered = false
        compose.setContent {
            if (visible) MaterialTheme {
                EntryLoginScreen(sessionCheck = { _, callback -> respond = callback }) { entered = true }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { requireNotNull(respond)("valid") }
        assertFalse(entered)
    }

    @Test fun pausedEntryRejectsOldCallbackAndResumeStartsExactlyOneFreshCheck() {
        lateinit var lifecycleContext: EntryLifecycleContext
        val responses = mutableListOf<(String) -> Unit>()
        var entered = false
        compose.setContent {
            val base = LocalContext.current
            val scoped = remember { EntryLifecycleContext(base).also { lifecycleContext = it } }
            CompositionLocalProvider(LocalContext provides scoped) {
                MaterialTheme {
                    EntryLoginScreen(sessionCheck = { _, callback -> responses.add(callback) }) { entered = true }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, responses.size) // Initial ON_RESUME must not create a duplicate check.
        compose.runOnIdle {
            lifecycleContext.event(Lifecycle.Event.ON_PAUSE)
            responses[0]("valid")
        }
        assertFalse(entered)
        compose.runOnIdle { lifecycleContext.event(Lifecycle.Event.ON_RESUME) }
        compose.waitForIdle()
        assertEquals(2, responses.size)
        compose.runOnIdle { responses[0]("valid") }
        assertFalse(entered)
        compose.runOnIdle { responses[1]("valid") }
        assertTrue(entered)
    }

    @Test fun coalescedPauseAndResumeStillInvalidatesOldCallbackAndRechecks() {
        lateinit var lifecycleContext: EntryLifecycleContext
        val responses = mutableListOf<(String) -> Unit>()
        var entered = false
        compose.setContent {
            val base = LocalContext.current
            val scoped = remember { EntryLifecycleContext(base).also { lifecycleContext = it } }
            CompositionLocalProvider(LocalContext provides scoped) {
                MaterialTheme {
                    EntryLoginScreen(sessionCheck = { _, callback -> responses.add(callback) }) { entered = true }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            lifecycleContext.event(Lifecycle.Event.ON_PAUSE)
            lifecycleContext.event(Lifecycle.Event.ON_RESUME)
            responses[0]("valid")
        }
        compose.waitForIdle()
        assertFalse(entered)
        assertEquals(2, responses.size)
    }
}

/** Synthetic lifecycle around the real test context; no school network or credentials. */
private class EntryLifecycleContext(base: Context) : ContextWrapper(base), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    init {
        event(Lifecycle.Event.ON_CREATE)
        event(Lifecycle.Event.ON_START)
        event(Lifecycle.Event.ON_RESUME)
    }
    fun event(event: Lifecycle.Event) { registry.handleLifecycleEvent(event) }
}
