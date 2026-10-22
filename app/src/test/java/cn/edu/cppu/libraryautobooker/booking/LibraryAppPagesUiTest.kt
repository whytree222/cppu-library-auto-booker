package cn.edu.cppu.libraryautobooker.booking

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.cppu.libraryautobooker.LibraryAppPages
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryAppPagesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pagesAreSeparateAndSwitchingDoesNotLoseReservationDraft() {
        val draft = mutableStateOf("尚未选座")
        compose.setContent { MaterialTheme {
            LibraryAppPages(
                booking = {
                    OutlinedButton(onClick = { draft.value = "已选 G015A · 明天 · 前四段" }) {
                        Text(draft.value)
                    }
                },
                permissions = { Text("仅权限设置") },
                history = { Text("仅运行记录") }
            )
        } }
        compose.onNodeWithText("尚未选座").performClick()
        compose.onNodeWithTag("page-permissions").performClick().assertIsSelected()
        compose.onNodeWithText("仅权限设置").assertIsDisplayed()
        compose.onNodeWithText("已选 G015A · 明天 · 前四段").assertDoesNotExist()
        compose.onNodeWithText("仅运行记录").assertDoesNotExist()
        compose.onNodeWithTag("page-history").performClick().assertIsSelected()
        compose.onNodeWithText("仅运行记录").assertIsDisplayed()
        compose.onNodeWithText("仅权限设置").assertDoesNotExist()
        compose.onNodeWithTag("page-booking").performClick().assertIsSelected()
        compose.onNodeWithText("已选 G015A · 明天 · 前四段").assertIsDisplayed()
    }

    @Test fun eachPageRemembersItsUiAndSelectedPageRestores() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            LibraryAppPages(
                booking = {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    OutlinedButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "预约详情已展开" else "展开预约详情")
                    }
                },
                permissions = { Text("权限页内容") },
                history = { Text("记录页内容") }
            )
        } }
        compose.onNodeWithText("展开预约详情").performClick()
        compose.onNodeWithTag("page-history").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("page-history").assertIsSelected()
        compose.onNodeWithText("记录页内容").assertIsDisplayed()
        compose.onNodeWithTag("page-booking").performClick()
        compose.onNodeWithText("预约详情已展开").assertIsDisplayed()
    }

    @Test fun backFromSecondaryPageReturnsToBookingWithoutAnotherAction() {
        lateinit var back: OnBackPressedDispatcher
        compose.setContent { MaterialTheme {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            LibraryAppPages(booking = { Text("预约页内容") }, permissions = { Text("权限页内容") },
                history = { Text("记录页内容") })
        } }
        compose.onNodeWithTag("page-permissions").performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("page-booking").assertIsSelected()
        compose.onNodeWithText("预约页内容").assertIsDisplayed()
        compose.onNodeWithTag("page-history").performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithTag("page-booking").assertIsSelected()
        compose.onNodeWithText("记录页内容").assertDoesNotExist()
    }
}
