package cn.edu.cppu.libraryautobooker.update

import android.content.Context
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateEngineTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000_000_000L
    private var installedCode = 49L

    @Before fun clearState() {
        app.getSharedPreferences("update_state", Context.MODE_PRIVATE).edit().clear().commit()
        now = 1_000_000_000_000L
        installedCode = 49L
    }

    private fun manifest(versionCode: Int = 51) = UpdateManifest(
        versionCode = versionCode,
        versionName = "0.19.1",
        minSupportedVersionCode = 50,
        publishedAt = "2026-10-24T00:00:00Z",
        apkUrls = listOf("https://gitee.com/owner/repo/releases/download/v0.19.1/app.apk"),
        sha256 = "b".repeat(64),
        size = 3L,
        notes = "notes",
        pageUrl = "https://gitee.com/owner/repo/releases/latest"
    )

    private class FakeRepository(var manifest: UpdateManifest) : UpdateRepository {
        override val configured = true
        var fetchCount = 0
        var fetchError: Exception? = null
        var fetchDelay = 0L
        var downloadError: Exception? = null
        var downloadDelay = 0L
        var lastDirectory: File? = null

        override suspend fun fetchManifest(): UpdateManifest {
            fetchCount++
            if (fetchDelay > 0) delay(fetchDelay)
            fetchError?.let { throw it }
            return manifest
        }

        override suspend fun download(
            manifest: UpdateManifest,
            directory: File,
            onProgress: (Long, Long) -> Unit
        ): File {
            lastDirectory = directory
            if (downloadDelay > 0) delay(downloadDelay)
            downloadError?.let { throw it }
            directory.mkdirs()
            val file = File(directory, "library-auto-booker-${manifest.versionName}.apk")
            file.writeBytes(byteArrayOf(1, 2, 3))
            onProgress(manifest.size, manifest.size)
            return file
        }
    }

    private class FakeVerifier : UpdatePackageVerifier {
        var calls = 0
        var error: Exception? = null
        override fun verify(apk: File, manifest: UpdateManifest) {
            calls++
            error?.let { throw it }
        }
    }

    private fun TestScope.engine(
        repository: UpdateRepository,
        verifier: UpdatePackageVerifier
    ) = UpdateEngine(
        context = app,
        scope = this,
        repository = repository,
        verifier = verifier,
        store = UpdateStore(app),
        currentVersionCode = 49,
        installedVersionCode = { installedCode },
        now = { now },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        updatesDirectory = File(app.cacheDir, "updates-${System.nanoTime()}")
    )

    @Test fun autoCheckIsThrottledDailyAndRespectsDefer() = runTest {
        val repository = FakeRepository(manifest())
        val engine = engine(repository, FakeVerifier())

        engine.automaticCheck(true)
        advanceUntilIdle()
        assertTrue(engine.state.value is UpdateState.Available)
        assertEquals(1, repository.fetchCount)

        engine.defer()
        assertTrue((engine.state.value as UpdateState.Available).deferred)
        engine.automaticCheck(true)
        advanceUntilIdle()
        assertEquals(1, repository.fetchCount)

        now += UpdateStore.DAY_MS
        engine.automaticCheck(true)
        advanceUntilIdle()
        assertEquals(2, repository.fetchCount)
        assertFalse((engine.state.value as UpdateState.Available).deferred)
    }

    @Test fun unsafeBookingStateBlocksAutomaticChecks() = runTest {
        val repository = FakeRepository(manifest())
        val engine = engine(repository, FakeVerifier())
        engine.automaticCheck(false)
        advanceUntilIdle()
        assertEquals(0, repository.fetchCount)
        assertTrue(engine.state.value is UpdateState.Idle)
    }

    @Test fun reportsManualCheckFailureAndWaitsAnHourBeforeAutomaticRetry() = runTest {
        val repository = FakeRepository(manifest()).apply { fetchError = UpdateException("网络失败") }
        val engine = engine(repository, FakeVerifier())

        engine.checkNow()
        advanceUntilIdle()
        assertTrue(engine.state.value is UpdateState.Failed)
        assertEquals(1, repository.fetchCount)

        engine.automaticCheck(true)
        advanceUntilIdle()
        assertEquals(1, repository.fetchCount)

        now += UpdateStore.RETRY_MS
        engine.automaticCheck(true)
        advanceUntilIdle()
        assertEquals(2, repository.fetchCount)
    }

    @Test fun alreadyCurrentVersionDoesNotOfferAnUpdate() = runTest {
        val engine = engine(FakeRepository(manifest(versionCode = 49)), FakeVerifier())
        engine.checkNow()
        advanceUntilIdle()
        assertTrue(engine.state.value is UpdateState.UpToDate)
    }

    @Test fun downloadRequiresVerificationAndCleansUpAfterInstall() = runTest {
        val repository = FakeRepository(manifest())
        val verifier = FakeVerifier()
        val engine = engine(repository, verifier)
        engine.checkNow()
        advanceUntilIdle()
        engine.download()
        advanceUntilIdle()
        val ready = engine.state.value as UpdateState.Ready
        assertEquals(1, verifier.calls)
        assertTrue(ready.file.isFile)

        installedCode = 51
        engine.onResume()
        advanceUntilIdle()
        assertTrue(engine.state.value is UpdateState.UpToDate)
        assertFalse(ready.file.exists())
    }

    @Test fun cancellingADownloadReturnsToAvailable() = runTest {
        val repository = FakeRepository(manifest()).apply { downloadDelay = 10_000L }
        val engine = engine(repository, FakeVerifier())
        engine.checkNow()
        advanceUntilIdle()
        engine.download()
        runCurrent()
        assertTrue(engine.state.value is UpdateState.Downloading)
        engine.cancelDownload()
        advanceUntilIdle()
        assertTrue(engine.state.value is UpdateState.Available)
    }

    @Test fun verifierFailureDeletesTheDownloadedPackage() = runTest {
        val repository = FakeRepository(manifest())
        val verifier = FakeVerifier().apply { error = UpdateException("签名不一致") }
        val engine = engine(repository, verifier)
        engine.checkNow()
        advanceUntilIdle()
        engine.download()
        advanceUntilIdle()
        val failed = engine.state.value as UpdateState.Failed
        assertEquals("签名不一致", failed.message)
        assertTrue(failed.manifest != null)
        assertEquals(emptyList<String>(), repository.lastDirectory?.listFiles().orEmpty().map(File::getName))
    }

    @Test fun cancellationOfCheckReturnsToIdle() = runTest {
        val repository = FakeRepository(manifest()).apply { fetchDelay = 10_000L }
        val engine = engine(repository, FakeVerifier())
        engine.checkNow()
        runCurrent()
        assertTrue(engine.state.value is UpdateState.Checking)
        engine.cancelCheck()
        advanceUntilIdle()
        assertTrue(engine.state.value is UpdateState.Idle)
    }
}
