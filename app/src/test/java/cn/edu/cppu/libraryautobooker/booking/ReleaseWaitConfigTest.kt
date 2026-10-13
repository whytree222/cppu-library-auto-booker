package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.BookingConfig
import cn.edu.cppu.libraryautobooker.data.ConfigStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReleaseWaitConfigTest {
    @Test fun waitIsOptInAndPersists() {
        val store = ConfigStore(RuntimeEnvironment.getApplication())
        assertFalse(store.load().waitForRelease)
        store.save(BookingConfig(waitForRelease = true))
        assertTrue(store.load().waitForRelease)
        assertTrue(AutomationScript.build("__BOOKING_CONFIG__", store.load()).contains("\"waitForRelease\":true"))
    }

    @Test fun appendKeepsSameDateSeatAndWaitOption() {
        val sequence = BookingSequence(BookingConfig(dryRun = false, waitForRelease = true,
            appendLastThree = true, selectedSlots = listOf(0, 1, 2, 3), seatNumbers = listOf("G015A")))
        assertTrue(sequence.success(1, "G015A") is BookingSequence.Result.Next)
        assertTrue(sequence.config.waitForRelease)
        assertEquals(listOf("G015A"), sequence.config.seatNumbers)
        assertEquals(listOf(4, 5, 6), sequence.config.selectedSlots)
        assertTrue(sequence.failure("10 分钟上限").detail.contains("前四段已成功"))
    }
}

