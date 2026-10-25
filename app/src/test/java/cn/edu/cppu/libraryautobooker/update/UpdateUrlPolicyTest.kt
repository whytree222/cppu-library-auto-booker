package cn.edu.cppu.libraryautobooker.update

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateUrlPolicyTest {
    private val manifest = "https://gitee.com/owner/repo/raw/main/update.json"
    private val githubManifest = "https://github.com/owner/repo/releases/latest/download/update.json"
    private val apk = "https://gitee.com/owner/repo/releases/download/v0.19.1/library-auto-booker-0.19.1.apk"

    @Test fun acceptsTheConfiguredManifestAndApkPaths() {
        assertEquals("gitee.com", UpdateUrlPolicy.requireManifestUrl(manifest).host)
        assertEquals("github.com", UpdateUrlPolicy.requireManifestUrl(githubManifest).host)
        assertEquals("gitee.com", UpdateUrlPolicy.requireApkUrl(apk).host)
        assertEquals("gitee.com", UpdateUrlPolicy.requirePageUrl(
            "https://gitee.com/owner/repo/releases/latest"
        ).host)
        assertEquals("github.com", UpdateUrlPolicy.requirePageUrl(
            "https://github.com/owner/repo/releases/tag/v0.19.1"
        ).host)
    }

    @Test fun rejectsDowngradesAndUnsafeOrigins() {
        listOf(
            "http://gitee.com/owner/repo/raw/main/update.json",
            "https://user@gitee.com/owner/repo/raw/main/update.json",
            "https://gitee.com:8443/owner/repo/raw/main/update.json",
            "https://gitee.com.evil.example/owner/repo/raw/main/update.json",
            "https://evil.example/owner/repo/raw/main/update.json",
            "https://gitee.com/owner/repo/raw/main/update.json?redirect=https://evil.example"
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) {
                UpdateUrlPolicy.requireManifestUrl(url)
            }
        }
        listOf(
            "http://gitee.com/owner/repo/releases/download/v0.19.1/app.apk",
            "https://gitee.com/owner/repo/releases/latest/app.apk",
            "https://gitee.com/owner/repo/releases/download/v0.19.1/app.zip",
            "https://gitee.com/owner/repo/releases/download/v0.19.1/app.apk?download=1",
            "https://github.com/owner/repo/raw/main/app.apk"
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) {
                UpdateUrlPolicy.requireApkUrl(url)
            }
        }
    }

    @Test fun followsRedirectsOnlyToKnownHttpsCdnHosts() {
        val current = URI(apk)
        assertEquals(
            "foruda.gitee.com",
            UpdateUrlPolicy.resolveRedirect(
                current, "https://foruda.gitee.com/assets/library-auto-booker.apk?auth=1"
            ).host
        )
        assertEquals(
            "release-assets.githubusercontent.com",
            UpdateUrlPolicy.resolveRedirect(
                URI("https://github.com/owner/repo/releases/download/v1/app.apk"),
                "https://release-assets.githubusercontent.com/github-production-release-asset/app.apk"
            ).host
        )
        listOf(
            "http://foruda.gitee.com/assets/app.apk",
            "https://evil.example/app.apk",
            "https://gitee.com.evil.example/app.apk",
            "https://user@foruda.gitee.com/assets/app.apk",
            "https://foruda.gitee.com:8443/assets/app.apk",
            "https://foruda.gitee.com/assets/app.apk#fragment"
        ).forEach { location ->
            assertThrows(location, IllegalArgumentException::class.java) {
                UpdateUrlPolicy.resolveRedirect(current, location)
            }
        }
    }

    @Test fun resolvesRelativeRedirectsOnTrustedHosts() {
        val next = UpdateUrlPolicy.resolveRedirect(URI(manifest), "/owner/repo/raw/main/update.json")
        assertEquals("gitee.com", next.host)
    }
}
