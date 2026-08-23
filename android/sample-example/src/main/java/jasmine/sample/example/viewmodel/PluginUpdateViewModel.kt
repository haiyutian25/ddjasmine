package jasmine.sample.example.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.lhzkml.jasmine.core.plugin.PluginHost
import jasmine.sample.example.common.update.DownloadStatus
import jasmine.sample.example.common.update.PluginVersionInfo
import jasmine.sample.example.common.update.UpdateManager
import jasmine.sample.example.common.viewmodel.BaseViewModel
import jasmine.sample.example.state.PluginUpdateState
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/** 热更新：拉取远端插件清单、下载并安装。裁决与安装由 PluginHost 完成。 */
class PluginUpdateViewModel(
    private val application: Application,
) : BaseViewModel<PluginUpdateState>(PluginUpdateState()) {

    private val updateManager = UpdateManager(PluginHost.updateManifestBaseUrl.orEmpty())

    init {
        fetchPlugins()
    }

    private fun fetchPlugins() {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            val installed = PluginHost.allPlugins().associate { it.pluginId to it.versionName }
            val remote = updateManager.fetchRemotePlugins()
            updateState {
                copy(isLoading = false, remotePlugins = remote, installedPlugins = installed)
            }
        }
    }

    fun downloadAndInstallPlugin(
        pluginId: String,
        pluginName: String,
        versionInfo: PluginVersionInfo,
    ) {
        viewModelScope.launch {
            val downloadIdentifier = "$pluginId-${versionInfo.version}"
            updateState { copy(downloadingPlugins = downloadingPlugins + (downloadIdentifier to 0f)) }
            updateManager.downloadPlugin(pluginName, versionInfo).collectLatest { status ->
                when (status) {
                    is DownloadStatus.InProgress -> updateState {
                        copy(downloadingPlugins = downloadingPlugins + (downloadIdentifier to status.progress))
                    }
                    is DownloadStatus.Success -> {
                        updateState {
                            copy(
                                downloadingPlugins = downloadingPlugins - downloadIdentifier,
                                installingPlugins = installingPlugins + downloadIdentifier,
                            )
                        }
                        installPlugin(pluginId, status.file, downloadIdentifier)
                    }
                    is DownloadStatus.Failure -> {
                        updateState {
                            copy(
                                downloadingPlugins = downloadingPlugins - downloadIdentifier,
                                isError = true,
                                errorMessage = "下载失败: ${status.error.message}",
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun installPlugin(pluginId: String, pluginFile: File, downloadIdentifier: String) {
        val wasPreviouslyInstalled = uiState.value.installedPlugins.containsKey(pluginId)
        val result = runCatching { PluginHost.installPlugin(pluginFile, forceOverwrite = true) }
        updateState { copy(installingPlugins = installingPlugins - downloadIdentifier) }
        result.fold(
            onSuccess = { record ->
                PluginHost.launchPlugin(record.pluginId)
                updateState {
                    copy(installedPlugins = installedPlugins + (record.pluginId to record.versionName))
                }
                if (wasPreviouslyInstalled) {
                    updateState {
                        copy(
                            showInstallSuccessDialog = true,
                            recentlyInstalledPluginId = record.pluginId,
                            restartRequired = true,
                        )
                    }
                }
            },
            onFailure = { t ->
                Log.e("PluginUpdate", "安装失败", t)
                updateState {
                    copy(isError = true, errorMessage = "安装失败: ${t.message}")
                }
            },
        )
    }

    fun restartApp() {
        val intent = application.packageManager.getLaunchIntentForPackage(application.packageName)
        val restartIntent = Intent.makeRestartActivityTask(intent!!.component)
        application.startActivity(restartIntent)
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    fun dismissRestartDialog() {
        updateState {
            copy(showInstallSuccessDialog = false, recentlyInstalledPluginId = null, restartRequired = false)
        }
    }
}
