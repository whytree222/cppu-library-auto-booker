package cn.edu.cppu.libraryautobooker.update

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.cppu.libraryautobooker.BuildConfig
import kotlinx.coroutines.flow.StateFlow

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val store = UpdateStore(context)
    private val installer = UpdateInstaller(context)
    private val engine = UpdateEngine(
        context = context,
        scope = viewModelScope,
        repository = UpdateSource(
            manifestUrls = listOf(
                BuildConfig.UPDATE_MANIFEST_URL,
                BuildConfig.UPDATE_MANIFEST_FALLBACK_URL
            )
        ),
        verifier = UpdateVerifier(context),
        store = store
    )

    val state: StateFlow<UpdateState> = engine.state
    val configured: Boolean = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank() ||
        BuildConfig.UPDATE_MANIFEST_FALLBACK_URL.isNotBlank()
    val pageUrl: String = BuildConfig.UPDATE_PAGE_URL

    fun maybeAutoCheck(safe: Boolean) = engine.automaticCheck(safe)
    fun checkNow() = engine.checkNow()
    fun cancelCheck() = engine.cancelCheck()
    fun download() = engine.download()
    fun cancelDownload() = engine.cancelDownload()
    fun retry() = engine.retry()
    fun defer() = engine.defer()
    fun onResume() = engine.onResume()

    fun installNow(): UpdateInstallLaunch {
        val ready = state.value as? UpdateState.Ready
            ?: return UpdateInstallLaunch.Failed("没有已下载的更新包，请重新下载")
        return installer.install(ready.file)
    }

    fun canInstall(): Boolean = installer.canInstall()
    fun installSettingsIntent(): Intent = installer.installSettingsIntent()
    fun appDetailsIntent(): Intent = installer.appDetailsIntent()
    fun openPage(): Boolean = installer.openPage()
}
