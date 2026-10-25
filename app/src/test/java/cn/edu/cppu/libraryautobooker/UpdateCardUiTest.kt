package cn.edu.cppu.libraryautobooker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
class UpdateCardUiTest {
    @get:Rule val compose = createComposeRule()

    private val manifest = UpdateManifest(
        versionCode = 51,
        versionName = "0.19.1",
        minSupportedVersionCode = 50,
        publishedAt = "2026-10-24T00:00:00Z",
        apkUrls = listOf("https://gitee.com/owner/repo/releases/download/v0.19.1/app.apk"),
        sha256 = "a".repeat(64),
        size = 12L * 1024 * 1024,
        notes = "修复更新检查并改进品牌权限引导。",
        pageUrl = "https://gitee.com/owner/repo/releases/latest"
    )

    @Test fun availableUpdateOffersDownloadAndFollowsTheSafetyGate() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                UpdateCard(
                    state = UpdateState.Available(manifest, required = false),
                    currentVersion = "0.19.0",
                    safe = true,
                    onCheck = { actions += "check" },
                    onDownload = { actions += "download" },
                    onCancelDownload = { actions += "cancel" },
                    onInstall = { actions += "install" },
                    onOpenPage = { actions += "page" }
                )
            }
        }
        compose.onNodeWithTag("update-card").assertIsDisplayed()
        compose.onNodeWithText("可更新 v0.19.1").assertIsDisplayed()
        compose.onNodeWithTag("update-download").assertIsEnabled().performClick()
        compose.onNodeWithTag("update-open-page").performClick()
        compose.runOnIdle { assertEquals(listOf("download", "page"), actions) }
    }

    @Test fun unsafeBookingWindowDisablesDownloadButKeepsTheReleasePage() {
        compose.setContent {
            MaterialTheme {
                UpdateCard(
                    state = UpdateState.Available(manifest, required = true),
                    currentVersion = "0.19.0",
                    safe = false,
                    onCheck = {}, onDownload = {}, onCancelDownload = {},
                    onInstall = {}, onOpenPage = {}
                )
            }
        }
        compose.onNodeWithText("预约任务运行中或将在 15 分钟内触发，更新操作已暂停。")
            .assertIsDisplayed()
        compose.onNodeWithTag("update-download").assertIsNotEnabled()
        compose.onNodeWithTag("update-open-page").assertIsEnabled()
        compose.onNodeWithText("必要更新").assertIsDisplayed()
    }

    @Test fun idleAndReadyStatesExposeTheirOwnActions() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    UpdateCard(
                        state = UpdateState.Idle,
                        currentVersion = "0.19.0",
                        safe = true,
                        onCheck = { actions += "check" },
                        onDownload = {}, onCancelDownload = {}, onInstall = {}, onOpenPage = {}
                    )
                    UpdateCard(
                        state = UpdateState.Ready(manifest, java.io.File("missing.apk")),
                        currentVersion = "0.19.0",
                        safe = true,
                        onCheck = {},
                        onDownload = {}, onCancelDownload = {},
                        onInstall = { actions += "install" }, onOpenPage = {}
                    )
                }
            }
        }
        compose.onNodeWithTag("update-check").performScrollTo().performClick()
        compose.onNodeWithTag("update-install").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("check", "install"), actions) }
    }

    @Test fun downloadingShowsProgressAndCancel() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                UpdateCard(
                    state = UpdateState.Downloading(manifest, 6L * 1024 * 1024, 12L * 1024 * 1024),
                    currentVersion = "0.19.0",
                    safe = true,
                    onCheck = {}, onDownload = {}, onCancelDownload = { actions += "cancel" },
                    onInstall = {}, onOpenPage = {}
                )
            }
        }
        compose.onNodeWithText("6.0 MB / 12.0 MB").assertIsDisplayed()
        compose.onNodeWithTag("update-cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("cancel"), actions) }
    }

    @Test fun largeFontStacksActionsAndKeepsThemReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        UpdateCard(
                            state = UpdateState.Available(manifest, required = false),
                            currentVersion = "0.19.0",
                            safe = true,
                            onCheck = {}, onDownload = {}, onCancelDownload = {},
                            onInstall = {}, onOpenPage = {}
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("update-download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("update-open-page").performScrollTo().assertIsDisplayed()
    }
}
