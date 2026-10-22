package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.PermissionsPage
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
class PermissionsPageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingPermissionsPageDoesNotRequestAnythingAndKeepsBookingControlsOut() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                PermissionsPage(false, true, false, "尚未进行后台唤醒测试",
                    onExactAlarm = { actions += "alarm" }, onNotifications = { actions += "notifications" },
                    onAppSettings = { actions += "app" }, onBatterySettings = { actions += "battery" },
                    onPermissionGuide = { actions += "guide" }, onWakeTest = { actions += "test" })
            }
        }
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("permissions-page").assertIsDisplayed()
        compose.onNodeWithText("权限设置").assertIsDisplayed()
        compose.onNodeWithText("未允许 · 需开启").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("需手动确认").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("尚未进行后台唤醒测试").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存并安排任务").assertDoesNotExist()
        compose.onNodeWithText("最近运行记录").assertDoesNotExist()
        compose.onNodeWithText("登录状态").assertDoesNotExist()
    }

    @Test fun eachExplicitActionUsesItsOwnCallbackAndTestFeedbackIsShown() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                PermissionsPage(true, false, true, "测试计划：10-22 08:05:00", "测试已安排，不会预约",
                    onExactAlarm = { actions += "alarm" }, onNotifications = { actions += "notifications" },
                    onAppSettings = { actions += "app" }, onBatterySettings = { actions += "battery" },
                    onPermissionGuide = { actions += "guide" }, onWakeTest = { actions += "test" })
            }
        }
        listOf("设置闹钟和提醒", "通知授权 / 设置", "打开系统应用设置", "查看系统省电设置",
            "重新查看权限引导", "60 秒后台唤醒测试（不预约）").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("alarm", "notifications", "app", "battery", "guide", "test"), actions)
        }
        compose.onNodeWithText("测试已安排，不会预约").performScrollTo().assertIsDisplayed()
    }

    @Test fun permissionStatusesRefreshAfterReturningFromSystemSettings() {
        val exact = mutableStateOf(false)
        val notifications = mutableStateOf(false)
        val battery = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                PermissionsPage(exact.value, notifications.value, battery.value, "尚未进行后台唤醒测试",
                    onExactAlarm = {}, onNotifications = {}, onAppSettings = {},
                    onBatterySettings = {}, onPermissionGuide = {}, onWakeTest = {})
            }
        }
        compose.onNodeWithText("未允许 · 需开启").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未允许").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未开启").performScrollTo().assertIsDisplayed()

        // The Activity re-reads these values on resume after a system settings visit.
        compose.runOnIdle {
            exact.value = true
            notifications.value = true
            battery.value = true
        }
        compose.onNodeWithText("未允许 · 需开启").assertDoesNotExist()
        compose.onNodeWithText("未允许").assertDoesNotExist()
        compose.onNodeWithText("未开启").assertDoesNotExist()
        compose.onAllNodesWithText("已允许").assertCountEquals(2)
        compose.onNodeWithText("已开启").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("需手动确认").performScrollTo().assertIsDisplayed()
    }
}
