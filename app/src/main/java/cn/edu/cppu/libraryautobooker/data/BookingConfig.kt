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
    val scheduledAtMillis: Long = 0L
)
