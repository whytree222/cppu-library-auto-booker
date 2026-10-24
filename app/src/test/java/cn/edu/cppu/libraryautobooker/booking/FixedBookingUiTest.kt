package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.cppu.libraryautobooker.BookingActionsCard
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

    @Test fun directAndScheduledActionsStayDistinctAndNeverRunOnPageOpen() {
        var direct = 0
        var scheduled = 0
        compose.setContent { MaterialTheme { Column {
            BookingActionsCard(
                scheduled = false, running = false, directEnabled = true, scheduledEnabled = true,
                status = "", onDirect = { direct++ }, onSchedule = { scheduled++ },
                onCancelSchedule = {}, onStop = {}
            )
        } } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        assertEquals(0, direct); assertEquals(0, scheduled)
        compose.onNodeWithText("直接预约").performClick()
        assertEquals(1, direct); assertEquals(0, scheduled)
        compose.onNodeWithText("预约 · 下次 00:58 启动").performClick()
        assertEquals(1, direct); assertEquals(1, scheduled)
        compose.onNodeWithText("立即预约所选座位").assertDoesNotExist()
        compose.onNodeWithText("演练模式").assertDoesNotExist()
    }

    @Test fun cancelAndStopAppearOnlyInMatchingStates() {
        var cancelled = 0
        var stopped = 0
        compose.setContent { MaterialTheme { Column {
            BookingActionsCard(
                scheduled = true, running = true, directEnabled = false, scheduledEnabled = false,
                status = "已安排一次预约", onDirect = {}, onSchedule = {},
                onCancelSchedule = { cancelled++ }, onStop = { stopped++ }
            )
        } } }
        compose.onNodeWithText("取消已安排预约").assertIsDisplayed().performClick()
        compose.onNodeWithText("停止当前任务").assertIsDisplayed().performClick()
        assertEquals(1, cancelled); assertEquals(1, stopped)
        compose.onNodeWithText("已安排一次预约").assertIsDisplayed()
    }
}
