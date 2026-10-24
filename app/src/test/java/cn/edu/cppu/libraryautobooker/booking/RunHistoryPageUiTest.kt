package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.RunHistoryPage
import cn.edu.cppu.libraryautobooker.LibraryAppPages
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
        compose.onNodeWithText("安排或执行一次预约后，结果会显示在这里。")
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

    @Test fun savedExpansionStaysWithItsRecordWhenANewerRunArrivesOnAnotherPage() {
        val history = mutableStateOf(listOf(RuntimeStore.RunRecord("old-run", "旧任务", 1000L,
            "DONE", listOf("旧任务的闹钟收到", "旧任务完成"))))
        compose.setContent {
            MaterialTheme {
                LibraryAppPages(
                    booking = { Text("预约设置内容") },
                    permissions = { Text("权限设置内容") },
                    history = { RunHistoryPage(history.value) }
                )
            }
        }
        compose.onNodeWithTag("page-history").performClick()
        compose.onNodeWithText("查看步骤（2）").performScrollTo().performClick()
        compose.onNodeWithText("旧任务的闹钟收到").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("page-booking").performClick()
        compose.onNodeWithText("预约设置内容").assertIsDisplayed()
        compose.runOnIdle {
            history.value = history.value + RuntimeStore.RunRecord("new-run", "新任务", 2000L,
                "DONE", listOf("新任务的服务启动", "新任务完成"))
        }
        compose.onNodeWithTag("page-history").performClick()
        compose.onNodeWithText("新任务完成").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("新任务的服务启动").assertDoesNotExist()
        compose.onNodeWithText("查看步骤（2）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("旧任务的闹钟收到").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("收起步骤").performScrollTo().assertIsDisplayed()
    }
}
