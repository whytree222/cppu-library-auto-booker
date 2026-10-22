package cn.edu.cppu.libraryautobooker

import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test

/** Synthetic cookie labels only: no school requests, credentials, or real session values. */
class SessionProbeEpochsTest {
    private val valid = SessionChecker.Result("valid", "synthetic verified result")

    @Test fun currentProbeMayApplyResponseCookiesAndStatusAndComplete() {
        val epochs = SessionProbeEpochs()
        val ticket = epochs.begin(LibraryRoute.DIRECT, "synthetic-before")
        var cookie = "synthetic-before"
        var state = "checking"
        val results = mutableListOf<SessionChecker.Result>()

        epochs.deliver(ticket, cookie, valid, accept = {
            cookie = "synthetic-after"
            state = it.state
        }, callback = results::add)

        assertEquals("synthetic-after", cookie)
        assertEquals("valid", state)
        assertEquals(listOf(valid), results)
    }

    @Test fun authenticationNavigationDiscardsOldCookiesAndStateAndCompletesUnknown() {
        val epochs = SessionProbeEpochs()
        val ticket = epochs.begin(LibraryRoute.DIRECT, "synthetic-old")
        epochs.invalidateAuthentication(LibraryRoute.DIRECT)
        var writes = 0
        var result: SessionChecker.Result? = null

        // The cookie need not rotate: a new captcha can belong to the same session cookie.
        epochs.deliver(ticket, "synthetic-old", valid, accept = { writes++ }, callback = { result = it })

        assertEquals(0, writes)
        assertConservative(result)
    }

    @Test fun changedCookieSnapshotCannotBeRestoredByLateResponse() {
        val epochs = SessionProbeEpochs()
        val ticket = epochs.begin(LibraryRoute.DIRECT, "synthetic-old")
        var cookie = "synthetic-new-login"
        var state = "new-login-valid"
        var result: SessionChecker.Result? = null

        epochs.deliver(ticket, cookie, valid, accept = {
            cookie = "synthetic-old-response"
            state = it.state
        }, callback = { result = it })

        assertEquals("synthetic-new-login", cookie)
        assertEquals("new-login-valid", state)
        assertConservative(result)
    }

    @Test fun olderProbeCannotOverwriteNewerProbeButCanReturnResultForUnchangedSession() {
        val epochs = SessionProbeEpochs()
        val older = epochs.begin(LibraryRoute.DIRECT, "synthetic-shared")
        val newer = epochs.begin(LibraryRoute.DIRECT, "synthetic-shared")
        var writes = 0
        var result: SessionChecker.Result? = null

        epochs.deliver(newer, "synthetic-shared", valid, accept = { writes++ }, callback = {})
        epochs.deliver(older, "synthetic-shared", valid, accept = { writes++ }, callback = { result = it })

        assertEquals(1, writes)
        assertSame(valid, result)
    }

    @Test fun directAuthenticationInvalidationRejectsOutstandingProbe() {
        val epochs = SessionProbeEpochs()
        val direct = epochs.begin(LibraryRoute.DIRECT, "synthetic-direct")
        epochs.invalidateAuthentication(LibraryRoute.DIRECT)
        var writes = 0
        var result: SessionChecker.Result? = null
        epochs.deliver(direct, "synthetic-direct", valid, accept = { writes++ }, callback = { result = it })
        assertEquals(0, writes)
        assertConservative(result)
    }

    @Test fun freshProbeAfterAuthenticationInvalidationCanCompleteNormally() {
        val epochs = SessionProbeEpochs()
        epochs.begin(LibraryRoute.DIRECT, "synthetic-old")
        epochs.invalidateAuthentication(LibraryRoute.DIRECT)
        val fresh = epochs.begin(LibraryRoute.DIRECT, "synthetic-fresh")
        var writes = 0
        var result: SessionChecker.Result? = null

        epochs.deliver(fresh, "synthetic-fresh", valid, accept = { writes++ }, callback = { result = it })

        assertEquals(1, writes)
        assertSame(valid, result)
    }

    @Test fun cookieRemovalAlsoInvalidatesSnapshotWhileMissingAndEmptyAreEquivalent() {
        val epochs = SessionProbeEpochs()
        val beforeRemoval = epochs.begin(LibraryRoute.DIRECT, "synthetic-old")
        var writes = 0
        var removedResult: SessionChecker.Result? = null
        epochs.deliver(beforeRemoval, null, valid, accept = { writes++ }, callback = { removedResult = it })
        assertEquals(0, writes)
        assertConservative(removedResult)

        val noCookie = epochs.begin(LibraryRoute.DIRECT, null)
        epochs.deliver(noCookie, "", valid, accept = { writes++ }, callback = {})
        assertEquals(1, writes)
    }

    @Test fun ticketDiagnosticsNeverExposeItsCookieSnapshot() {
        val epochs = SessionProbeEpochs()
        val ticket = epochs.begin(LibraryRoute.DIRECT, "synthetic-do-not-print")
        assertFalse(ticket.toString().contains("synthetic-do-not-print"))
    }

    private fun assertConservative(result: SessionChecker.Result?) {
        assertNotNull(result)
        assertEquals("unknown", result!!.state)
        assertFalse(result.networkFailure)
    }
}
