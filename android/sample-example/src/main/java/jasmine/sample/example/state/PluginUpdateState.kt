package jasmine.sample.example.state

import jasmine.sample.example.common.update.RemotePlugin
import jasmine.sample.example.common.viewmodel.BaseUiState

data class PluginUpdateState(
    val remotePlugins: List<RemotePlugin> = emptyList(),
    val installedPlugins: Map<String, String> = emptyMap(),
    val downloadingPlugins: Map<String, Float> = emptyMap(),
    val installingPlugins: Set<String> = emptySet(),
    val showInstallSuccessDialog: Boolean = false,
    val recentlyInstalledPluginId: String? = null,
    val restartRequired: Boolean = false,
    override val isLoading: Boolean = false,
    override val isError: Boolean = false,
    override val errorMessage: String? = null,
) : BaseUiState
