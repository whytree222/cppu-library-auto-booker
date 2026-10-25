package cn.edu.cppu.libraryautobooker.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale

fun interface UpdatePackageVerifier {
    fun verify(apk: File, manifest: UpdateManifest)
}

class UpdateVerifier(private val context: Context) : UpdatePackageVerifier {
    override fun verify(apk: File, manifest: UpdateManifest) {
        if (!apk.isFile) throw UpdateException("更新安装包不存在")
        if (apk.length() != manifest.size) throw UpdateException("安装包大小校验失败")
        val manager = context.packageManager
        val flags = signingFlags()
        val archive = try {
            manager.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (error: Exception) {
            null
        } ?: throw UpdateException("无法读取更新安装包")
        val installed = try {
            manager.getPackageInfo(context.packageName, flags)
        } catch (error: Exception) {
            throw UpdateException("无法读取当前应用签名")
        }

        val archiveSigner = signerInfo(archive)
        val installedSigner = signerInfo(installed)
        val mismatch = UpdateVerifierPolicy.mismatch(
            expectedPackage = context.packageName,
            expectedVersionCode = manifest.versionCode.toLong(),
            expectedVersionName = manifest.versionName,
            archivePackage = archive.packageName,
            archiveVersionCode = archiveVersionCode(archive),
            archiveVersionName = archive.versionName,
            archiveSigners = archiveSigner.digests,
            archiveMultipleSigners = archiveSigner.multiple,
            installedSigners = installedSigner.digests,
            installedMultipleSigners = installedSigner.multiple
        )
        if (mismatch != null) throw UpdateException(mismatch)
    }

    private data class SignerInfo(val digests: List<String>, val multiple: Boolean)

    private fun signerInfo(info: PackageInfo): SignerInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return SignerInfo(emptyList(), false)
            if (signing.hasMultipleSigners()) return SignerInfo(emptyList(), true)
            val signers = signing.apkContentsSigners ?: return SignerInfo(emptyList(), false)
            return SignerInfo(signers.map { sha256(it.toByteArray()) }, false)
        }
        @Suppress("DEPRECATION")
        val signatures = info.signatures ?: return SignerInfo(emptyList(), false)
        return SignerInfo(signatures.map { sha256(it.toByteArray()) }, false)
    }

    @Suppress("DEPRECATION")
    private fun archiveVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
}

object UpdateVerifierPolicy {
    fun mismatch(
        expectedPackage: String,
        expectedVersionCode: Long,
        expectedVersionName: String,
        archivePackage: String?,
        archiveVersionCode: Long,
        archiveVersionName: String?,
        archiveSigners: List<String>,
        archiveMultipleSigners: Boolean,
        installedSigners: List<String>,
        installedMultipleSigners: Boolean
    ): String? {
        if (archivePackage != expectedPackage) return "安装包包名与当前应用不一致"
        if (archiveVersionCode != expectedVersionCode) return "安装包版本号与更新信息不一致"
        if (archiveVersionName != expectedVersionName) return "安装包版本名称与更新信息不一致"
        if (archiveMultipleSigners) return "安装包包含多个签名，已拒绝"
        if (archiveSigners.size != 1) return "无法读取安装包签名"
        if (installedMultipleSigners) return "当前应用包含多个签名，无法安全更新"
        if (installedSigners.size != 1) return "无法读取当前应用签名"
        if (!archiveSigners.first().equals(installedSigners.first(), ignoreCase = true)) {
            return "安装包签名与当前应用不一致，已拒绝"
        }
        return null
    }
}
