package cn.edu.cppu.libraryautobooker

import org.junit.Assert.*
import org.junit.Test

class ProbeCookieCompletionTest {
    @Test fun synchronousReceiptsWaitForRegistrationAndDelivery() {
        val events = mutableListOf<String>()
        val barrier = ProbeCookieCompletion(2, { events += "deliver:$it" }, { events += "release" })
        val first = barrier.receipt(); val second = barrier.receipt()
        first(); first(); second()
        assertTrue(events.isEmpty())
        barrier.registrationComplete()
        assertEquals(listOf("deliver:false", "release"), events)
        barrier.timeout(); second()
        assertEquals(2, events.size)
    }
    @Test fun timeoutDoesNotReleaseUntilActualLateCookieReceipt() {
        val events = mutableListOf<String>()
        val barrier = ProbeCookieCompletion(1, { events += "deliver:$it" }, { events += "release" })
        val receipt = barrier.receipt()
        barrier.registrationComplete(); barrier.timeout()
        assertEquals(listOf("deliver:true"), events)
        receipt()
        assertEquals(listOf("deliver:true", "release"), events)
    }
    @Test fun callbackExceptionCannotLeakOrDoubleRelease() {
        var releases = 0
        val barrier = ProbeCookieCompletion(0, { throw IllegalStateException() }, { releases++ })
        barrier.registrationComplete(); barrier.timeout()
        assertEquals(1, releases)
    }
    @Test fun reentrantReceiptsCannotReleaseDuringDelivery() {
        var releases = 0
        lateinit var receipt: () -> Unit
        val barrier = ProbeCookieCompletion(1, { receipt(); assertEquals(0, releases) }, { releases++ })
        receipt = barrier.receipt()
        barrier.registrationComplete(); barrier.timeout()
        assertEquals(1, releases)
    }
}
