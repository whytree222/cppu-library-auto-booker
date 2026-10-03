package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.EntryLoginScreen
import cn.edu.cppu.libraryautobooker.data.CredentialStore
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
        compose.onNode(hasText("自动重新登录") and hasClickAction()).performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(CredentialStore(RuntimeEnvironment.getApplication()).preferredAutoLogin)
        compose.onNodeWithText("最近运行记录").assertDoesNotExist()
        compose.onNodeWithText("每天放号时间").assertDoesNotExist()
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
}

