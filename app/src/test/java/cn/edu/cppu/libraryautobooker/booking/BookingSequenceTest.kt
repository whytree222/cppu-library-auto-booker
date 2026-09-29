package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.BookingConfig
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BookingSequenceTest {
    private val today = LocalDate.of(2026, 9, 29)
    private val plan = BookingConfig(dryRun = false, seatNumbers = listOf("G015A", "G016A"),
        selectedSlots = listOf(0, 1, 2, 3), appendLastThree = true)

    @Test fun followsTheActualSuccessfulSeat() {
        val run = BookingSequence(plan, today)
        assertTrue(run.success(1, "G016A") is BookingSequence.Result.Next)
        assertEquals(listOf("G016A"), run.config.seatNumbers)
        assertEquals(listOf(4, 5, 6), run.config.selectedSlots)
        assertEquals(today.plusDays(1), run.targetDate)
        assertTrue(run.success(2, "G016A") is BookingSequence.Result.Done)
        assertTrue(run.finished)
    }

    @Test fun ignoresDuplicateAndOldBatchSuccess() {
        val run = BookingSequence(plan, today)
        run.success(1, "G015A")
        assertEquals(BookingSequence.Result.Ignored, run.success(1, "G015A"))
        assertFalse(run.finished)
        run.success(2, "G015A")
        assertEquals(BookingSequence.Result.Ignored, run.success(2, "G015A"))
    }

    @Test fun keepsFirstBookingWhenSecondFails() {
        val run = BookingSequence(plan, today)
        run.success(1, "G015A")
        val failure = run.failure("候选座位不可预约")
        assertTrue(failure.detail.contains("G015A 前四段已成功"))
        assertTrue(failure.detail.contains("前四段保留"))
        assertEquals("G015A", run.firstSeat)
        assertEquals(BookingSequence.Result.Ignored, run.success(2, "G015A"))
    }

    @Test fun doesNotStartSecondBatchAfterFirstFails() {
        val run = BookingSequence(plan, today)
        run.failure("预约失败")
        assertEquals(1, run.batch)
        assertNull(run.firstSeat)
        assertEquals(BookingSequence.Result.Ignored, run.success(1, "G015A"))
    }

    @Test fun disabledOrDifferentSlotsStaySingleBooking() {
        for (config in listOf(plan.copy(appendLastThree = false), plan.copy(selectedSlots = listOf(1, 2, 3, 4)))) {
            val run = BookingSequence(config, today)
            assertFalse(run.hasSecondBatch)
            assertTrue(run.success(1, "G015A") is BookingSequence.Result.Done)
        }
    }

    @Test fun secondBatchCannotSwitchSeat() {
        val run = BookingSequence(plan, today)
        run.success(1, "G016A")
        assertTrue(run.success(2, "G015A") is BookingSequence.Result.Failed)
        assertEquals("G016A", run.firstSeat)
    }

    @Test fun dryRunCannotStartSecondBatch() {
        val run = BookingSequence(plan.copy(dryRun = true), today)
        assertTrue(run.success(1, "G015A") is BookingSequence.Result.Failed)
        assertEquals(1, run.batch)
    }

    @Test fun pinsDateAcrossMidnight() {
        val run = BookingSequence(plan, today)
        run.success(1, "G015A")
        assertTrue(run.configForDate(today)!!.reserveTomorrow)
        assertFalse(run.configForDate(today.plusDays(1))!!.reserveTomorrow)
        assertNull(run.configForDate(today.plusDays(2)))
        assertEquals(today.plusDays(1), run.targetDate)
    }
}
