package cn.edu.cppu.libraryautobooker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.cppu.libraryautobooker.update.UpdateManifest
import cn.edu.cppu.libraryautobooker.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateDialogUiTest {
    @get:Rule val compose = createComposeRule()

    private val manifest = UpdateManifest(
        versionCode = 51,
        versionName = "0.19.1",
        minSupportedVersionCode = 51,
        publishedAt = "2026-10-24T00:00:00Z",
        apkUrls = listOf("https://gitee.com/owner/repo/releases/download/v0.19.1/app.apk"),
        sha256 = "a".repeat(64),
        size = 12L * 1024 * 1024,
        notes = "修复更新检查并改进品牌权限引导。",
        pageUrl = "https://gitee.com/owner/repo/releases/latest"
    )

    @Test fun requiredUpdateShowsNotesAndDownloadAction() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                UpdateDialog(
                    state = UpdateState.Available(manifest, required = true),
                    currentVersion = "0.19.0",
                    currentVersionCode = 50,
                    safe = true,
                    onDismiss = { actions += "dismiss" },
                    onDownload = { actions += "download" },
                    onCancelDownload = {},
                    onInstall = {},
                    onRetry = {},
                    onOpenPage = { actions += "page" }
                )
            }
        }
        compose.onNodeWithText("发现新版本 v0.19.1").assertIsDisplayed()
        compose.onNodeWithText("必要更新").assertIsDisplayed()
        compose.onNodeWithText("修复更新检查并改进品牌权限引导。").assertIsDisplayed()
        compose.onNodeWithTag("update-download").performClick()
        compose.onNodeWithTag("update-open-page").performClick()
        compose.onNodeWithText("稍后").performClick()
        compose.runOnIdle { assertEquals(listOf("download", "page", "dismiss"), actions) }
    }

    @Test fun downloadingDialogOnlyOffersCancel() {
        compose.setContent {
            MaterialTheme {
                UpdateDialog(
                    state = UpdateState.Downloading(manifest, 6L * 1024 * 1024, 12L * 1024 * 1024),
                    currentVersion = "0.19.0",
                    currentVersionCode = 50,
                    safe = true,
                    onDismiss = {},
                    onDownload = {},
                    onCancelDownload = {},
                    onInstall = {},
                    onRetry = {},
                    onOpenPage = {}
                )
            }
        }
        compose.onNodeWithText("正在下载更新").assertIsDisplayed()
        compose.onNodeWithTag("update-cancel").assertIsDisplayed()
        compose.onNodeWithText("稍后").assertDoesNotExist()
    }
}
