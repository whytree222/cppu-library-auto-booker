package cn.edu.cppu.libraryautobooker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateManifestTest {
    private fun json(
        schema: Int = 1,
        versionCode: Int = 51,
        versionName: String = "0.19.1",
        minSupported: Int = 50,
        apkUrls: String = "\"https://gitee.com/owner/repo/releases/download/v0.19.1/app.apk\"," +
            "\"https://github.com/owner/repo/releases/download/v0.19.1/app.apk\"",
        sha256: String = "a".repeat(64),
        size: Long = 9_859_104,
        notes: String = "修复更新检查",
        pageUrl: String = "https://gitee.com/owner/repo/releases/tag/v0.19.1"
    ) = """
        {"schema":$schema,"versionCode":$versionCode,"versionName":"$versionName",
         "minSupportedVersionCode":$minSupported,"publishedAt":"2026-10-24T00:00:00Z",
         "apkUrls":[$apkUrls],"sha256":"$sha256","size":$size,"notes":"$notes","pageUrl":"$pageUrl"}
    """.trimIndent()

    @Test fun parsesAValidReleaseManifest() {
        val manifest = UpdateManifest.parse(json())
        assertEquals(51, manifest.versionCode)
        assertEquals("0.19.1", manifest.versionName)
        assertEquals(50, manifest.minSupportedVersionCode)
        assertEquals(9_859_104L, manifest.size)
        assertEquals(2, manifest.apkUrls.size)
        assertEquals("a".repeat(64), manifest.sha256)
    }

    @Test fun normalizesUppercaseSha256() {
        val manifest = UpdateManifest.parse(json(sha256 = "A".repeat(64)))
        assertEquals("a".repeat(64), manifest.sha256)
    }

    @Test fun rejectsUnsupportedSchemaAndVersions() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json(schema = 2)) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json(versionCode = 0)) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json(versionName = "0.19")) }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(versionCode = 50, minSupported = 51))
        }
    }

    @Test fun rejectsUntrustedOrNonHttpsUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(apkUrls = "\"http://gitee.com/owner/repo/releases/download/v0.19.1/app.apk\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(apkUrls = "\"https://evil.example/owner/repo/releases/download/v0.19.1/app.apk\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(pageUrl = "https://evil.example/owner/repo/releases/latest"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(pageUrl = "http://gitee.com/owner/repo/releases/latest"))
        }
    }

    @Test fun rejectsInvalidHashSizeNotesAndUrls() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json(sha256 = "abc")) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json(size = 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(size = UpdateManifest.MAX_APK_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(notes = "x".repeat(UpdateManifest.MAX_NOTES_LENGTH + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json(apkUrls = "")) }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(minSupported = 0))
        }
    }

    @Test fun rejectsMalformedJson() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse("{not json") }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(json(publishedAt = "not-a-time"))
        }
    }

    private fun json(publishedAt: String): String =
        json().replace("2026-10-24T00:00:00Z", publishedAt)
}
