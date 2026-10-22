package cn.edu.cppu.libraryautobooker.booking

import cn.edu.cppu.libraryautobooker.SessionChecker
import cn.edu.cppu.libraryautobooker.data.LoginStatusStore
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginStatusTest {
    private val route = LibraryRoute.DIRECT
    private val store get() = LoginStatusStore(RuntimeEnvironment.getApplication())

    @Test fun directServerLoginResultIsRecordedWithTimestamp() {
        store.record(route, SessionChecker.decodeRoute(route, 200, null, "{\"ReturnValue\":1}"))
        assertEquals("valid", store.snapshot().direct.state)
        assertTrue(store.snapshot().direct.checkedAt > 0)
    }
    @Test fun confirmedLoggedOutAndNetworkUnknownRemainDistinct() {
        store.record(route, SessionChecker.decodeRoute(route, 200, null, "{\"ReturnValue\":0}"))
        assertEquals("expired", store.snapshot().direct.state)
        store.record(route, SessionChecker.Result("unknown", "校园网络不可达", true))
        assertEquals("unknown", store.snapshot().direct.state)
    }
    @Test fun externalOrUnknownHtmlIsNeverValidAuthentication() {
        assertEquals("unknown", SessionChecker.decodeRoute(route, 200, null, "<html>login</html>").state)
        assertEquals("unknown", SessionChecker.decodeRoute(route, 502, null, "").state)
    }
    @Test fun onlyCampusOriginIsSupported() {
        assertEquals(listOf(LibraryRoute.DIRECT), LibraryRoute.entries)
        assertNull(route.pathOf("https://webvpn.cppu.edu.cn/login"))
        assertNull(route.pathOf("http://other.example/selectreadingroom"))
        assertNull(route.pathOf("http://user@mlib.cppu.edu.cn/login"))
        assertEquals("/selectreadingroom", route.pathOf(route.url("/selectreadingroom")))
    }
}
