package com.lhzkml.jasmine.core.plugin.internal

import android.annotation.SuppressLint
import android.app.Application
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.AssetsProvider
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Per-plugin resources, built at load and handed to the plugin through
 * [com.lhzkml.jasmine.core.plugin.PluginContext] — explicit injection, no
 * global Application override. Resource-id collisions are impossible
 * because the packaging step partitions package ids (host keeps 0x7f,
 * plugins take 0x80+N), so the two tables merge without remapping.
 *
 * Two load paths: API 30+ attaches a [ResourcesProvider] over the package;
 * below that, reflective `AssetManager.addAssetPath`.
 */
internal object PluginResourcesLoader {

    fun load(application: Application, apkPath: String): Resources {
        val host = application.resources
        val assets = newHostBasedAssets(application)
        val resources = Resources(assets, host.displayMetrics, host.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val loader = ResourcesLoader()
            val fd = ParcelFileDescriptor.open(
                File(apkPath),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            loader.addProvider(
                ResourcesProvider.loadFromApk(fd, PluginAssetsProvider(File(apkPath))),
            )
            resources.addLoaders(loader)
        } else {
            addAssetPath(assets, apkPath)
        }
        return resources
    }

    /**
     * An AssetManager pre-seeded with the host package, so framework and
     * host references inside plugin resources resolve.
     */
    private fun newHostBasedAssets(application: Application): AssetManager {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        addAssetPath(assets, application.packageResourcePath)
        return assets
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun addAssetPath(assets: AssetManager, path: String) {
        AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
            .invoke(assets, path)
    }
}

/**
 * Serves asset files from inside the plugin package to the API 30+
 * resources pipeline. Extracted to a temp file per request; results are
 * cached by (path, size) so repeated reads stop re-extracting.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class PluginAssetsProvider(
    private val packageFile: File,
) : AssetsProvider {

    private val cacheDir: File =
        File(packageFile.parentFile, "assets-cache").apply { mkdirs() }

    override fun loadAssetFd(path: String, accessMode: Int): android.content.res.AssetFileDescriptor? =
        runCatching {
            ZipFile(packageFile).use { zip ->
                val entry = zip.getEntry("assets/$path") ?: return null
                val out = File(cacheDir, "${path.replace("/", "_")}.${entry.size}")
                if (!out.exists() || out.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                    enforceQuota()
                }
                val fd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
                android.content.res.AssetFileDescriptor(fd, 0, entry.size)
            }
        }.getOrNull()

    /**
     * Bounds the extracted-asset cache. On overflow, drops oldest-first until
     * back under the quota (the just-written entry is the newest and survives).
     */
    private fun enforceQuota() {
        val files = cacheDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= QUOTA_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= QUOTA_BYTES) return
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val QUOTA_BYTES = 64L * 1024 * 1024
    }
}
