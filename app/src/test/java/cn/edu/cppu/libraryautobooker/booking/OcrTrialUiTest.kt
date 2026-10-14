package cn.edu.cppu.libraryautobooker.booking

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.cppu.libraryautobooker.OcrTrialScreen
import cn.edu.cppu.libraryautobooker.OcrTrialState
import cn.edu.cppu.libraryautobooker.isFourDigitOcrResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class OcrTrialUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyFourAsciiDigitsAreAcceptedWithoutRemovingLeadingZeroes() {
        assertTrue(isFourDigitOcrResult("0001"))
        assertFalse(isFourDigitOcrResult("123"))
        assertFalse(isFourDigitOcrResult("12345"))
        assertFalse(isFourDigitOcrResult("１２３４"))
        assertFalse(isFourDigitOcrResult("12 3"))
        assertFalse(isFourDigitOcrResult("12a3"))
    }

    @Test fun invalidResultCannotBeCopiedOrReturnedAndNoImageCannotBeRecognized() {
        compose.setContent { MaterialTheme {
            OcrTrialScreen(OcrTrialState(text = "12a3"), true, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("本地 OCR 试验").assertIsDisplayed()
        compose.onNodeWithText("识别四位数字").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("使用我确认的四位数字").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("复制我确认的结果").performScrollTo().assertIsNotEnabled()
    }

    @Test fun nothingIsCopiedOrReturnedUntilUserExplicitlyConfirms() {
        var copied = false
        var returned = false
        compose.setContent { MaterialTheme {
            OcrTrialScreen(OcrTrialState(text = "0011", confidence = 0.92), true,
                {}, {}, {}, {}, { copied = true }, { returned = true }, {})
        } }
        assertFalse(copied)
        assertFalse(returned)
        compose.onNodeWithText("使用我确认的四位数字").performScrollTo().performClick()
        assertTrue(returned)
        assertFalse(copied)
    }

    @Test fun busyRecognitionPreventsStaleResultUse() {
        compose.setContent { MaterialTheme {
            OcrTrialScreen(OcrTrialState(text = "0011", busy = true), true, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("选择数字图片").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("使用我确认的四位数字").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("复制我确认的结果").performScrollTo().assertIsNotEnabled()
    }
}
