package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.LoginStatusCard
import cn.edu.cppu.libraryautobooker.WebVpnCard
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StatusCardsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directLoginCardShowsOnlyDirectStatusAndDetails() {
        val entry = LoginStatusStore.Entry("expired", "请重新登录图书馆", 1000)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            LoginStatusCard(LoginStatusStore.Snapshot(entry, LoginStatusStore.Entry()))
        } } }
        compose.onNodeWithText("校园网直连 · 图书馆").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("WebVPN · 图书馆登录").assertDoesNotExist()
        compose.onNodeWithText("未登录 / 已过期").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查看登录详情").performScrollTo().performClick()
        compose.onNodeWithText("请重新登录图书馆").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("权限与后台运行").assertDoesNotExist()
    }

    @Test fun webVpnCardShowsManualLoginStatusAndFallbackSwitch() {
        var enabled by mutableStateOf(false)
        var logins = 0
        var checks = 0
        var accounts = 0
        val statuses = LoginStatusStore.Snapshot(
            LoginStatusStore.Entry("unknown", "尚未检查", 0),
            LoginStatusStore.Entry("vpn_login_required", "请完成 WebVPN 网页登录", 2000))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            WebVpnCard(statuses, enabled, { enabled = it }, { logins++ }, { checks++ }, { accounts++ })
        } } }
        compose.onNodeWithText("WebVPN 校外备用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("需网页登录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("请完成 WebVPN 网页登录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("直连失败时自动切换").performScrollTo().assertIsOff()
            .performClick().assertIsOn()
        compose.onNodeWithText("WebVPN 应用内登录").performScrollTo().performClick()
        compose.onNodeWithText("检查 WebVPN").performScrollTo().performClick()
        compose.onNodeWithText("WebVPN 账号设置").performScrollTo().performClick()
        assertTrue(enabled)
        assertEquals(1, logins)
        assertEquals(1, checks)
        assertEquals(1, accounts)
    }
}
