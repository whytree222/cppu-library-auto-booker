package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.network.SubmissionGuard
import org.junit.Assert.*
import org.junit.Test

class SubmissionGuardTest {
    @Test fun unknownResultStaysLockedUntilAConfirmedNewBatch() {
        val guard = SubmissionGuard()
        assertFalse(guard.hasSubmitted())
        assertTrue(guard.markSubmission())
        assertTrue(guard.hasSubmitted())
        guard.confirmedNextBatch()
        assertFalse(guard.hasSubmitted())
    }
}
