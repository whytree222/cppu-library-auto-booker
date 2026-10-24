package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.LoginStatusCard
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
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

    @Test fun onlyCampusLoginStatusAndDetailsAreShown() {
        val entry = LoginStatusStore.Entry("expired", "请重新登录图书馆", 1000)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            LoginStatusCard(LoginStatusStore.Snapshot(entry))
        } } }
        compose.onNodeWithText("校园网直连 · 图书馆").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("WebVPN · 图书馆登录").assertDoesNotExist()
        compose.onNodeWithText("未登录 / 已过期").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查看登录详情").performScrollTo().performClick()
        compose.onNodeWithText("请重新登录图书馆").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("权限与后台运行").assertDoesNotExist()
    }
}
