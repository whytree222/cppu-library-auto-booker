package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.cppu.libraryautobooker.FixedBookingButton
import cn.edu.cppu.libraryautobooker.FixedBookingInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FixedBookingUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onlyOneBookingButtonExplicitlyExplainsSchedulingAndDoesNotStartUntilClicked() {
        var requests = 0
        compose.setContent { MaterialTheme { Column {
            FixedBookingInfo()
            FixedBookingButton { requests++ }
        } } }
        assertEquals(0, requests)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("预约启动：下次 00:58").assertIsDisplayed()
        compose.onNodeWithText("选择时间").assertDoesNotExist()
        compose.onNodeWithText("立即预约所选座位").assertDoesNotExist()
        compose.onNodeWithText("演练模式").assertDoesNotExist()
        compose.onNodeWithText("预约 · 下次 00:58 启动").performClick()
        assertEquals(1, requests)
    }
}
