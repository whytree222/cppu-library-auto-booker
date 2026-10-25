package cn.edu.cppu.libraryautobooker.update

/** Booking work always wins: never check, download or install while a task is running or imminent. */
object UpdatePolicy {
    const val SAFE_WINDOW_MS = 15 * 60 * 1000L
    private const val RECENT_TRIGGER_MS = 60 * 1000L

    fun isSafe(
        serviceRunning: Boolean,
        taskState: String,
        pendingTimes: List<Long>,
        now: Long,
        loginVisible: Boolean,
        permissionSetupVisible: Boolean
    ): Boolean {
        if (loginVisible || permissionSetupVisible) return false
        if (serviceRunning) return false
        if (taskState == "RUNNING" || taskState == "TRIGGERED") return false
        val from = now - RECENT_TRIGGER_MS
        val until = now + SAFE_WINDOW_MS
        return pendingTimes.none { it in from..until }
    }
}
