package jasmine.sample.example.common.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简更新管理器：用 HttpURLConnection 拉取 `plugins.json` 并下载 APK，
 * 替代 Retrofit/OkHttp。baseUrl 为占位地址，演示插件在无服务时优雅降级。
 */
class UpdateManager(private val baseUrl: String) {

    suspend fun fetchRemotePlugins(): List<RemotePlugin> = withContext(Dispatchers.IO) {
        runCatching {
            val text = get("$baseUrl/plugins.json")
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val versions = o.optJSONArray("versions") ?: JSONArray()
                    val vs = buildList {
                        for (j in 0 until versions.length()) {
                            val v = versions.getJSONObject(j)
                            val changelog = v.optJSONArray("changelog") ?: JSONArray()
                            add(
                                PluginVersionInfo(
                                    version = v.optString("version"),
                                    releaseDate = v.optString("releaseDate"),
                                    changelog = (0 until changelog.length()).map { changelog.getString(it) },
                                    downloadUrl = v.optString("downloadUrl"),
                                ),
                            )
                        }
                    }
                    add(
                        RemotePlugin(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            description = o.optString("description"),
                            versions = vs,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun downloadPlugin(
        pluginName: String,
        versionInfo: PluginVersionInfo,
    ): Flow<DownloadStatus> = flow {
        val url = versionInfo.downloadUrl
        if (url.isBlank()) {
            emit(DownloadStatus.Failure(IllegalArgumentException("该版本没有 downloadUrl")))
            return@flow
        }
        try {
            val target = File.createTempFile("plugin-", ".apk")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) emit(DownloadStatus.InProgress(read.toFloat() / total))
                    }
                }
            }
            connection.disconnect()
            emit(DownloadStatus.Success(target))
        } catch (e: Throwable) {
            emit(DownloadStatus.Failure(e))
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
