package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cn.edu.cppu.libraryautobooker.SeatMapCard
import cn.edu.cppu.libraryautobooker.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h1600dp")
@LooperMode(LooperMode.Mode.PAUSED)
class SeatMapUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeSeatTapAddsAndCancelsDraftWithoutSubmittingAnything() {
        val selected = mutableStateOf(emptyList<String>())
        val map = RoomSeatMap(RoomProfile.DEFAULT, 1, 2,
            listOf(MapSeat("G001A",0,0),MapSeat("G001B",0,1)), "2026-10-21")
        compose.setContent { MaterialTheme { SeatMapCard(map,selected.value) { selected.value=it } } }
        compose.onNodeWithText("尚未选择座位，请在座位图中点击添加。").assertIsDisplayed()
        compose.onNodeWithTag("seat-0-0").performClick()
        compose.runOnIdle { assertEquals(listOf("G001A"),selected.value) }
        compose.onNodeWithTag("seat-0-1").performClick()
        compose.runOnIdle { assertEquals(listOf("G001A","G001B"),selected.value) }
        compose.onNodeWithTag("seat-0-0").performClick()
        compose.runOnIdle { assertEquals(listOf("G001B"),selected.value) }
        compose.onNodeWithText("尚未选择座位，请在座位图中点击添加。").assertDoesNotExist()
    }
    @Test fun ambiguityIsVisiblyDisabledAndEmptyMapDoesNotGuess() {
        val map = RoomSeatMap(RoomProfile.DEFAULT,1,2,
            listOf(MapSeat("G001A",0,0),MapSeat("G001A",0,1)),"2026-10-21")
        compose.setContent { MaterialTheme { SeatMapCard(map,emptyList()) { fail("Ambiguous selection") } } }
        compose.onNodeWithTag("seat-0-0").assertIsNotEnabled()
        compose.onNodeWithTag("seat-0-1").assertIsNotEnabled()
        compose.onNodeWithText("尚未选择座位，请在座位图中点击添加。").assertIsDisplayed()
    }
}
