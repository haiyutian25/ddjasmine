package com.lhzkml.jasmine.core.plugin.update

import android.app.Application
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.internal.InstallException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Network update channel. The manifest carries a SHA-256 digest per
 * version — the charter verifies it at adjudication, so a tampered or
 * truncated download never reaches the payload stage.
 *
 * Manifest shape (served statically):
 * ```json
 * { "id": "some.plugin", "versions": [
 *   { "versionCode": 2, "versionName": "1.1", "downloadUrl": "https://…/p.apk",
 *     "sha256": "…", "changelog": "…" }
 * ] }
 * ```
 */
@Serializable
data class PluginVersionInfo(
    val versionCode: Long,
    val versionName: String = "",
    val downloadUrl: String,
    val sha256: String,
    val changelog: String = "",
)

@Serializable
data class PluginUpdateManifest(
    val id: String,
    val versions: List<PluginVersionInfo> = emptyList(),
) {
    val latest: PluginVersionInfo? get() = versions.maxByOrNull { it.versionCode }
}

class PluginUpdateChannel(
    private val application: Application,
    private val manifestBaseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches one plugin's update manifest (`$manifestBaseUrl/<id>.json`). */
    suspend fun fetchManifest(pluginId: String): PluginUpdateManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$manifestBaseUrl/$pluginId.json")
            json.decodeFromString<PluginUpdateManifest>(body)
        }.getOrNull()
    }

    /** Versions newer than the installed record, digest always present. */
    suspend fun availableUpdate(pluginId: String): PluginVersionInfo? {
        val manifest = fetchManifest(pluginId) ?: return null
        val installed = PluginHost.pluginRecord(pluginId) ?: return manifest.latest
        val latest = manifest.latest ?: return null
        return if (latest.versionCode.toULong() > installed.versionCode &&
            latest.sha256.isNotBlank()
        ) {
            latest
        } else {
            null
        }
    }

    /**
     * Downloads and installs an update. The digest from the manifest is
     * passed to adjudication as `expectedSha256`; a mismatch is a Deny
     * before any file lands.
     */
    suspend fun installUpdate(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val update = availableUpdate(pluginId) ?: return@withContext false
        val staged = File(application.cacheDir, "update-$pluginId-${update.versionCode}.apk")
        try {
            download(update.downloadUrl, staged)
            PluginHost.installPlugin(staged, expectedSha256 = update.sha256)
            PluginHost.launchPlugin(pluginId) // hot-update: load or chained restart
            true
        } catch (e: InstallException) {
            false
        } finally {
            staged.delete()
        }
    }

    /**
     * Downloads and installs the latest version, whether or not it is newer
     * than the installed record — used to fetch a missing dependency.
     */
    suspend fun installLatest(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val manifest = fetchManifest(pluginId) ?: return@withContext false
        val latest = manifest.latest ?: return@withContext false
        val staged = File(application.cacheDir, "dep-$pluginId-${latest.versionCode}.apk")
        try {
            download(latest.downloadUrl, staged)
            PluginHost.installPlugin(staged, expectedSha256 = latest.sha256)
            true
        } catch (e: InstallException) {
            false
        } finally {
            staged.delete()
        }
    }

    private fun get(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
