package cn.edu.cppu.libraryautobooker.booking

import android.content.Intent
import android.webkit.WebView
import cn.edu.cppu.libraryautobooker.LoginActivity
import cn.edu.cppu.libraryautobooker.network.LibraryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginActivityUiTest {
    private fun loadedUrl(webvpn: Boolean): String {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(app, LoginActivity::class.java)
        if (webvpn) intent.putExtra(LoginActivity.EXTRA_WEBVPN, true)
        val controller = Robolectric.buildActivity(LoginActivity::class.java, intent).setup()
        return try {
            val field = LoginActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
            (field.get(controller.get()) as WebView).url.orEmpty()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun directLoginLoadsOnlyTheCampusLibraryPage() {
        assertEquals(LibraryRoute.DIRECT.url("/login"), loadedUrl(webvpn = false))
    }

    @Test fun webVpnLoginLoadsOnlyTheProxiedLibraryPage() {
        assertEquals(LibraryRoute.WEBVPN.url("/login"), loadedUrl(webvpn = true))
    }
}
