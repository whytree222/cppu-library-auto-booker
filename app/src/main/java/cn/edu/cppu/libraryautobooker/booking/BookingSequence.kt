package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.data.BookingConfig
import java.time.LocalDate

/** One confirmed booking may unlock one more booking, always for the same seat/date. */
class BookingSequence(initial: BookingConfig, today: LocalDate = LocalDate.now()) {
    val targetDate: LocalDate = today.plusDays(if (initial.reserveTomorrow) 1 else 0)
    val hasSecondBatch = initial.appendLastThree && initial.selectedSlots == listOf(0, 1, 2, 3)
    var config: BookingConfig = initial
        private set
    var batch: Int = 1
        private set
    var firstSeat: String? = null
        private set
    var finished = false
        private set

    sealed class Result {
        data class Next(val detail: String) : Result()
        data class Done(val detail: String) : Result()
        data class Failed(val detail: String) : Result()
        data object Ignored : Result()
    }

    fun success(sourceBatch: Int, seat: String): Result {
        if (finished || sourceBatch != batch) return Result.Ignored
        if (config.dryRun || seat !in config.seatNumbers) {
            return failure("成功反馈中的座位与当前任务不一致；已停止")
        }
        if (batch == 1 && hasSecondBatch) {
            firstSeat = seat
            batch = 2
            config = config.copy(seatNumbers = listOf(seat), selectedSlots = listOf(4, 5, 6), appendLastThree = false)
            return Result.Next("$seat 前四段已预约成功，正在预约同座位后三段")
        }
        finished = true
        return Result.Done(if (batch == 2) "$seat 前四段和后三段均预约成功（同一天，共两笔）" else "$seat 预约成功")
    }

    fun failure(detail: String): Result.Failed {
        finished = true
        return Result.Failed(firstSeat?.let { "$it 前四段已成功；后三段未确认成功：$detail。前四段保留，请核对学校记录" } ?: detail)
    }

    fun progress(detail: String): String = when {
        batch == 2 -> "${firstSeat} 前四段已成功；后三段：$detail"
        hasSecondBatch -> "前四段：$detail"
        else -> detail
    }

    fun configForDate(today: LocalDate): BookingConfig? = when (targetDate) {
        today -> config.copy(reserveTomorrow = false)
        today.plusDays(1) -> config.copy(reserveTomorrow = true)
        else -> null
    }
}
