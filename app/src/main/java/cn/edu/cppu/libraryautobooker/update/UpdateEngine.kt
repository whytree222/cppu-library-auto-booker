package cn.edu.cppu.libraryautobooker.update

import android.content.Context
import android.os.Build
import cn.edu.cppu.libraryautobooker.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Unavailable : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(
        val manifest: UpdateManifest,
        val required: Boolean,
        val deferred: Boolean = false
    ) : UpdateState
    data class Downloading(
        val manifest: UpdateManifest,
        val downloaded: Long,
        val total: Long
    ) : UpdateState
    data class Ready(val manifest: UpdateManifest, val file: File) : UpdateState
    data class Failed(
        val message: String,
        val manual: Boolean,
        val manifest: UpdateManifest? = null
    ) : UpdateState
}

class UpdateEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: UpdateRepository,
    private val verifier: UpdatePackageVerifier,
    private val store: UpdateStore,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val installedVersionCode: () -> Long = {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(currentVersionCode.toLong())
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updatesDirectory: File = File(context.filesDir, "updates")
) {
    private val _state = MutableStateFlow<UpdateState>(
        if (repository.configured) UpdateState.Idle else UpdateState.Unavailable
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun automaticCheck(safe: Boolean) {
        if (!safe || !repository.configured) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready ||
            _state.value is UpdateState.Checking) return
        if (!store.shouldAutoCheck(now())) return
        check(manual = false)
    }

    fun checkNow() {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
        check(manual = true)
    }

    fun cancelCheck() {
        if (_state.value !is UpdateState.Checking) return
        checkJob?.cancel()
        _state.value = UpdateState.Idle
    }

    fun download() {
        val available = _state.value as? UpdateState.Available ?: return
        startDownload(available.manifest)
    }

    fun cancelDownload() {
        val downloading = _state.value as? UpdateState.Downloading ?: return
        downloadJob?.cancel()
        _state.value = UpdateState.Available(
            downloading.manifest,
            downloading.manifest.updateRequired(currentVersionCode),
            deferred = false
        )
    }

    fun retry() {
        val failed = _state.value as? UpdateState.Failed ?: return
        val manifest = failed.manifest
        if (manifest != null) startDownload(manifest) else check(manual = true)
    }

    fun defer() {
        val available = _state.value as? UpdateState.Available ?: return
        store.defer(available.manifest.versionCode, now() + UpdateStore.DAY_MS)
        _state.value = available.copy(deferred = true)
    }

    fun onResume() {
        scope.launch(ioDispatcher) {
            if (_state.value is UpdateState.Downloading) return@launch
            val ready = _state.value as? UpdateState.Ready
            if (ready != null && ready.manifest.versionCode <= installedVersionCode()) {
                ready.file.delete()
                _state.value = UpdateState.UpToDate
                cleanupFiles(keep = null)
            } else {
                cleanupFiles(keep = ready?.file)
            }
        }
    }

    private fun check(manual: Boolean) {
        if (!repository.configured) {
            _state.value = UpdateState.Unavailable
            return
        }
        if (checkJob?.isActive == true) return
        val previous = _state.value
        checkJob = scope.launch {
            _state.value = UpdateState.Checking
            try {
                val manifest = withContext(ioDispatcher) { repository.fetchManifest() }
                if (manifest.versionCode <= currentVersionCode) {
                    store.markChecked(now())
                    _state.value = UpdateState.UpToDate
                } else {
                    store.markChecked(now())
                    _state.value = UpdateState.Available(
                        manifest = manifest,
                        required = manifest.updateRequired(currentVersionCode),
                        deferred = !manual && store.isDeferred(manifest.versionCode, now())
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.markFailed(now())
                _state.value = if (previous is UpdateState.Available) previous
                else UpdateState.Failed(friendly(error), manual)
            }
        }
    }

    private fun startDownload(manifest: UpdateManifest) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(manifest, 0L, manifest.size)
            var lastReported = 0L
            try {
                val file = withContext(ioDispatcher) {
                    repository.download(manifest, updatesDirectory) { done, total ->
                        if (done == 0L || done == total || done - lastReported >= PROGRESS_STEP) {
                            lastReported = done
                            _state.value = UpdateState.Downloading(manifest, done, total)
                        }
                    }
                }
                try {
                    withContext(ioDispatcher) { verifier.verify(file, manifest) }
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
                _state.value = UpdateState.Ready(manifest, file)
            } catch (error: CancellationException) {
                if (scope.isActive) {
                    _state.value = UpdateState.Available(
                        manifest,
                        manifest.updateRequired(currentVersionCode),
                        deferred = false
                    )
                }
                throw error
            } catch (error: Exception) {
                _state.value = UpdateState.Failed(friendly(error), manual = true, manifest = manifest)
            }
        }
    }

    private fun cleanupFiles(keep: File?) {
        updatesDirectory.listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    private fun friendly(error: Throwable): String = when (error) {
        is UpdateException -> error.message ?: "更新失败，请稍后重试"
        is java.net.UnknownHostException -> "网络不可用，请稍后重试"
        is java.net.SocketTimeoutException -> "连接更新服务器超时，请稍后重试"
        is java.io.IOException -> "网络读取失败，请稍后重试"
        is IllegalArgumentException -> error.message ?: "更新信息无效"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "更新失败，请稍后重试"
    }

    companion object {
        private const val PROGRESS_STEP = 512 * 1024L
    }
}
