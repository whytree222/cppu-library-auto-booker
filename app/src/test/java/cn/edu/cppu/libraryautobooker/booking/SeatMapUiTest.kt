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
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SeatMapUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeSeatTapAddsAndCancelsDraftWithoutSubmittingAnything() {
        val selected = mutableStateOf(emptyList<String>())
        val map = RoomSeatMap(RoomProfile.DEFAULT, 1, 2,
            listOf(MapSeat("G001A",0,0),MapSeat("G001B",0,1)), "2026-10-21")
        compose.setContent { MaterialTheme { SeatMapCard(map,selected.value) { selected.value=it } } }
        compose.onNodeWithText("G001A").performClick()
        compose.runOnIdle { assertEquals(listOf("G001A"),selected.value) }
        compose.onNodeWithText("G001B").performClick()
        compose.runOnIdle { assertEquals(listOf("G001A","G001B"),selected.value) }
        compose.onNodeWithText("G001A").performClick()
        compose.runOnIdle { assertEquals(listOf("G001B"),selected.value) }
    }
    @Test fun ambiguityIsVisiblyDisabledAndEmptyMapDoesNotGuess() {
        val map = RoomSeatMap(RoomProfile.DEFAULT,1,2,
            listOf(MapSeat("G001A",0,0),MapSeat("G001A",0,1)),"2026-10-21")
        compose.setContent { MaterialTheme { SeatMapCard(map,emptyList()) { fail("Ambiguous selection") } } }
        compose.onAllNodesWithText("⚠\nG001A")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("⚠\nG001A")[1].assertIsNotEnabled()
    }
}

