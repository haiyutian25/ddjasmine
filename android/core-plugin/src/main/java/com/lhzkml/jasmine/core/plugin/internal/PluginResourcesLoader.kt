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

    fun load(application: Application, apkPath: String): LoadedResources {
        val host = application.resources
        val assets = newHostBasedAssets(application)
        val resources = Resources(assets, host.displayMetrics, host.configuration)
        var provider: ResourcesProvider? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val loader = ResourcesLoader()
            val fd = ParcelFileDescriptor.open(
                File(apkPath),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            provider = ResourcesProvider.loadFromApk(fd, PluginAssetsProvider(File(apkPath)))
            loader.addProvider(provider)
            resources.addLoaders(loader)
        } else {
            addAssetPath(assets, apkPath)
        }
        return LoadedResources(resources, provider)
    }

    /**
     * An AssetManager pre-seeded with the host package, so framework and
     * host references inside plugin resources resolve.
     */
    private fun newHostBasedAssets(application: Application): AssetManager {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        // 反射新建的 AssetManager 默认不含 framework-res.apk（package id 0x01）：
        // 插件资源引用 @android: 系统资源/主题会 NotFoundException。显式补挂。
        val frameworkRes = File("/system/framework/framework-res.apk")
        if (frameworkRes.exists()) addAssetPath(assets, frameworkRes.absolutePath)
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

/** 插件资源及其释放句柄：卸载时 [close] 释放 PFD 与 AssetManager。 */
internal class LoadedResources(
    val resources: Resources,
    private val provider: ResourcesProvider?,
) {
    fun close() {
        // 关闭 ResourcesProvider（AutoCloseable）会释放 loadFromApk 传入的
        // ParcelFileDescriptor 及其 AssetsProvider。此前只 close assets 会泄漏 PFD；
        // Resources/ResourcesLoader 均无公开 close()。
        runCatching { provider?.close() }
        runCatching { resources.assets.close() }
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
                // 缓存名用路径的 SHA-256：此前 `path.replace("/","_")` 会让
                // `a/b` 与 `a_b` 落到同一文件名，同大小时互相覆盖、读到错误资源。
                val out = File(cacheDir, "${sha256(path)}.${entry.size}")
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

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
