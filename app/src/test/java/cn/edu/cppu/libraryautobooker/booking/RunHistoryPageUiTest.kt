package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.RunHistoryPage
import cn.edu.cppu.libraryautobooker.data.RuntimeStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RunHistoryPageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyHistoryExplainsRetentionAndDoesNotShowBookingOrPermissionControls() {
        compose.setContent { MaterialTheme { RunHistoryPage(emptyList()) } }
        compose.onNodeWithText("运行记录").assertIsDisplayed()
        compose.onNodeWithText("暂无运行记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("仅保留最近 3 次任务，超出后自动清除最早一次。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成一次演练或安排预约后，任务记录会显示在这里。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存并安排任务").assertDoesNotExist()
        compose.onNodeWithText("权限与后台运行").assertDoesNotExist()
    }

    @Test fun recordDetailsCanBeExpandedAndLiveResultsAreDisplayed() {
        val history = mutableStateOf(listOf(RuntimeStore.RunRecord("run-1", "立即演练", 1000L,
            "RUNNING", listOf("闹钟收到", "正在检查网页"))))
        compose.setContent { MaterialTheme { RunHistoryPage(history.value) } }
        compose.onNodeWithText("正在检查网页").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("闹钟收到").assertDoesNotExist()
        compose.onNodeWithText("查看步骤（2）").performScrollTo().performClick()
        compose.onNodeWithText("闹钟收到").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("收起步骤").performScrollTo().performClick()
        compose.onNodeWithText("闹钟收到").assertDoesNotExist()
        compose.runOnIdle {
            history.value = listOf(history.value.single().copy(state = "DONE",
                lines = listOf("闹钟收到", "正在检查网页", "演练完成，未提交预约")))
        }
        compose.onNodeWithText("演练完成，未提交预约").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("查看步骤（3）").performScrollTo().assertIsDisplayed()
    }
}
