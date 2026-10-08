package cn.edu.cppu.libraryautobooker.data

data class BookingConfig(
    val enabled: Boolean = false,
    val dryRun: Boolean = true,
    val reserveTomorrow: Boolean = true,
    val releaseHour: Int = 6,
    val releaseMinute: Int = 0,
    val roomKeywords: String = "",
    val seatKeywords: String = "",
    val seatChoices: List<SeatChoice> = emptyList(),
    val seatNumbers: List<String> = emptyList(),
    val selectedSlots: List<Int> = emptyList(),
    val appendLastThree: Boolean = false,
    val startTime: String = "08:00",
    val endTime: String = "22:00",
    val entryPath: String = "/selectreadingroom",
    val scheduledAtMillis: Long = 0L,
    val releaseTimes: List<Int> = emptyList(),
    val scheduledTimes: List<Long> = emptyList()
) {
    fun times(): List<Int> = (if (releaseTimes.isEmpty()) listOf(releaseHour * 60 + releaseMinute)
        else releaseTimes).distinct()

    fun pendingTimes(): List<Long> = (if (scheduledTimes.isEmpty() && enabled && scheduledAtMillis > 0)
        listOf(scheduledAtMillis) else scheduledTimes).filter { it > 0 }.distinct().sorted()

    fun withPending(times: List<Long>): BookingConfig {
        val pending = times.distinct().sorted()
        return copy(enabled = pending.isNotEmpty(), scheduledTimes = pending,
            scheduledAtMillis = pending.firstOrNull() ?: 0L)
    }
}
