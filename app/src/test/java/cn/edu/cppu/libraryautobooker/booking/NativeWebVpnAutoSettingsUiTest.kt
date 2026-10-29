package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.WebVpnAccountSettings
import cn.edu.cppu.libraryautobooker.WebVpnAccountsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeWebVpnAutoSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullFlowSwitchCanBeDisabledWithoutChangingAccountToggles() {
        var enabled by mutableStateOf(true)
        var accountChanges = 0
        val saved = WebVpnAccountSettings(configured = true, enabled = true)
        compose.setContent { MaterialTheme {
            WebVpnAccountsScreen(saved, saved, "", { _, _, _ -> true }, { _, _ -> accountChanges++ },
                {}, {}, {}, {}, autoLoginEnabled = enabled, onAutoLoginChange = { enabled = it })
        } }
        compose.onNodeWithContentDescription("WebVPN 全流程自动登录开关").performScrollTo().performClick()
        compose.onNodeWithText("已关闭 · 统一认证验证码改为手动输入").assertIsDisplayed()
        assertFalse(enabled)
        assertEquals(0, accountChanges)
    }

    @Test fun pausedAccountIsExplainedWhileManualEntryRemainsAvailable() {
        var manualOpened = false
        compose.setContent { MaterialTheme {
            WebVpnAccountsScreen(WebVpnAccountSettings(configured = true, enabled = true, paused = true),
                WebVpnAccountSettings(configured = true, enabled = true), "", { _, _, _ -> true }, { _, _ -> },
                {}, {}, { manualOpened = true }, {}, autoLoginEnabled = true)
        } }
        compose.onNodeWithText("有账号处于暂停状态，应用不会擅自恢复；请核对后允许该账号重新尝试。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("打开 WebVPN 验证两层登录").performScrollTo().performClick()
        assertTrue(manualOpened)
    }
}
