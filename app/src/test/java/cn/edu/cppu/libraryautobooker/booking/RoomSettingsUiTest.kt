package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.RoomSettingsCard
import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.RoomProfile
import cn.edu.cppu.libraryautobooker.resetRoomDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class RoomSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val room2 = RoomProfile("31", "二楼测试阅览室", "2", "二楼", "free")

    @Test fun browsingFloorDoesNotChangeTargetButChoosingRoomDoes() {
        val selected = mutableStateOf(RoomProfile.DEFAULT)
        var selectedCount = 0
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RoomSettingsCard(selected.value, listOf(RoomProfile.DEFAULT, room2), {
                    selectedCount++
                    selected.value = it
                }, {})
            }
        } }
        compose.onNodeWithText("浏览楼层：一楼 ▾").performScrollTo().performClick()
        compose.onNodeWithText("二楼").performClick()
        assertEquals(0, selectedCount)
        assertEquals(RoomProfile.DEFAULT, selected.value)
        compose.onNodeWithText("选择这一层的阅览室 ▾").performScrollTo().performClick()
        compose.onNodeWithText("二楼测试阅览室").performClick()
        assertEquals(1, selectedCount)
        assertEquals(room2, selected.value)
        compose.onNodeWithText("当前预约：二楼 · 二楼测试阅览室").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("预约方式：填写开始和结束时间").performScrollTo().assertIsDisplayed()
    }

    @Test fun syncingIsExplicitAndDoesNotSilentlySelectAnotherRoom() {
        var syncCount = 0
        var selectedCount = 0
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RoomSettingsCard(RoomProfile.DEFAULT, listOf(RoomProfile.DEFAULT, room2),
                    { selectedCount++ }, { syncCount++ })
            }
        } }
        compose.onNodeWithText("预约方式：选择该房间的固定时段（共 7 个）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("时段来源：缓存，尚未按使用日期核对；学校未开放或同步失败时保留缓存，预约时仍精确核对学校网页。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("从学校同步阅览室").performScrollTo().performClick()
        assertEquals(1, syncCount)
        assertEquals(0, selectedCount)
    }

    @Test fun roomChangeClearsOldReservationTargetAndScheduleButKeepsPreferences() {
        val original = BookingConfig(enabled = true, dryRun = false, reserveTomorrow = false,
            seatNumbers = listOf("G015A"), selectedSlots = listOf(0, 1, 2, 3), appendLastThree = true,
            releaseTimes = listOf(60, 90), scheduledTimes = listOf(1000L, 2000L), scheduledAtMillis = 1000L,
            waitForRelease = true)
        val changed = resetRoomDraft(original, room2)
        assertEquals(room2, changed.room)
        assertTrue(changed.seatNumbers.isEmpty())
        assertTrue(changed.seatChoices.isEmpty())
        assertTrue(changed.selectedSlots.isEmpty())
        assertFalse(changed.appendLastThree)
        assertFalse(changed.enabled)
        assertTrue(changed.pendingTimes().isEmpty())
        assertEquals(0L, changed.scheduledAtMillis)
        assertEquals(original.releaseTimes, changed.releaseTimes)
        assertEquals(original.waitForRelease, changed.waitForRelease)
        assertEquals(original.dryRun, changed.dryRun)
        assertEquals(original.reserveTomorrow, changed.reserveTomorrow)
    }

    @Test fun changedRulesForSameRoomAlsoClearSelections() {
        val updated = RoomProfile.DEFAULT.copy(slots = RoomProfile.DEFAULT.slots.dropLast(1))
        val changed = resetRoomDraft(BookingConfig(seatNumbers = listOf("G015A"),
            selectedSlots = listOf(0, 1), appendLastThree = false), updated)
        assertEquals(updated, changed.room)
        assertTrue(changed.seatNumbers.isEmpty())
        assertTrue(changed.selectedSlots.isEmpty())
    }
}
