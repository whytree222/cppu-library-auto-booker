package cn.edu.cppu.libraryautobooker.update

import cn.edu.cppu.libraryautobooker.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface UpdateRepository {
    val configured: Boolean
    suspend fun fetchManifest(): UpdateManifest
    suspend fun download(manifest: UpdateManifest, directory: File, onProgress: (Long, Long) -> Unit): File
}

class UpdateResponse(
    val stream: InputStream,
    val contentLength: Long,
    private val disconnect: (() -> Unit)? = null
) : Closeable {
    override fun close() {
        runCatching { stream.close() }
        runCatching { disconnect?.invoke() }
    }
}

interface UpdateFetch {
    suspend fun open(url: String, resource: UpdateResource): UpdateResponse
}

class HttpUpdateFetch(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : UpdateFetch {
    override suspend fun open(url: String, resource: UpdateResource): UpdateResponse {
        var current = UpdateUrlPolicy.validateInitial(
            url, resource, allowQuery = resource == UpdateResource.MANIFEST
        )
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = try {
                openConnection(current.toURL())
            } catch (error: Exception) {
                throw UpdateException("无法连接更新服务器", error)
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty(
                "Accept",
                if (resource == UpdateResource.MANIFEST) "application/json, text/plain, */*"
                else "application/vnd.android.package-archive, application/octet-stream, */*"
            )
            connection.setRequestProperty("User-Agent", "CppuLibraryAutoBooker/${BuildConfig.VERSION_NAME}")
            try {
                when (val code = connection.responseCode) {
                    in 200..299 -> return UpdateResponse(
                        connection.inputStream,
                        connection.contentLengthLong,
                        connection::disconnect
                    )
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (location.isNullOrBlank()) throw UpdateException("更新地址重定向缺少目标")
                        if (redirects >= MAX_REDIRECTS) throw UpdateException("更新地址重定向次数过多")
                        redirects++
                        current = UpdateUrlPolicy.resolveRedirect(current, location)
                    }
                    else -> {
                        connection.disconnect()
                        throw UpdateException("更新服务器返回 HTTP $code")
                    }
                }
            } catch (error: Exception) {
                connection.disconnect()
                if (error is UpdateException) throw error
                throw UpdateException("更新服务器连接失败", error)
            }
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
    }
}

class UpdateSource(
    manifestUrls: List<String>,
    private val fetch: UpdateFetch = HttpUpdateFetch()
) : UpdateRepository {
    private val manifestUrls = manifestUrls.map(String::trim).filter(String::isNotBlank).distinct()

    override val configured: Boolean get() = manifestUrls.isNotEmpty()

    override suspend fun fetchManifest(): UpdateManifest {
        if (!configured) throw UpdateException("此版本未配置更新通道")
        var failure: Throwable? = null
        for (url in manifestUrls) {
            try {
                val response = fetch.open(cacheBusted(url), UpdateResource.MANIFEST)
                val json = response.use { readLimited(it, UpdateManifest.MAX_MANIFEST_BYTES) }
                return UpdateManifest.parse(json.toString(Charsets.UTF_8))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
        val detail = (failure as? UpdateException)?.message
        throw UpdateException(detail ?: "无法获取更新信息，请检查网络后重试", failure)
    }

    override suspend fun download(
        manifest: UpdateManifest,
        directory: File,
        onProgress: (Long, Long) -> Unit
    ): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException("无法创建更新目录")
        val required = manifest.size + manifest.size / 5
        val usable = directory.usableSpace
        if (usable in 1 until required) throw UpdateException("存储空间不足，请清理后重试")
        val target = File(directory, "library-auto-booker-${manifest.versionName}.apk")
        var failure: Throwable? = null
        for (url in manifest.apkUrls) {
            val part = File(directory, "${target.name}.part")
            try {
                part.delete()
                downloadTo(url, part, manifest, onProgress)
                if (target.exists() && !target.delete()) throw UpdateException("无法替换旧的安装包")
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    if (!part.delete()) part.deleteOnExit()
                }
                return target
            } catch (error: CancellationException) {
                part.delete()
                throw error
            } catch (error: Exception) {
                part.delete()
                failure = error
            }
        }
        throw UpdateException(failure?.message ?: "下载更新失败，请稍后重试", failure)
    }

    private suspend fun downloadTo(
        url: String,
        part: File,
        manifest: UpdateManifest,
        onProgress: (Long, Long) -> Unit
    ) {
        val response = fetch.open(url, UpdateResource.APK)
        response.use {
            val declared = response.contentLength
            if (declared > 0 && declared != manifest.size) {
                throw UpdateException("安装包大小与更新信息不符")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            var lastReported = 0L
            onProgress(0L, manifest.size)
            FileOutputStream(part).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = response.stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > manifest.size) throw UpdateException("安装包大小与更新信息不符")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    if (total == manifest.size || total - lastReported >= PROGRESS_STEP) {
                        lastReported = total
                        onProgress(total, manifest.size)
                    }
                }
                output.flush()
            }
            if (total != manifest.size) throw UpdateException("安装包大小与更新信息不符")
            val actual = digest.digest().toHex()
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                throw UpdateException("安装包 SHA-256 校验失败")
            }
        }
    }

    private suspend fun readLimited(response: UpdateResponse, limit: Int): ByteArray {
        val declared = response.contentLength
        if (declared > limit) throw UpdateException("更新信息过大")
        val initial = if (declared in 0..limit) declared.toInt() else 8192
        val output = ByteArrayOutputStream(initial)
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = response.stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw UpdateException("更新信息过大")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun cacheBusted(url: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}t=${System.currentTimeMillis()}"
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 512 * 1024L
    }
}
