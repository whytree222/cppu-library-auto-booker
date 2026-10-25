package cn.edu.cppu.libraryautobooker.update

import java.time.Instant
import java.util.Locale
import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val publishedAt: String,
    val apkUrls: List<String>,
    val sha256: String,
    val size: Long,
    val notes: String,
    val pageUrl: String
) {
    fun updateRequired(currentVersionCode: Int): Boolean = minSupportedVersionCode > currentVersionCode

    companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        const val MAX_NOTES_LENGTH = 8000

        private val versionNamePattern = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$")
        private val sha256Pattern = Regex("^[0-9a-f]{64}$")

        fun parse(json: String): UpdateManifest {
            require(json.length <= MAX_MANIFEST_BYTES) { "更新信息过大" }
            val root = try {
                JSONObject(json)
            } catch (error: Exception) {
                throw IllegalArgumentException("更新信息格式错误", error)
            }
            require(root.optInt("schema", -1) == 1) { "不支持的更新信息版本" }

            val versionCode = root.optInt("versionCode", -1)
            require(versionCode > 0) { "更新版本号无效" }
            val versionName = root.optString("versionName", "")
            require(versionNamePattern.matches(versionName)) { "更新版本名称无效" }
            val minSupported = root.optInt("minSupportedVersionCode", versionCode)
            require(minSupported in 1..versionCode) { "最低支持版本无效" }

            val publishedAt = root.optString("publishedAt", "")
            require(publishedAt.length in 1..40 && runCatching { Instant.parse(publishedAt) }.isSuccess) {
                "更新时间无效"
            }

            val urls = root.optJSONArray("apkUrls") ?: throw IllegalArgumentException("缺少安装包地址")
            require(urls.length() in 1..4) { "安装包地址数量无效" }
            val apkUrls = (0 until urls.length()).map { index ->
                urls.optString(index, "").also(UpdateUrlPolicy::requireApkUrl)
            }

            val sha256 = root.optString("sha256", "").lowercase(Locale.ROOT)
            require(sha256Pattern.matches(sha256)) { "安装包 SHA-256 无效" }
            val size = root.optLong("size", 0L)
            require(size in 1..MAX_APK_BYTES) { "安装包大小无效" }
            val notes = root.optString("notes", "")
            require(notes.length <= MAX_NOTES_LENGTH) { "更新说明过长" }
            val pageUrl = root.optString("pageUrl", "")
            UpdateUrlPolicy.requirePageUrl(pageUrl)

            return UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                minSupportedVersionCode = minSupported,
                publishedAt = publishedAt,
                apkUrls = apkUrls,
                sha256 = sha256,
                size = size,
                notes = notes,
                pageUrl = pageUrl
            )
        }
    }
}
