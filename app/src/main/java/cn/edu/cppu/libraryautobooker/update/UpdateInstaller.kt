package cn.edu.cppu.libraryautobooker.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import cn.edu.cppu.libraryautobooker.BuildConfig
import java.io.File

sealed interface UpdateInstallLaunch {
    data object Started : UpdateInstallLaunch
    data object PermissionRequired : UpdateInstallLaunch
    data class Failed(val message: String) : UpdateInstallLaunch
}

class UpdateInstaller(
    private val context: Context,
    private val pageUrl: String = BuildConfig.UPDATE_PAGE_URL
) {
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install(apk: File): UpdateInstallLaunch {
        if (!apk.isFile) return UpdateInstallLaunch.Failed("更新安装包不存在，请重新下载")
        if (!canInstall()) return UpdateInstallLaunch.PermissionRequired
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } catch (error: Exception) {
            return UpdateInstallLaunch.Failed("无法共享更新安装包")
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            UpdateInstallLaunch.Started
        } catch (error: ActivityNotFoundException) {
            UpdateInstallLaunch.Failed("未找到系统安装器，请打开发布页手动安装")
        } catch (error: Exception) {
            UpdateInstallLaunch.Failed("无法打开系统安装器，请打开发布页手动安装")
        }
    }

    fun installSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun openPage(): Boolean {
        val valid = runCatching { UpdateUrlPolicy.requirePageUrl(pageUrl) }.getOrNull() ?: return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(valid.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (error: Exception) {
            false
        }
    }

    fun cleanup(directory: File, keep: File? = null) {
        directory.listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }
}
