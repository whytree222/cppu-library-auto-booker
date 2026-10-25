package cn.edu.cppu.libraryautobooker.update

import java.net.URI

enum class UpdateResource { MANIFEST, APK }

/** Only public HTTPS release endpoints are accepted, including the CDN hosts used after redirects. */
object UpdateUrlPolicy {
    private val originHosts = setOf("gitee.com", "github.com")
    private val redirectHosts = originHosts + setOf(
        "raw.giteeusercontent.com",
        "foruda.gitee.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com"
    )

    private val manifestPath = Regex(
        "^/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(?:raw/[A-Za-z0-9_.-]+/update\\.json|releases/latest/download/update\\.json)$"
    )
    private val apkPath = Regex(
        "^/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\.apk$"
    )
    private val pagePath = Regex(
        "^/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/(?:latest|tag/[A-Za-z0-9_.-]+)$"
    )

    fun requireManifestUrl(raw: String): URI = validateInitial(raw, UpdateResource.MANIFEST)

    fun requireApkUrl(raw: String): URI = validateInitial(raw, UpdateResource.APK)

    fun requirePageUrl(raw: String): URI {
        val uri = parse(raw)
        requireOrigin(uri)
        require(uri.rawQuery == null) { "更新页面地址不能包含查询参数" }
        require(pagePath.matches(uri.rawPath.orEmpty())) { "更新页面地址无效" }
        return uri
    }

    fun validateInitial(raw: String, resource: UpdateResource, allowQuery: Boolean = false): URI {
        val uri = parse(raw)
        requireOrigin(uri)
        if (!allowQuery) require(uri.rawQuery == null) { "更新地址不能包含查询参数" }
        val expected = when (resource) {
            UpdateResource.MANIFEST -> manifestPath
            UpdateResource.APK -> apkPath
        }
        require(expected.matches(uri.rawPath.orEmpty())) { "更新地址路径无效" }
        require(!uri.rawPath.orEmpty().split('/').contains("..")) { "更新地址路径无效" }
        return uri
    }

    fun resolveRedirect(current: URI, location: String): URI {
        require(location.isNotBlank() && location.length <= 2048) { "重定向地址无效" }
        val next = try {
            current.resolve(URI(location))
        } catch (error: Exception) {
            throw IllegalArgumentException("重定向地址无效", error)
        }
        require(next.scheme.equals("https", ignoreCase = true)) { "拒绝非 HTTPS 重定向" }
        require(!next.host.isNullOrBlank() && redirectHosts.contains(next.host.lowercase())) {
            "拒绝未知的重定向主机"
        }
        require(next.userInfo == null) { "重定向地址不能包含用户信息" }
        require(next.port == -1 || next.port == 443) { "重定向地址端口无效" }
        require(next.fragment == null) { "重定向地址不能包含片段" }
        require(!next.rawPath.orEmpty().split('/').contains("..")) { "重定向路径无效" }
        return next.normalize()
    }

    private fun parse(raw: String): URI {
        require(raw.isNotBlank() && raw.length <= 2048) { "更新地址无效" }
        return try {
            URI(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("更新地址无效", error)
        }
    }

    private fun requireOrigin(uri: URI) {
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
            "更新地址必须使用 HTTPS"
        }
        require(!uri.host.isNullOrBlank() && originHosts.contains(uri.host.lowercase())) {
            "更新地址主机不受信任"
        }
        require(uri.userInfo == null) { "更新地址不能包含用户信息" }
        require(uri.port == -1 || uri.port == 443) { "更新地址端口无效" }
        require(uri.fragment == null) { "更新地址不能包含片段" }
    }
}
