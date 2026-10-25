package cn.edu.cppu.libraryautobooker.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val now = 1_000_000_000L

    private fun safe(
        serviceRunning: Boolean = false,
        taskState: String = "SCHEDULED",
        pendingTimes: List<Long> = listOf(now + UpdatePolicy.SAFE_WINDOW_MS + 1),
        loginVisible: Boolean = false,
        permissionSetupVisible: Boolean = false
    ) = UpdatePolicy.isSafe(
        serviceRunning, taskState, pendingTimes, now, loginVisible, permissionSetupVisible
    )

    @Test fun allowsUpdatesWhenNoBookingIsNear() {
        assertTrue(safe())
        assertTrue(safe(pendingTimes = emptyList()))
        assertTrue(safe(taskState = "SUCCESS"))
    }

    @Test fun blocksWhileTheBookingServiceOrTaskIsRunning() {
        assertFalse(safe(serviceRunning = true))
        assertFalse(safe(taskState = "RUNNING"))
        assertFalse(safe(taskState = "TRIGGERED"))
    }

    @Test fun blocksWithinFifteenMinutesOfAPendingTrigger() {
        assertFalse(safe(pendingTimes = listOf(now + 1)))
        assertFalse(safe(pendingTimes = listOf(now + UpdatePolicy.SAFE_WINDOW_MS)))
        assertTrue(safe(pendingTimes = listOf(now + UpdatePolicy.SAFE_WINDOW_MS + 1)))
    }

    @Test fun blocksJustAfterATriggerUntilTheTaskStateSettles() {
        assertFalse(safe(pendingTimes = listOf(now - 30_000L)))
        assertTrue(safe(pendingTimes = listOf(now - 61_000L)))
    }

    @Test fun blocksOnLoginOrFirstRunSetup() {
        assertFalse(safe(loginVisible = true))
        assertFalse(safe(permissionSetupVisible = true))
    }
}
