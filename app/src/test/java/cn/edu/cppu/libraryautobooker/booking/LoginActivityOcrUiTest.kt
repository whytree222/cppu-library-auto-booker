package cn.edu.cppu.libraryautobooker.booking

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import cn.edu.cppu.libraryautobooker.LoginActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LoginActivityOcrUiTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("webvpn_ocr", 0)

    @Before fun clearPreference() {
        preferences.edit().clear().commit()
    }

    @Test fun webVpnPageOffersOptOutManualRetryAndOriginalImportTrial() {
        val activity = Robolectric.buildActivity(LoginActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), LoginActivity::class.java).putExtra("webvpn", true))
            .setup()
        try {
            val views = children(activity.get().window.decorView)
            val checkbox = views.filterIsInstance<CheckBox>().single()
            assertEquals("自动识别并填写验证码", checkbox.text.toString())
            assertTrue(checkbox.isChecked)
            val labels = views.filterIsInstance<Button>().map { it.text.toString() }
            assertTrue("重新识别当前验证码" in labels)
            assertTrue("本地验证码 OCR 试验（导入图片）" in labels)
            assertTrue("返回应用" in labels)
            assertTrue(views.filterIsInstance<TextView>().any {
                it.text.contains("不自动提交登录") && it.text.contains("不保存统一认证账号或密码")
            })
            checkbox.isChecked = false
            assertFalse(preferences.getBoolean("enabled", true))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun optOutSurvivesReopeningAndDoesNotChangeDirectLogin() {
        preferences.edit().putBoolean("enabled", false).commit()
        val vpn = Robolectric.buildActivity(LoginActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), LoginActivity::class.java).putExtra("webvpn", true))
            .setup()
        try {
            assertFalse(children(vpn.get().window.decorView).filterIsInstance<CheckBox>().single().isChecked)
        } finally {
            vpn.pause().stop().destroy()
        }
        val direct = Robolectric.buildActivity(LoginActivity::class.java).setup()
        try {
            val views = children(direct.get().window.decorView)
            assertTrue(views.filterIsInstance<CheckBox>().isEmpty())
            assertFalse(views.filterIsInstance<TextView>().any { it.text.toString().contains("验证码 OCR") })
            assertFalse(preferences.getBoolean("enabled", true))
        } finally {
            direct.pause().stop().destroy()
        }
    }

    private fun children(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(children(view.getChildAt(index)))
    }
}
