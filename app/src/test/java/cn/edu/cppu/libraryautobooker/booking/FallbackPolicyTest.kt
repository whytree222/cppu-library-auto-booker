package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.network.FallbackPolicy
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test

class FallbackPolicyTest {
    @Test fun submissionLocksTheRouteUntilTheNextBatchIsConfirmed() {
        val policy = FallbackPolicy(enabled = true)
        assertEquals(LibraryRoute.DIRECT, policy.route)
        assertFalse(policy.hasSubmitted())
        assertTrue(policy.markSubmission())
        assertTrue(policy.hasSubmitted())
        assertFalse(policy.switchForNetworkFailure())
        assertEquals(LibraryRoute.DIRECT, policy.route)
        policy.confirmedNextBatch()
        assertFalse(policy.hasSubmitted())
        assertTrue(policy.switchForNetworkFailure())
        assertEquals(LibraryRoute.WEBVPN, policy.route)
    }

    @Test fun switchIsOneWayAndOnlyBeforeSubmission() {
        val policy = FallbackPolicy(enabled = true)
        assertTrue(policy.switchForNetworkFailure())
        assertEquals(LibraryRoute.WEBVPN, policy.route)
        assertFalse(policy.switchForNetworkFailure())
        assertFalse(policy.markSubmission(LibraryRoute.DIRECT))
        assertTrue(policy.markSubmission(LibraryRoute.WEBVPN))
        assertFalse(policy.switchForNetworkFailure())
    }

    @Test fun disabledBackupNeverChangesRoute() {
        val policy = FallbackPolicy(enabled = false)
        assertFalse(policy.switchForNetworkFailure())
        assertEquals(LibraryRoute.DIRECT, policy.route)
    }
}
