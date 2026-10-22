package cn.edu.cppu.libraryautobooker.booking

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import cn.edu.cppu.libraryautobooker.LoginActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenshotPolicyTest {
    @Test fun directLoginDoesNotBlockScreenshots() = assertCaptureAllowed(LoginActivity::class.java)

    private fun <T : Activity> assertCaptureAllowed(type: Class<T>) {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(type, Intent(app, type)).setup()
        try {
            assertEquals(0, controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        } finally {
            controller.pause().stop().destroy()
            // Drain lifecycle/recomposer cancellation before the next Compose test starts.
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
