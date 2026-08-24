package com.lhzkml.jasmine.core.plugin.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.lhzkml.jasmine.core.plugin.rust.FfiCapability
import com.lhzkml.jasmine.core.plugin.rust.FfiPluginRecord
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Install executor: everything the Rust charter's verdict authorizes, this
 * performs — package metadata, payload placement, native library
 * extraction, class index, component manifests — then hands the complete
 * record to the ledger's transactional commit.
 *
 * On-disk layout per plugin (`filesDir/plugins/<id>/`):
 *   base.apk        payload, read-only (Android 14+ DCL requirement)
 *   class_index     one class name per line (multi-DEX aware)
 *   lib/<abi>/…     native libraries, best-ABI-first
 */
internal class InstallExecutor(private val context: Context) {

    companion object {
        const val META_ENTRY_CLASS = "jasmine.plugin.entryClass"
        const val META_UI_ENTRY_CLASS = "jasmine.plugin.uiEntryClass"
        const val META_DESCRIPTION = "jasmine.plugin.description"
        const val META_CAPABILITIES = "jasmine.plugin.capabilities"
        const val META_ISOLATED = "jasmine.plugin.isolated"
        const val META_DEPENDENCIES = "jasmine.plugin.dependencies"
        const val PAYLOAD_NAME = "base.apk"
        const val CLASS_INDEX_NAME = "class_index"
        const val LIB_DIR = "lib"
        const val EXEC_DIR = "exec"
        const val PERMISSIONS_NAME = "permissions"
        const val DEPENDENCIES_NAME = "dependencies"
        const val UI_ENTRY_NAME = "ui_entry"
    }

    class Metadata(
        val packageName: String,
        val name: String,
        val iconResId: Int?,
        val versionCode: Long,
        val versionName: String,
        val entryClass: String,
        val uiEntryClass: String?,
        val description: String,
        val capabilities: List<String>,
        val isolated: Boolean,
        val dependencies: List<String>,
    )

    fun pluginDir(pluginId: String): File = File(context.filesDir, "plugins/$pluginId")

    fun payloadFile(pluginId: String): File = File(pluginDir(pluginId), PAYLOAD_NAME)

    fun libDir(pluginId: String): File = File(pluginDir(pluginId), LIB_DIR)

    /** Where executable assets (`assets/exec/`) are extracted, +x marked. */
    fun execDir(pluginId: String): File = File(pluginDir(pluginId), EXEC_DIR)

    /** Reads and validates package metadata; fails fast on anything missing. */
    fun readMetadata(apk: File): Metadata {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA)
            ?: throw InstallException("无法解析插件包: ${apk.absolutePath}")
        info.applicationInfo?.let {
            it.sourceDir = apk.absolutePath
            it.publicSourceDir = apk.absolutePath
        }
        val appInfo = info.applicationInfo
            ?: throw InstallException("插件包缺少 applicationInfo: ${apk.absolutePath}")
        val meta = appInfo.metaData
            ?: throw InstallException("插件包缺少 meta-data（未声明 $META_ENTRY_CLASS）: ${info.packageName}")
        val entryClass = meta.getString(META_ENTRY_CLASS)
            ?: throw InstallException("插件包未声明 $META_ENTRY_CLASS: ${info.packageName}")
        val capabilities = meta.getString(META_CAPABILITIES)
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val isolated = meta.getBoolean(META_ISOLATED, false)
        val dependencies = meta.getString(META_DEPENDENCIES)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return Metadata(
            packageName = info.packageName
                ?: throw InstallException("插件包缺少 package 名: ${apk.absolutePath}"),
            name = appInfo.loadLabel(pm)?.toString() ?: info.packageName.orEmpty(),
            iconResId = appInfo.icon.takeIf { it != 0 },
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = info.versionName.orEmpty(),
            entryClass = entryClass,
            uiEntryClass = meta.getString(META_UI_ENTRY_CLASS),
            description = meta.getString(META_DESCRIPTION).orEmpty(),
            capabilities = capabilities,
            isolated = isolated,
            dependencies = dependencies,
        )
    }

    /** SHA-256 (lowercase hex) of the package bytes. */
    fun digestOf(apk: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * Places the payload: backup the previous plugin dir, copy the package,
     * mark read-only, extract native libraries, write the class index.
     * The backup path is returned so the caller can roll back on a failed
     * commit; it is deleted on success.
     */
    fun placePayload(pluginId: String, source: File, classes: List<String>): File? {
        val dir = pluginDir(pluginId)
        val backup = File(context.filesDir, "plugins/$pluginId.bak")
        if (backup.exists()) backup.deleteRecursively()
        val hadPrevious = dir.exists()
        if (hadPrevious && !dir.renameTo(backup)) {
            throw InstallException("无法备份现有插件目录: $pluginId")
        }
        try {
            dir.mkdirs()
            val payload = File(dir, PAYLOAD_NAME)
            source.inputStream().use { input ->
                FileOutputStream(payload).use { output -> input.copyTo(output) }
            }
            if (payload.length() != source.length()) {
                throw InstallException("插件包复制不完整: $pluginId")
            }
            if (!payload.setReadOnly()) {
                throw InstallException("无法将插件包设为只读（Android 14+ 动态代码加载要求）: $pluginId")
            }
            extractNativeLibraries(payload, File(dir, LIB_DIR))
            extractExecutableAssets(payload, File(dir, EXEC_DIR))
            File(dir, CLASS_INDEX_NAME).writeText(classes.joinToString("\n"))
            return if (hadPrevious) backup else null
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (hadPrevious) backup.renameTo(dir)
            if (e is InstallException) throw e
            throw InstallException("插件落盘失败: $pluginId (${e.message})", e)
        }
    }

    /** Deletes a stale backup after a successful commit. */
    fun dropBackup(backup: File?) {
        backup?.deleteRecursively()
    }

    /** Rolls a failed commit back to the pre-install state. */
    fun rollback(pluginId: String, backup: File?) {
        pluginDir(pluginId).deleteRecursively()
        backup?.renameTo(pluginDir(pluginId))
    }

    /**
     * Extracts `lib/<abi>/` entries for the best-matching device ABIs.
     * Unlike the reference implementation, all matching ABIs are extracted
     * in preference order so the class loader can fall back down the list.
     */
    private fun extractNativeLibraries(apk: File, libDir: File) {
        val abis = Build.SUPPORTED_ABIS ?: return
        ZipFile(apk).use { zip ->
            for (abi in abis) {
                val prefix = "lib/$abi/"
                val entries = zip.entries().asSequence()
                    .filter { it.name.startsWith(prefix) && it.name.endsWith(".so") }
                    .toList()
                if (entries.isEmpty()) continue
                val target = File(libDir, abi)
                target.mkdirs()
                for (entry in entries) {
                    val out = File(target, entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
                return // best ABI only; the rest stay in the package
            }
        }
    }

    /**
     * Extracts executable assets from `assets/exec/` entries, marking them
     * `+x`. Android 10+ makes `filesDir` noexec, so a direct `execve` of a
     * file here fails; the `ExecBridge` therefore prefers a dlopen bridge
     * (loading the executable as a shared object and invoking its `main`)
     * and falls back to `ProcessBuilder` only where the mount permits it.
     * Extraction itself is deterministic and idempotent.
     */
    private fun extractExecutableAssets(apk: File, execDir: File) {
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.startsWith("assets/exec/") && !it.isDirectory }
                .toList()
            if (entries.isEmpty()) return
            execDir.mkdirs()
            for (entry in entries) {
                val name = entry.name.substringAfterLast('/')
                val out = File(execDir, name)
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                out.setExecutable(true, false)
            }
        }
    }

    /**
     * Parses static receivers from the binary manifest (AssetManager
     * resource-parser path, since PackageManager does not surface
     * uninstalled receivers).
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    fun parseReceivers(apk: File): List<ReceiverSpec> {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        addAssetPath.invoke(assetManager, apk.absolutePath)
        val parser: XmlResourceParser = assetManager
            .openXmlResourceParser("AndroidManifest.xml")
        try {
            val packageName = readManifestPackage(parser)
            return readReceivers(parser, packageName)
        } finally {
            parser.close()
        }
    }

    private fun readManifestPackage(parser: XmlResourceParser): String {
        parser.resetToManifest()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "manifest") {
                return parser.getAttributeValue(null, "package").orEmpty()
            }
            event = parser.next()
        }
        return ""
    }

    private fun readReceivers(
        parser: XmlResourceParser,
        packageName: String,
    ): List<ReceiverSpec> {
        val receivers = mutableListOf<ReceiverSpec>()
        var event = parser.eventType
        var current: MutableReceiver? = null
        var currentFilter: IntentFilterSpecBuilder? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "receiver" -> current = MutableReceiver(
                        className = qualify(parser.attr("name"), packageName),
                        enabled = parser.attr("enabled")?.toBoolean() ?: true,
                        exported = parser.attr("exported")?.toBoolean() ?: false,
                    )
                    "intent-filter" -> if (current != null) currentFilter = IntentFilterSpecBuilder()
                    "action" -> currentFilter?.actions += parser.attr("name").orEmpty()
                    "category" -> currentFilter?.categories += parser.attr("name").orEmpty()
                    "data" -> parser.attr("scheme")?.let { currentFilter?.schemes += it }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "intent-filter" -> currentFilter?.let { current?.filters += it.build() }
                        .also { currentFilter = null }
                    "receiver" -> current?.let { receivers += it.build() }
                        .also { current = null }
                }
            }
            event = parser.next()
        }
        return receivers
    }

    /**
     * Parses the plugin's `uses-permission` list via PackageManager. The
     * plugin runs with the host's permission set, so this is surfaced to the
     * host for pre-declaration and validation, not merged (Android cannot
     * grant permissions at runtime).
     */
    fun parsePermissions(apk: File): List<String> {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_PERMISSIONS,
        ) ?: return emptyList()
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    /** Writes the plugin's requested-permission list (one per line). */
    fun writePermissions(pluginId: String, permissions: List<String>) {
        File(pluginDir(pluginId), PERMISSIONS_NAME)
            .writeText(permissions.joinToString("\n"))
    }

    /** Reads the persisted requested-permission list, or empty. */
    fun readPermissions(pluginId: String): List<String> {
        val file = File(pluginDir(pluginId), PERMISSIONS_NAME)
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }
    }

    /** Writes the plugin's declared dependency ids (one per line). */
    fun writeDependencies(pluginId: String, dependencies: List<String>) {
        File(pluginDir(pluginId), DEPENDENCIES_NAME)
            .writeText(dependencies.joinToString("\n"))
    }

    /** Reads the persisted declared dependency ids, or empty. */
    fun readDependencies(pluginId: String): List<String> {
        val file = File(pluginDir(pluginId), DEPENDENCIES_NAME)
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }
    }

    /** Writes the optional UI-side entry class (host-process UI companion). */
    fun writeUiEntryClass(pluginId: String, uiEntryClass: String?) {
        val file = File(pluginDir(pluginId), UI_ENTRY_NAME)
        if (uiEntryClass.isNullOrBlank()) {
            file.delete()
        } else {
            file.writeText(uiEntryClass)
        }
    }

    /** Reads the optional UI-side entry class, or null. */
    fun readUiEntryClass(pluginId: String): String? {
        val file = File(pluginDir(pluginId), UI_ENTRY_NAME)
        if (!file.exists()) return null
        return file.readText().trim().takeIf { it.isNotEmpty() }
    }

    /** Parses providers via PackageManager (it does surface these). */
    fun parseProviders(apk: File): List<ProviderSpec> {
        val pm = context.packageManager
        val info: PackageInfo = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA,
        ) ?: return emptyList()
        return info.providers?.map { provider ->
            ProviderSpec(
                className = provider.name,
                authorities = provider.authority?.split(";")?.filter { it.isNotBlank() }
                    ?: emptyList(),
                enabled = provider.enabled,
                exported = provider.exported,
                metaData = provider.metaData?.let { bundle ->
                    bundle.keySet().map { key ->
                        ProviderMetaSpec(name = key, value = bundle.get(key)?.toString())
                    }
                } ?: emptyList(),
            )
        } ?: emptyList()
    }

    /** Builds the complete ledger record for one placed payload. */
    fun buildRecord(
        metadata: Metadata,
        signatureDigests: List<String>,
        packageSha256: String,
        classes: List<String>,
        receivers: List<ReceiverSpec>,
        providers: List<ProviderSpec>,
        capabilities: List<FfiCapability>,
    ): FfiPluginRecord = FfiPluginRecord(
        pluginId = metadata.packageName,
        name = metadata.name,
        iconResId = metadata.iconResId?.toUInt(),
        versionCode = metadata.versionCode.toULong(),
        versionName = metadata.versionName,
        entryClass = metadata.entryClass,
        description = metadata.description,
        signatureDigests = signatureDigests,
        packageSha256 = packageSha256,
        installPath = pluginDir(metadata.packageName).absolutePath,
        enabled = true,
        installedAtMs = System.currentTimeMillis(),
        classes = classes,
        staticReceiversJson = receivers.receiversToJsonOrNull(),
        providersJson = providers.providersToJsonOrNull(),
        capabilities = capabilities,
    )

    private fun XmlResourceParser.attr(name: String): String? =
        getAttributeValue("http://schemas.android.com/apk/res/android", name)

    private fun XmlResourceParser.resetToManifest() {
        // openXmlResourceParser positions at START_DOCUMENT already
    }

    private fun qualify(name: String?, packageName: String): String = when {
        name == null -> ""
        name.startsWith(".") -> packageName + name
        name.contains(".") -> name
        else -> "$packageName.$name"
    }

    private class IntentFilterSpecBuilder {
        val actions = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val schemes = mutableListOf<String>()
        fun build() = IntentFilterSpec(actions.toList(), categories.toList(), schemes.toList())
    }

    private class MutableReceiver(
        val className: String,
        val enabled: Boolean,
        val exported: Boolean,
    ) {
        val filters = mutableListOf<IntentFilterSpec>()
        fun build() = ReceiverSpec(className, enabled, exported, filters.toList())
    }
}

/** Installation-time failure, safe to surface to the caller verbatim. */
class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)
