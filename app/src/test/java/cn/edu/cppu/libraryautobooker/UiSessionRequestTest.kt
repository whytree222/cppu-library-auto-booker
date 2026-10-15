package cn.edu.cppu.libraryautobooker

import org.junit.Assert.*
import org.junit.Test

class UiSessionRequestTest {
    @Test fun cancellationClosesHandleAndRejectsLateCallback() {
        val request = UiSessionRequest()
        val ticket = request.begin()
        var closes = 0
        var callbacks = 0
        request.attach(ticket, AutoCloseable { closes++ })

        request.cancel()
        request.cancel()
        request.complete(ticket) { callbacks++ }

        assertEquals(1, closes)
        assertEquals(0, callbacks)
        assertFalse(request.pending)
        assertFalse(request.isCurrent(ticket))
    }

    @Test fun newerCheckCancelsEarlierHandleAndCannotBeCompletedByItsCallback() {
        val request = UiSessionRequest()
        val earlier = request.begin()
        var oldCloses = 0
        request.attach(earlier, AutoCloseable { oldCloses++ })
        val current = request.begin()
        var callbacks = 0

        request.complete(earlier) { callbacks++ }
        assertTrue(request.pending)
        request.complete(current) { callbacks++ }
        request.complete(current) { callbacks++ }

        assertEquals(1, oldCloses)
        assertEquals(1, callbacks)
        assertFalse(request.pending)
    }

    @Test fun synchronousCompletionBeforeReturnedHandleIsAttachedStillReleasesIt() {
        val request = UiSessionRequest()
        val ticket = request.begin()
        var closes = 0
        var callbacks = 0
        request.complete(ticket) { callbacks++ }
        request.attach(ticket, AutoCloseable { closes++ })
        request.cancel()

        assertEquals(1, callbacks)
        assertEquals(1, closes)
        assertFalse(request.pending)
    }

    @Test fun lateHandleCannotAttachAfterLifecycleCancellation() {
        val request = UiSessionRequest()
        val ticket = request.begin()
        request.cancel()
        var closes = 0
        request.attach(ticket, AutoCloseable { closes++ })
        assertEquals(1, closes)
        assertFalse(request.pending)
    }

    @Test fun completionReleasesHandleBeforeCallingConsumerAndDoesNotCloseNewCheck() {
        val request = UiSessionRequest()
        val ticket = request.begin()
        var oldCloses = 0
        var newCloses = 0
        request.attach(ticket, AutoCloseable { oldCloses++ })
        request.complete(ticket) {
            assertEquals(1, oldCloses)
            val next = request.begin()
            request.attach(next, AutoCloseable { newCloses++ })
        }
        assertTrue(request.pending)
        assertEquals(0, newCloses)
        request.cancel()
        assertEquals(1, newCloses)
    }
}
