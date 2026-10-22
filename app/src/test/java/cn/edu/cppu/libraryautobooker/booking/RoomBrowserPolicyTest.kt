package cn.edu.cppu.libraryautobooker.booking

import android.content.Intent
import android.view.View
import android.webkit.WebView
import cn.edu.cppu.libraryautobooker.RoomBrowserActivity
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Stub WebView only: verifies native request policy, never performs school requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomBrowserPolicyTest {
    private fun withBrowser(block: (RoomBrowserActivity) -> Unit) {
        val intent = Intent(RuntimeEnvironment.getApplication(), RoomBrowserActivity::class.java)
        val controller = Robolectric.buildActivity(RoomBrowserActivity::class.java, intent).setup()
        try { block(controller.get()) } finally { controller.pause().stop().destroy() }
    }

    private fun accepts(activity: RoomBrowserActivity, method: String, url: String): Boolean {
        val policy = RoomBrowserActivity::class.java.getDeclaredMethod("allowedRequest", String::class.java, String::class.java)
        policy.isAccessible = true
        return policy.invoke(activity, method, url) as Boolean
    }

    @Test fun allowsOnlyObservedReadOnlyRequestsOnCampusOrigin() {
        withBrowser { activity ->
            val route = LibraryRoute.DIRECT
            assertTrue(accepts(activity, "GET", route.url("/selectreadingroom?groupid=4")))
            assertTrue(accepts(activity, "GET", route.url("/js/jquery-3.4.1.min.js")))
            assertTrue(accepts(activity, "POST", route.url("/readingroom/checktimecanbesk")))
            assertTrue(accepts(activity, "POST", route.url("/readingroom/getbesktimelist")))
            listOf("/readingroom/postbeskdata", "/readingroom/multipostbeskdata", "/interface/readingroom/submitBespeak",
                "/readingroomtablelist", "/multireadingroomtablelist", "/login").forEach { path ->
                assertFalse(accepts(activity, "POST", route.url(path)))
                assertFalse(accepts(activity, "GET", route.url(path)))
            }
            assertFalse(accepts(activity, "POST", "https://other.example/readingroom/getbesktimelist"))
            assertFalse(accepts(activity, "DELETE", route.url("/readingroom/checktimecanbesk")))
        }
    }

    @Test fun staticPathCannotEscapeToStateChangingOrForeignEndpoints() = withBrowser { activity ->
        val route = LibraryRoute.DIRECT
        listOf("/js/../readingroom/postbeskdata.js", "/js/../../login.js", "/readingroom/postbeskdata.js",
            "/wengine-vpn/js/../../login.js").forEach {
            assertFalse(accepts(activity, "GET", route.origin + it))
        }
        assertFalse(accepts(activity, "GET", route.origin + "/wengine-vpn/js/main.js"))
        assertFalse(accepts(activity, "POST", route.origin + "/wengine-vpn/js/main.js"))
    }

    @Test fun embeddedSchoolPageIsNotAUserBookingSurface() = withBrowser { activity ->
        val field = RoomBrowserActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
        val view = field.get(activity) as WebView
        assertEquals(View.GONE, view.visibility)
        assertFalse(view.isFocusable)
        assertFalse(view.isFocusableInTouchMode)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.importantForAccessibility)
    }
}
