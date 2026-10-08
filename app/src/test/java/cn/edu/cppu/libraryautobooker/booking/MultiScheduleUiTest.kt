package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.cppu.libraryautobooker.ReleaseTimesField
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
class MultiScheduleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addAndDeleteTimeKeepAtLeastOneEntry() {
        val times = mutableStateOf(listOf(360))
        compose.setContent { MaterialTheme { ReleaseTimesField(times.value) { times.value = it } } }
        compose.onNodeWithText("添加抢座时间").performClick()
        assertEquals(listOf(360, 420), times.value)
        compose.onNodeWithText("删除时间 2").performClick()
        assertEquals(listOf(360), times.value)
        compose.onNodeWithText("删除时间 1").assertDoesNotExist()
    }
}
