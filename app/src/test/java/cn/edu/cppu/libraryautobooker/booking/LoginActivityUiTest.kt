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
    @Test fun legacyIntentCannotOpenRetiredProxyAndOnlyCampusLoginIsLoaded() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(LoginActivity::class.java,
            Intent(app, LoginActivity::class.java).putExtra("webvpn", true)).setup()
        try {
            val field = LoginActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }
            val view = field.get(controller.get()) as WebView
            assertEquals(LibraryRoute.DIRECT.url("/login"), view.url)
        } finally { controller.pause().stop().destroy() }
    }
}
