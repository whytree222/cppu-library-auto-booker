package cn.edu.cppu.libraryautobooker.update

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    private val primaryManifest = "https://gitee.com/owner/repo/raw/main/update.json"
    private val fallbackManifest = "https://github.com/owner/repo/releases/latest/download/update.json"
    private val firstApk = "https://gitee.com/owner/repo/releases/download/v0.19.1/app.apk"
    private val secondApk = "https://github.com/owner/repo/releases/download/v0.19.1/app.apk"

    private fun response(bytes: ByteArray) =
        UpdateResponse(ByteArrayInputStream(bytes), bytes.size.toLong())

    private fun manifestJson(payload: ByteArray, apkUrls: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
        val urls = apkUrls.joinToString(",") { "\"$it\"" }
        return """
            {"schema":1,"versionCode":51,"versionName":"0.19.1","minSupportedVersionCode":50,
             "publishedAt":"2026-10-24T00:00:00Z","apkUrls":[$urls],"sha256":"$hash",
             "size":${payload.size},"notes":"test","pageUrl":"https://gitee.com/owner/repo/releases/latest"}
        """.trimIndent()
    }

    @Test fun fallsBackToTheSecondaryManifestSource() = runTest {
        var primaryCalls = 0
        val fetch = object : UpdateFetch {
            override suspend fun open(url: String, resource: UpdateResource): UpdateResponse {
                if (url.startsWith(primaryManifest)) {
                    primaryCalls++
                    throw UpdateException("primary unavailable")
                }
                return response(manifestJson("payload".toByteArray(), listOf(firstApk)).toByteArray())
            }
        }
        val source = UpdateSource(listOf(primaryManifest, fallbackManifest), fetch)
        val manifest = source.fetchManifest()
        assertEquals(51, manifest.versionCode)
        assertEquals(1, primaryCalls)
    }

    @Test fun rejectsOversizedManifestResponses() = runTest {
        val fetch = object : UpdateFetch {
            override suspend fun open(url: String, resource: UpdateResource): UpdateResponse =
                response(ByteArray(UpdateManifest.MAX_MANIFEST_BYTES + 1) { 'x'.code.toByte() })
        }
        val source = UpdateSource(listOf(primaryManifest), fetch)
        val error = runCatching { source.fetchManifest() }.exceptionOrNull()
        assertTrue(error is UpdateException)
        assertTrue(error?.message.orEmpty().contains("过大"))
    }

    @Test fun triesTheNextApkWhenTheFirstFailsItsHash() = runTest {
        val goodPayload = "second-payload".toByteArray()
        val json = manifestJson(goodPayload, listOf(firstApk, secondApk))
        val fetch = object : UpdateFetch {
            override suspend fun open(url: String, resource: UpdateResource): UpdateResponse {
                if (resource == UpdateResource.MANIFEST) return response(json.toByteArray())
                return if (url.startsWith(firstApk)) response("wrong-payload".toByteArray())
                else response(goodPayload)
            }
        }
        val source = UpdateSource(listOf(primaryManifest), fetch)
        val manifest = source.fetchManifest()
        val directory = temporary.newFolder("updates")
        val file = source.download(manifest, directory) { _, _ -> }
        assertEquals("library-auto-booker-0.19.1.apk", file.name)
        assertArrayEquals(goodPayload, file.readBytes())
        assertEquals(emptyList<String>(), directory.listFiles().orEmpty().map(File::getName)
            .filter { it.endsWith(".part") })
    }

    @Test fun rejectsAnApkWhoseSizeDoesNotMatch() = runTest {
        val payload = "payload".toByteArray()
        val json = manifestJson(payload, listOf(firstApk))
        val fetch = object : UpdateFetch {
            override suspend fun open(url: String, resource: UpdateResource): UpdateResponse =
                if (resource == UpdateResource.MANIFEST) response(json.toByteArray())
                else response("payload-and-more".toByteArray())
        }
        val source = UpdateSource(listOf(primaryManifest), fetch)
        val manifest = source.fetchManifest()
        val error = runCatching {
            source.download(manifest, temporary.newFolder("updates")) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(error is UpdateException)
        assertTrue(error?.message.orEmpty().contains("大小"))
    }

    @Test fun cancellationIsNotTreatedAsAFallback() = runTest {
        var calls = 0
        val fetch = object : UpdateFetch {
            override suspend fun open(url: String, resource: UpdateResource): UpdateResponse {
                calls++
                throw CancellationException("cancelled")
            }
        }
        val source = UpdateSource(listOf(primaryManifest, fallbackManifest), fetch)
        val error = runCatching { source.fetchManifest() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, calls)
    }
}
