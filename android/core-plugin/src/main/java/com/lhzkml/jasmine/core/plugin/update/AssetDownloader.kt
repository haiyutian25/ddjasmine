package com.lhzkml.jasmine.core.plugin.update

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Asset-level download channel for heavy-native payloads that must not ride
 * inside the plugin APK (MNN model files, Proot rootfs tarballs, …).
 *
 * An asset is described by a manifest the host publishes alongside the
 * plugin: a fixed SHA-256, a size, a download URL, and whether it is
 * optional. Downloads resume from the last byte (HTTP Range) so a truncated
 * transfer never restarts from zero, verify the digest before anything is
 * consumed, and are bounded by a disk quota so one misbehaving manifest
 * cannot exhaust the device.
 */

/** One downloadable asset, as declared by the host's plugin metadata. */
data class AssetManifest(
    val name: String,
    val sha256: String,
    val size: Long,
    val url: String,
    val optional: Boolean = false,
)

/** Download progress, in bytes. */
data class AssetProgress(
    val downloaded: Long,
    val total: Long,
) {
    val isDone: Boolean get() = downloaded >= total
}

/**
 * Downloads assets into `filesDir/plugin_assets/<assetName>`. The asset's
 * own directory holds the target plus a `.part` sidecar used for resume;
 * both are removed together on a digest failure.
 */
class AssetDownloader(
    private val application: Application,
    /** Maximum total bytes this downloader may hold on disk (soft bound). */
    private val quotaBytes: Long = 512L * 1024L * 1024L,
) {
    private fun assetDir(name: String): File =
        File(application.filesDir, "plugin_assets").resolve(name)

    /** Target file, only present (and valid) after a verified download. */
    fun assetFile(name: String): File = assetDir(name).resolve(name)

    /** Whether the target exists and matches the manifest digest. */
    fun isCached(manifest: AssetManifest): Boolean {
        val target = assetFile(manifest.name)
        return target.length() == manifest.size && sha256Of(target) == manifest.sha256
    }

    /**
     * Ensures [manifest] is present and verified, downloading (or resuming)
     * as needed. Returns the verified file, or null when the download fails
     * and the asset is optional. Non-optional failures throw.
     */
    suspend fun ensure(manifest: AssetManifest): File? = withContext(Dispatchers.IO) {
        if (isCached(manifest)) return@withContext assetFile(manifest.name)
        val dir = assetDir(manifest.name)
        dir.mkdirs()
        val target = File(dir, manifest.name)
        val part = File(dir, "${manifest.name}.part")
        try {
            resumeDownload(manifest, part)
            if (sha256Of(part) != manifest.sha256 || part.length() != manifest.size) {
                throw AssetDownloadException("资产校验失败: ${manifest.name}")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw AssetDownloadException("资产落盘失败: ${manifest.name}")
            }
            enforceQuota()
            target
        } catch (e: Exception) {
            part.delete()
            target.delete()
            if (manifest.optional) return@withContext null
            throw if (e is AssetDownloadException) e
            else AssetDownloadException("资产下载失败: ${manifest.name} (${e.message})", e)
        }
    }

    /** Removes one asset's target and sidecar. */
    fun drop(name: String) {
        assetDir(name).deleteRecursively()
    }

    /** Total bytes held by the asset store. */
    fun usedBytes(): Long =
        assetDir("").listFiles()?.sumOf { it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
            ?: 0L

    private fun resumeDownload(manifest: AssetManifest, part: File) {
        val existing = if (part.exists()) part.length() else 0L
        val connection = URI(manifest.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            if (existing > 0 && existing < manifest.size) {
                connection.setRequestProperty("Range", "bytes=$existing-")
            }
            val code = connection.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            val fresh = code == HttpURLConnection.HTTP_OK && existing == 0L
            if (!resuming && !fresh) {
                throw AssetDownloadException("HTTP $code 无法下载: ${manifest.name}")
            }
            val offset = if (resuming) existing else 0L
            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(offset)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                    }
                }
            }
            if (part.length() != manifest.size) {
                throw AssetDownloadException("资产长度不匹配: ${manifest.name}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun enforceQuota() {
        val used = usedBytes()
        if (used > quotaBytes) {
            // Drop oldest-asset directories until back under quota. Best
            // effort; the just-written asset is the newest and survives.
            val dirs = assetDir("").listFiles()?.sortedBy { it.lastModified() } ?: return
            var remaining = used
            for (dir in dirs) {
                if (remaining <= quotaBytes) break
                val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                dir.deleteRecursively()
                remaining -= size
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}

/** Asset download/verification failure. */
class AssetDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
