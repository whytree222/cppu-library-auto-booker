package cn.edu.cppu.libraryautobooker.booking

import android.content.Intent
import android.os.Looper
import android.view.View
import android.webkit.WebView
import cn.edu.cppu.libraryautobooker.RoomBrowserActivity
import cn.edu.cppu.libraryautobooker.data.RoomProfile
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDate
import java.util.concurrent.TimeUnit

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

    @Test fun automaticRefreshIntentBindsExactRoomDayDateAndUniqueRequestTicket() {
        val app = RuntimeEnvironment.getApplication()
        val today = LocalDate.now()
        val room = RoomProfile.DEFAULT.copy(id = "20", name = "报刊阅览区", groupId = "2", groupName = "二楼")
        val first = RoomBrowserActivity.refreshIntent(app, room, true)
        val next = RoomBrowserActivity.refreshIntent(app, room, false)
        val request = RoomBrowserActivity.readRefreshRequest(first)!!
        assertEquals(room, request.room)
        assertEquals(1, request.useDay)
        assertEquals(today.plusDays(1).toString(), request.expectedDate)
        assertEquals(today.toString(), next.getStringExtra(RoomBrowserActivity.EXTRA_EXPECTED_DATE))
        assertEquals(0, next.getIntExtra(RoomBrowserActivity.EXTRA_USE_DAY, -1))
        assertNotNull(RoomBrowserActivity.requestId(first))
        assertNotEquals(first.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID), next.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID))
        assertFalse(request.current(today.plusDays(1))) // A midnight rollover is not another use day.
    }

    @Test fun targetedRefreshNeverFallsBackFromMalformedPartialOrExpiredRequest() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(RoomBrowserActivity.refreshRequested(Intent(app, RoomBrowserActivity::class.java)))
        val partial = Intent(app, RoomBrowserActivity::class.java).putExtra(RoomBrowserActivity.EXTRA_USE_DAY, 1)
        assertTrue(RoomBrowserActivity.refreshRequested(partial))
        assertNull(RoomBrowserActivity.readRefreshRequest(partial))
        val original = RoomBrowserActivity.refreshIntent(app, RoomProfile.DEFAULT, true)
        listOf(
            Intent(original).putExtra(RoomBrowserActivity.EXTRA_USE_DAY, 2),
            Intent(original).putExtra(RoomBrowserActivity.EXTRA_EXPECTED_DATE, "2026-02-30"),
            Intent(original).putExtra(RoomBrowserActivity.EXTRA_EXPECTED_DATE, LocalDate.now().minusDays(1).toString()),
            Intent(original).putExtra(RoomBrowserActivity.EXTRA_ROOM_PROFILE, "{}"),
            Intent(original).putExtra(RoomBrowserActivity.EXTRA_REQUEST_ID, "not-a-request-ticket"),
            Intent(original).apply { removeExtra(RoomBrowserActivity.EXTRA_REQUEST_ID) }
        ).forEach { assertNull(RoomBrowserActivity.readRefreshRequest(it)) }
    }

    @Test fun refreshResultMustMatchChosenRoomIdentityModeAndExactDateButMayUpdateClockRules() {
        val room = RoomProfile.DEFAULT.copy(id = "20", name = "报刊阅览区", groupId = "2", groupName = "二楼")
        val request = RoomBrowserActivity.readRefreshRequest(
            RoomBrowserActivity.refreshIntent(RuntimeEnvironment.getApplication(), room, true))!!
        val fresh = room.copy(slotsDate = request.expectedDate)
        assertTrue(request.matches(fresh))
        assertTrue(request.matches(fresh.copy(slots = fresh.slots.mapIndexed { index, slot ->
            if (index == 1) slot.copy(begin = "10:01:10") else slot
        })))
        listOf(fresh.copy(id = "26"), fresh.copy(name = "其他房间"), fresh.copy(groupId = "3"),
            fresh.copy(groupName = "三楼"), fresh.copy(timeMode = "free", slots = emptyList()),
            fresh.copy(slotsDate = ""), fresh.copy(slotsDate = LocalDate.now().toString())).forEach {
            assertFalse(request.matches(it))
        }
        assertFalse(request.matches(fresh, LocalDate.now().plusDays(1)))
    }

    @Test fun automaticFailureReturnsTicketAndExitsWithoutStartingAnotherRoomOrLogin() {
        val intent = RoomBrowserActivity.refreshIntent(RuntimeEnvironment.getApplication(), RoomProfile.DEFAULT, true)
        val controller = Robolectric.buildActivity(RoomBrowserActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val failure = RoomBrowserActivity::class.java.getDeclaredMethod("failTarget", String::class.java)
                .apply { isAccessible = true }
            failure.invoke(activity, "校园网暂时不可达")
            val shadow = shadowOf(activity)
            assertTrue(activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_CANCELED, shadow.resultCode)
            assertEquals(intent.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID),
                shadow.resultIntent.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID))
            assertEquals("校园网暂时不可达", shadow.resultIntent.getStringExtra(RoomBrowserActivity.EXTRA_REFRESH_ERROR))
            assertNull(shadow.nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pausingAutomaticRefreshCancelsRatherThanRestartingOnResume() {
        val intent = RoomBrowserActivity.refreshIntent(RuntimeEnvironment.getApplication(), RoomProfile.DEFAULT, false)
        val controller = Robolectric.buildActivity(RoomBrowserActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            controller.pause()
            assertTrue(activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            assertEquals(intent.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID),
                shadowOf(activity).resultIntent.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID))
            assertTrue(shadowOf(activity).resultIntent.getStringExtra(RoomBrowserActivity.EXTRA_REFRESH_ERROR)!!.contains("已离开"))
        } finally { controller.stop().destroy() }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun initialSchoolPageLoadingHasABoundedAutomaticTimeout() {
        val intent = RoomBrowserActivity.refreshIntent(RuntimeEnvironment.getApplication(), RoomProfile.DEFAULT, true)
        val controller = Robolectric.buildActivity(RoomBrowserActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertFalse(activity.isFinishing)
            shadowOf(Looper.getMainLooper()).idleFor(44, TimeUnit.SECONDS)
            assertFalse(activity.isFinishing)
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            assertTrue(activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            val result = shadowOf(activity).resultIntent
            assertEquals(intent.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID),
                result.getStringExtra(RoomBrowserActivity.EXTRA_REQUEST_ID))
            assertTrue(result.getStringExtra(RoomBrowserActivity.EXTRA_REFRESH_ERROR)!!.contains("45 秒"))
        } finally { controller.pause().stop().destroy() }
    }
}
