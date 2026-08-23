package jasmine.sample.example.common.update

import java.io.File

/** 远端插件元数据（对应 updates/plugins.json 的条目）。 */
data class RemotePlugin(
    val id: String,
    val name: String,
    val description: String = "",
    val versions: List<PluginVersionInfo> = emptyList(),
)

/** 一个可下载的版本。 */
data class PluginVersionInfo(
    val version: String,
    val releaseDate: String = "",
    val changelog: List<String> = emptyList(),
    val downloadUrl: String = "",
)

/** 下载状态。 */
sealed class DownloadStatus {
    data class InProgress(val progress: Float) : DownloadStatus()
    data class Success(val file: File) : DownloadStatus()
    data class Failure(val error: Throwable) : DownloadStatus()
}
