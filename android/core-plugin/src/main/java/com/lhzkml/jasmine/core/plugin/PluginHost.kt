package com.lhzkml.jasmine.core.plugin

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lhzkml.jasmine.core.plugin.internal.InstallException
import com.lhzkml.jasmine.core.plugin.internal.InstallExecutor
import com.lhzkml.jasmine.core.plugin.internal.LifecycleExecutor
import com.lhzkml.jasmine.core.plugin.internal.LoadFailureCallback
import com.lhzkml.jasmine.core.plugin.internal.Signatures
import com.lhzkml.jasmine.core.plugin.internal.providersFromJson
import com.lhzkml.jasmine.core.plugin.rust.FfiAccessRule
import com.lhzkml.jasmine.core.plugin.rust.FfiAuditReport
import com.lhzkml.jasmine.core.plugin.rust.FfiCallerIdentity
import com.lhzkml.jasmine.core.plugin.rust.FfiCapability
import com.lhzkml.jasmine.core.plugin.rust.FfiInstallRequest
import com.lhzkml.jasmine.core.plugin.rust.FfiPluginRecord
import com.lhzkml.jasmine.core.plugin.rust.FfiSignatureStrategy
import com.lhzkml.jasmine.core.plugin.rust.FfiVerdict
import com.lhzkml.jasmine.core.plugin.rust.PluginCoreHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Signature trust policy for installs, mirroring the charter's strategy. */
enum class SignaturePolicy { Strict, UserGrant, Insecure }

private fun SignaturePolicy.toFfi(): FfiSignatureStrategy = when (this) {
    SignaturePolicy.Strict -> FfiSignatureStrategy.STRICT
    SignaturePolicy.UserGrant -> FfiSignatureStrategy.USER_GRANT
    SignaturePolicy.Insecure -> FfiSignatureStrategy.INSECURE
}

/** Access rule for a sensitive framework API. */
enum class ApiRule { Host, SelfOrHost, AnyPlugin }

private fun ApiRule.toFfi(): FfiAccessRule = when (this) {
    ApiRule.Host -> FfiAccessRule.HOST
    ApiRule.SelfOrHost -> FfiAccessRule.SELF_OR_HOST
    ApiRule.AnyPlugin -> FfiAccessRule.ANY_PLUGIN
}

/** A user-authorization prompt raised by an Ask verdict. */
data class AuthorizationPrompt(
    val kind: Kind,
    val pluginId: String,
    val reason: String,
) {
    enum class Kind { Install, Api }
}

/** Persisted grant entry: (plugin, permission key) → granted. */
@Serializable
private data class PersistedGrant(
    val pluginId: String,
    val permissionKey: String,
    val granted: Boolean,
)

/** Host-provided authorization UI hook (dialog, server approval, …). */
fun interface AuthorizationHandler {
    suspend fun onAuthorization(prompt: AuthorizationPrompt): Boolean
}

/**
 * The plugin runtime's single entry point. All decisions (adjudication,
 * plans, audits, dispatch matching) come from the Rust core; this facade
 * only executes file, ClassLoader, and component operations.
 */
object PluginHost {

    private val mutex = Mutex()
    private var core: PluginCoreHandle? = null
    private var executor: InstallExecutor? = null
    private var lifecycle: LifecycleExecutor? = null
    private var app: Application? = null

    /** Kotlin 侧持久化的授权缓存：key = "pluginId\u0000permissionKey" → granted。
     *  Rust 核心的 grants 是会话级，这里落盘并在启动时回放，实现跨进程/重启保留。 */
    private val persistedGrants = ConcurrentHashMap<String, Boolean>()
    private val grantJson = Json { ignoreUnknownKeys = true }

    /** Completes once [initialize] finishes; [awaitReady] suspends on it. */
    private val ready = CompletableDeferred<Unit>()

    /** Optional structured-event observer (logging / metrics / crash aggregation). */
    @Volatile
    private var eventListener: PluginEventListener? = null

    /** Registers (or clears) the runtime event observer. */
    fun setEventListener(listener: PluginEventListener?) {
        eventListener = listener
    }

    internal fun emit(event: PluginEvent) {
        eventListener?.onEvent(event)
    }

    /** Host-settable authorization hook; Ask verdicts fail closed without it. */
    var authorizationHandler: AuthorizationHandler? = null

    /** Receives load/unload failures; the framework never swallows them. */
    var loadFailureCallback: LoadFailureCallback? = null
        set(value) {
            field = value
            lifecycle?.failureCallback = value
        }

    /**
     * Host-configurable update-manifest base URL. When set, the plugin
     * manager UI's online management (and startup checks) get a working
     * [updateChannel].
     */
    @Volatile
    var updateManifestBaseUrl: String? = null

    /** A channel wired to [updateManifestBaseUrl], or null when unset. */
    fun updateChannel(): com.lhzkml.jasmine.core.plugin.update.PluginUpdateChannel? =
        updateManifestBaseUrl?.let { base ->
            com.lhzkml.jasmine.core.plugin.update.PluginUpdateChannel(requireApp(), base)
        }

    /**
     * Asset-level downloader for heavy payloads that must not ride inside
     * the APK (model files, rootfs tarballs). Resume + digest verification +
     * disk quota; see [com.lhzkml.jasmine.core.plugin.update.AssetDownloader].
     */
    fun assetDownloader(): com.lhzkml.jasmine.core.plugin.update.AssetDownloader =
        com.lhzkml.jasmine.core.plugin.update.AssetDownloader(requireApp())

    /**
     * Executable-asset runner (Proot-style user-space binaries). Launching is
     * gated on the `EXEC` capability; see
     * [com.lhzkml.jasmine.core.plugin.proxy.ExecBridge].
     */
    fun execBridge(): com.lhzkml.jasmine.core.plugin.proxy.ExecBridge =
        com.lhzkml.jasmine.core.plugin.proxy.ExecBridge(requireApp())

    /**
     * Registers a named host capability invocable from any process by command
     * name (OpenMinis `native_offload`-style). See
     * [com.lhzkml.jasmine.core.plugin.process.OffloadDispatcher].
     */
    fun registerOffload(name: String, handler: com.lhzkml.jasmine.core.plugin.process.OffloadHandler) =
        com.lhzkml.jasmine.core.plugin.process.OffloadDispatcher.register(name, handler)

    /** Unregisters a named capability. */
    fun unregisterOffload(name: String) =
        com.lhzkml.jasmine.core.plugin.process.OffloadDispatcher.unregister(name)

    /**
     * Invokes a named capability; local handlers run in-process, unregistered
     * names route over the abstract socket to the owning (host) process.
     */
    fun dispatchOffload(
        name: String,
        argv: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): com.lhzkml.jasmine.core.plugin.process.OffloadResult =
        com.lhzkml.jasmine.core.plugin.process.OffloadDispatcher.dispatch(name, argv, env)

    /**
     * Moves a plugin into the isolated `:plugin_isolated` process (heavy
     * native / crash containment). See
     * [com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager].
     */
    suspend fun isolatePlugin(pluginId: String): Boolean =
        com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager.isolate(pluginId)

    /** Releases a plugin's isolation (stops its private process). */
    fun releaseIsolation(pluginId: String) {
        com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager.release(pluginId)
        emit(PluginEvent.IsolationReleased(pluginId))
    }

    /**
     * Publishes a Binder-backed cross-process service. In the isolated
     * process this registers into the process-local bridge directory; see
     * [com.lhzkml.jasmine.core.plugin.process.RemoteServices].
     */
    fun publishRemoteService(
        key: com.lhzkml.jasmine.core.plugin.process.RemoteServiceKey,
        service: android.os.IBinder,
    ) = com.lhzkml.jasmine.core.plugin.process.RemoteServices.publish(key, service)

    /** Resolves a Binder-backed cross-process service (local-first, then bridge). */
    fun resolveRemoteService(
        key: com.lhzkml.jasmine.core.plugin.process.RemoteServiceKey,
    ): android.os.IBinder? = com.lhzkml.jasmine.core.plugin.process.RemoteServices.resolve(key)

    /** Checks and installs updates for every installed plugin (best effort). */
    suspend fun applyAvailableUpdates(): List<String> = withContext(Dispatchers.IO) {
        val channel = updateChannel() ?: return@withContext emptyList()
        val updated = mutableListOf<String>()
        for (pluginId in allPlugins().map { it.pluginId }) {
            runCatching { if (channel.installUpdate(pluginId)) updated += pluginId }
        }
        updated
    }

    val isInitialized: Boolean get() = core != null

    /**
     * Reactive view of every loaded plugin's settings-list menu entry
     * (pluginId → entry). Emits on load/unload, so the host's settings list
     * adds/removes plugin entries dynamically.
     */
    private val _loadedMenuEntries =
        MutableStateFlow<Map<String, PluginMenuEntry>>(emptyMap())
    val loadedMenuEntries: StateFlow<Map<String, PluginMenuEntry>> =
        _loadedMenuEntries.asStateFlow()

    /**
     * Opens the decision core (recovering crash-interrupted ledger
     * rotations) and loads every enabled plugin. [loadFilter] decides which
     * records auto-load in this process — the host filters out isolated
     * plugins, the isolated process loads nothing up front (it is driven by
     * [IsolatedPluginProcessService] instead).
     */
    suspend fun initialize(
        application: Application,
        policy: SignaturePolicy,
        loadFilter: (FfiPluginRecord) -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (core != null) {
                if (!ready.isCompleted) ready.complete(Unit)
                return@withLock
            }
            val handle = PluginCoreHandle.open(
                path = File(application.filesDir, "plugins.json").absolutePath,
                strategy = policy.toFfi(),
                hostSignatureDigests = Signatures.hostDigests(application).toList(),
            )
            val install = InstallExecutor(application)
            val lc = LifecycleExecutor(
                application = application,
                core = handle,
                payloadFile = install::payloadFile,
                libDir = install::libDir,
                readDependencies = install::readDependencies,
                readUiEntryClass = install::readUiEntryClass,
                readApplicationClass = install::readApplicationClass,
                failureCallback = loadFailureCallback,
            )
            lc.onChange = { refreshMenuEntries(lc) }
            core = handle
            executor = install
            lifecycle = lc
            app = application
            // 回放持久化的授权到 Rust 核心（Rust 侧 grants 是会话级缓存）。
            loadPersistedGrants()
            persistedGrants.forEach { (k, granted) ->
                if (granted) {
                    val sep = k.indexOf('\u0000')
                    handle.recordGrant(k.substring(0, sep), k.substring(sep + 1), true)
                }
            }
            lc.loadEnabled(handle.allRecords().filter(loadFilter))
            refreshMenuEntries(lc)
            // The host serves the named-capability offload channel; the
            // isolated process only consumes it (handlers register in the host).
            if (!com.lhzkml.jasmine.core.plugin.process.ProcessIdentity
                    .isIsolatedProcess(application)
            ) {
                runCatching {
                    com.lhzkml.jasmine.core.plugin.process.OffloadDispatcher.startServer()
                }
            }
            ready.complete(Unit)
        }
    }

    /** Suspends until [initialize] has finished (idempotent once ready). */
    suspend fun awaitReady() {
        ready.await()
    }

    /**
     * Installs or updates a plugin package: charter adjudication → payload
     * placement → transactional ledger commit. Rolls the files back when
     * the commit fails.
     */
    suspend fun installPlugin(
        apk: File,
        expectedSha256: String? = null,
        forceOverwrite: Boolean = false,
    ): FfiPluginRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val handle = requireCore()
            val install = requireExecutor()

            val metadata = install.readMetadata(apk)
            val packageSha256 = install.digestOf(apk)
            val request = FfiInstallRequest(
                pluginId = metadata.packageName,
                versionCode = metadata.versionCode.toULong(),
                signatureDigests = Signatures.packageDigests(requireApp(), apk.absolutePath).toList(),
                packageSha256 = packageSha256,
                expectedSha256 = expectedSha256,
                forceOverwrite = forceOverwrite,
                capabilities = emptyList(),
            )
            when (val verdict = handle.adjudicateInstall(request)) {
                FfiVerdict.Allow -> Unit
                is FfiVerdict.RequireUserGrant -> {
                    val granted = authorizationHandler?.onAuthorization(
                        AuthorizationPrompt(AuthorizationPrompt.Kind.Install, metadata.packageName, verdict.reason),
                    ) == true
                    if (!granted) throw InstallException("安装未获授权: ${metadata.packageName}")
                }
                is FfiVerdict.Deny -> throw InstallException("安装被拒绝: ${verdict.reason}")
            }

            val classes = com.lhzkml.jasmine.core.plugin.internal.DexScanner.scanClassNames(apk)
            val receivers = install.parseReceivers(apk)
            val providers = install.parseProviders(apk)
            val permissions = install.parsePermissions(apk)

            // Capability declaration: parse from metadata, adjudicate through
            // the charter (escalate to user grant until each is authorized),
            // then persist onto the record.
            val capabilities = metadata.capabilities.mapNotNull { name ->
                when (name) {
                    "exec" -> FfiCapability.EXEC
                    "gpu" -> FfiCapability.GPU
                    "network" -> FfiCapability.NETWORK
                    "storage" -> FfiCapability.STORAGE
                    "camera" -> FfiCapability.CAMERA
                    else -> null
                }
            }
            if (capabilities.isNotEmpty()) {
                when (val verdict = handle.adjudicateCapabilities(metadata.packageName, capabilities)) {
                    FfiVerdict.Allow -> Unit
                    is FfiVerdict.RequireUserGrant -> {
                        val granted = authorizationHandler?.onAuthorization(
                            AuthorizationPrompt(AuthorizationPrompt.Kind.Install, metadata.packageName, verdict.reason),
                        ) == true
                        if (granted) {
                            capabilities.forEach {
                                handle.recordGrant(metadata.packageName, "capability:${it.name.lowercase()}", true)
                            }
                        } else {
                            throw InstallException("能力授权被拒绝: ${metadata.packageName}")
                        }
                    }
                    is FfiVerdict.Deny -> throw InstallException("能力被拒绝: ${verdict.reason}")
                }
            }

            val backup = install.placePayload(metadata.packageName, apk, classes)
            install.writePermissions(metadata.packageName, permissions)
            install.writeDependencies(metadata.packageName, metadata.dependencies)
            install.writeUiEntryClass(metadata.packageName, metadata.uiEntryClass)
            install.writeApplicationClass(metadata.packageName, metadata.applicationClass)
            val record = install.buildRecord(
                metadata, request.signatureDigests, packageSha256, classes, receivers, providers,
                capabilities,
            )
            try {
                handle.commitInstall(record)
                install.dropBackup(backup)
            } catch (e: Throwable) {
                install.rollback(metadata.packageName, backup)
                throw e
            }
            // Persist the isolation placement declared by the package; the
            // actual process move happens at launch time.
            if (metadata.isolated) {
                com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager
                    .markIsolated(metadata.packageName)
            }
            // Warn about permissions the plugin requests but the host does not
            // declare — the plugin runs with the host's permission set, so a
            // missing declaration means the feature silently won't work.
            warnMissingHostPermissions(metadata.packageName, permissions)
            emit(PluginEvent.Installed(metadata.packageName))
            record
        }
    }

    /**
     * Installs a plugin after first installing any missing declared
     * dependencies (via the update channel, best effort). Lets the plugin
     * manager install a plugin whose dependencies aren't bundled yet in one
     * step instead of failing on a missing dependency.
     */
    suspend fun installWithDependencies(apk: File, expectedSha256: String? = null): FfiPluginRecord =
        withContext(Dispatchers.IO) {
            val metadata = requireExecutor().readMetadata(apk)
            val channel = updateChannel()
            for (dep in metadata.dependencies) {
                if (pluginRecord(dep) == null && channel != null) {
                    runCatching { channel.installLatest(dep) }
                }
            }
            installPlugin(apk, expectedSha256 = expectedSha256)
        }

    /**
     * Installs plugins bundled in the host's `assets/plugins/` directory
     * (the development-mode distribution channel). Packages already
     * installed at the same version and digest are skipped, so calling this
     * on every launch is cheap. Returns the ids that were installed or
     * updated.
     */
    suspend fun installBundledPlugins(assetsDir: String = "plugins"): List<String> =
        withContext(Dispatchers.IO) {
            val application = requireApp()
            val install = requireExecutor()
            val names = application.assets.list(assetsDir)
                ?.filter { it.endsWith(".apk") } ?: return@withContext emptyList()
            val installed = mutableListOf<String>()
            for (name in names) {
                val staged = File(application.cacheDir, "bundled-$name")
                application.assets.open("$assetsDir/$name").use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                }
                try {
                    val digest = install.digestOf(staged)
                    val metadata = install.readMetadata(staged)
                    val existing = pluginRecord(metadata.packageName)
                    if (existing != null &&
                        existing.packageSha256 == digest &&
                        existing.versionCode == metadata.versionCode.toULong()
                    ) {
                        continue // same payload already installed
                    }
                    installPlugin(staged, forceOverwrite = existing != null)
                    installed += metadata.packageName
                } finally {
                    staged.delete()
                }
            }
            installed
        }

    /** Unloads (when loaded), removes files, and commits the uninstall. */
    suspend fun uninstallPlugin(pluginId: String): FfiPluginRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val lc = requireLifecycle()
            if (lc.isLoaded(pluginId)) lc.unload(pluginId)
            val record = requireCore().commitUninstall(pluginId)
            File(record.installPath).deleteRecursively()
            clearPluginPrefs(pluginId)
            clearPluginGrants(pluginId)
            emit(PluginEvent.Uninstalled(pluginId))
            record
        }
    }

    /** Deletes the plugin's namespaced SharedPreferences (`plugin_<id>_*.xml`). */
    private fun clearPluginPrefs(pluginId: String) {
        val application = app ?: return
        val prefsDir = File(application.dataDir, "shared_prefs")
        if (!prefsDir.isDirectory) return
        val prefix = "plugin_${pluginId}_"
        prefsDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".xml") }
            ?.forEach { it.delete() }
    }

    // --- grant persistence -------------------------------------------------

    private fun grantsFile(): File? = app?.let { File(it.filesDir, "plugin_grants.json") }

    private fun grantKey(pluginId: String, permissionKey: String): String =
        "$pluginId\u0000$permissionKey"

    /** Loads persisted grants into the Kotlin-side cache. */
    private fun loadPersistedGrants() {
        val file = grantsFile() ?: return
        if (!file.exists()) return
        runCatching {
            grantJson.decodeFromString<List<PersistedGrant>>(file.readText())
        }.getOrDefault(emptyList()).forEach { g ->
            persistedGrants[grantKey(g.pluginId, g.permissionKey)] = g.granted
        }
    }

    /** Persists a grant and rewrites the grant file. */
    private fun persistGrant(pluginId: String, permissionKey: String, granted: Boolean) {
        persistedGrants[grantKey(pluginId, permissionKey)] = granted
        rewriteGrantFile()
    }

    /** Drops a plugin's persisted grants (uninstall). */
    private fun clearPluginGrants(pluginId: String) {
        val prefix = "$pluginId\u0000"
        persistedGrants.keys.removeIf { it.startsWith(prefix) }
        rewriteGrantFile()
    }

    private fun rewriteGrantFile() {
        val file = grantsFile() ?: return
        runCatching {
            val list = persistedGrants.map { (k, v) ->
                val sep = k.indexOf('\u0000')
                PersistedGrant(k.substring(0, sep), k.substring(sep + 1), v)
            }
            file.writeText(grantJson.encodeToString(list))
        }
    }

    /**
     * Loads a plugin; when it is already loaded, executes the core's
     * chained-restart plan instead (dependents unload first, reload last).
     *
     * Isolated plugins never load in the host process: a launch from the
     * host delegates to the isolated process, while the isolated process
     * itself loads directly (its [initialize] load-filter already kept the
     * plugin out of the host).
     */
    suspend fun launchPlugin(pluginId: String): Unit = withContext(Dispatchers.IO) {
        val isolated = com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager
            .isIsolated(pluginId)
        val inIsolatedProcess = com.lhzkml.jasmine.core.plugin.process.ProcessIdentity
            .isIsolatedProcess(requireApp())
        if (isolated && !inIsolatedProcess) {
            com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager.isolate(pluginId)
            emit(PluginEvent.Isolated(pluginId))
            return@withContext
        }
        mutex.withLock {
            val handle = requireCore()
            val lc = requireLifecycle()
            if (lc.isLoaded(pluginId)) {
                val plan = handle.restartPlan(pluginId)
                val reloadRecords = plan.reloadOrder.mapNotNull { handle.pluginRecord(it) }
                lc.executeRestart(plan.unloadOrder, reloadRecords)
            } else {
                val record = handle.pluginRecord(pluginId)
                    ?: throw InstallException("插件未安装: $pluginId")
                lc.load(record)
            }
        }
        emit(PluginEvent.Loaded(pluginId))
    }

    /** Unloads a running plugin; the registry entry stays. */
    suspend fun unloadPlugin(pluginId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { requireLifecycle().unload(pluginId) }
        emit(PluginEvent.Unloaded(pluginId))
    }

    /** Enables or disables a plugin; takes effect on the next process start. */
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { requireCore().setEnabled(pluginId, enabled) }
    }

    // --- queries -----------------------------------------------------------

    fun allPlugins(): List<FfiPluginRecord> = requireCore().allRecords()

    fun pluginRecord(pluginId: String): FfiPluginRecord? = requireCore().pluginRecord(pluginId)

    fun isLoaded(pluginId: String): Boolean = lifecycle?.isLoaded(pluginId) == true

    fun loadedPluginIds(): List<String> = lifecycle?.loadedIds() ?: emptyList()

    /** Forwards host low-memory to every loaded plugin Application. */
    fun notifyLowMemory() {
        lifecycle?.notifyLowMemory()
    }

    /** Forwards host trim-memory to every loaded plugin Application. */
    fun notifyTrimMemory(level: Int) {
        lifecycle?.notifyTrimMemory(level)
    }

    fun entryOf(pluginId: String): PluginEntry? = lifecycle?.entryOf(pluginId)

    /** The loaded plugin's own resources (explicit-injection model). */
    fun resourcesOf(pluginId: String): android.content.res.Resources? =
        lifecycle?.loadedPlugins?.get(pluginId)?.resources

    fun dependentsChain(pluginId: String): List<String> = requireCore().dependentsChain(pluginId)

    fun dependenciesChain(pluginId: String): List<String> = requireCore().dependenciesChain(pluginId)

    /** Resolves a published cross-plugin service. */
    fun <T : Any> resolveService(key: ServiceKey<T>): T? = lifecycle?.resolveService(key)

    /** Three-way reconciliation: registry ↔ class index ↔ loaded set. */
    fun audit(): FfiAuditReport = requireCore().audit(loadedPluginIds())

    /** Fixes index drift on sight; returns what was repaired. */
    fun repair(): FfiAuditReport = requireCore().repair()

    // --- sensitive API gating ----------------------------------------------

    /**
     * Gates a sensitive API call through the charter. `callerPluginId` of
     * null means the host itself (explicit allow); an Ask verdict routes to
     * the authorization handler, and a granted answer is cached by the core
     * for the session.
     */
    suspend fun checkApi(
        rule: ApiRule,
        callerPluginId: String?,
        targetPluginId: String,
        permissionKey: String,
        hardFail: Boolean = false,
    ): Boolean {
        val handle = requireCore()
        val caller = when (callerPluginId) {
            null -> FfiCallerIdentity.Host
            else -> FfiCallerIdentity.Plugin(
                pluginId = callerPluginId,
                signatureDigests = handle.pluginRecord(callerPluginId)?.signatureDigests
                    ?: emptyList(),
            )
        }
        return when (val verdict = handle.checkApiAccess(
            rule.toFfi(), hardFail, caller, targetPluginId, permissionKey,
        )) {
            FfiVerdict.Allow -> true
            is FfiVerdict.Deny -> false
            is FfiVerdict.RequireUserGrant -> {
                val granted = authorizationHandler?.onAuthorization(
                    AuthorizationPrompt(
                        AuthorizationPrompt.Kind.Api,
                        callerPluginId ?: targetPluginId,
                        verdict.reason,
                    ),
                ) == true
                if (granted && callerPluginId != null) {
                    handle.recordGrant(callerPluginId, permissionKey, true)
                    persistGrant(callerPluginId, permissionKey, true)
                }
                granted
            }
        }
    }

    /**
     * Gates a declared capability at runtime (exec / gpu / network / storage
     * / camera). The plugin must have declared it at install and hold a user
     * grant; otherwise the call escalates through the authorization handler.
     * A `null` caller means the host itself (explicit allow).
     */
    suspend fun checkCapability(
        capability: FfiCapability,
        callerPluginId: String?,
        hardFail: Boolean = false,
    ): Boolean {
        val permissionKey = "capability:${capability.name.lowercase()}"
        return checkApi(
            rule = ApiRule.SelfOrHost,
            callerPluginId = callerPluginId,
            targetPluginId = callerPluginId ?: "",
            permissionKey = permissionKey,
            hardFail = hardFail,
        )
    }

    /**
     * 能力是否已声明：插件安装时在 manifest 声明并经 `adjudicate_capabilities`
     * 裁决（拒绝则安装失败）。声明是运行时能力门控的唯一依据——不再重复授权。
     */
    fun hasCapability(capability: FfiCapability, pluginId: String): Boolean {
        val record = pluginRecord(pluginId) ?: return false
        return record.capabilities.contains(capability)
    }

    // --- runtime permission (host permission pool) -------------------------

    /** 检查宿主是否已授予某权限（插件运行在宿主进程，权限 = 宿主权限集）。 */
    fun hasPermission(permission: String): Boolean =
        app?.let {
            ContextCompat.checkSelfPermission(it, permission) == PackageManager.PERMISSION_GRANTED
        } ?: false

    /**
     * 请求一项运行时权限（宿主替插件弹系统授权对话框）。已在权限池内的权限
     * 才有效；结果经挂起返回，供插件在 suspend 上下文等待授权。
     */
    suspend fun requestPermission(permission: String): Boolean = withContext(Dispatchers.Main) {
        val application = app ?: return@withContext false
        if (hasPermission(permission)) return@withContext true
        val requestCode = nextPermissionRequestCode()
        val deferred = CompletableDeferred<Boolean>()
        permissionRequests[requestCode] = deferred
        val intent = Intent(application, com.lhzkml.jasmine.core.plugin.auth.PermissionRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(com.lhzkml.jasmine.core.plugin.auth.PermissionRequestActivity.EXTRA_PERMISSION, permission)
            .putExtra(com.lhzkml.jasmine.core.plugin.auth.PermissionRequestActivity.EXTRA_REQUEST_CODE, requestCode)
        application.startActivity(intent)
        deferred.await()
    }

    /** PermissionRequestActivity 回传授权结果。 */
    internal fun completePermissionRequest(requestCode: Int, granted: Boolean) {
        permissionRequests.remove(requestCode)?.complete(granted)
    }

    /**
     * 观测上次进程是否因 native 崩溃（SIGSEGV 等）退出——Java 的
     * CrashHook 无法捕获 native 崩溃，这里用 ApplicationExitInfo（Android 11+）
     * 补齐，并结合崩溃标记归因到插件、emit Crash 事件。
     */
    fun observePreviousNativeCrash() {
        val application = app ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = application.getSystemService(ActivityManager::class.java) ?: return
        val nativeCrash = am.getHistoricalProcessExitReasons(null, 0, 5)
            .any { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
        if (!nativeCrash) return
        val markerDir = File(application.filesDir, "crashed_plugins")
        val culprit = markerDir.listFiles()
            ?.firstOrNull { it.name.endsWith(".crash") }
            ?.name?.removeSuffix(".crash")
        emit(
            PluginEvent.Crash(
                pluginId = culprit ?: "",
                kind = "NATIVE",
                blameAttributed = culprit != null,
            ),
        )
    }

    private val permissionRequests = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    private fun nextPermissionRequestCode(): Int =
        (System.nanoTime() and 0xFFFF).toInt()

    /**
     * 强制能力门控：插件必须已声明该能力，否则抛 [SecurityException]。
     * 与 [checkCapability]（软门控，返回布尔）互补，用于"未声明即失败"的
     * 敏感设施（exec / gpu / network / storage / camera）。
     */
    fun requireCapability(capability: FfiCapability, pluginId: String) {
        if (!hasCapability(capability, pluginId)) {
            throw SecurityException(
                "插件 [$pluginId] 未声明能力 ${capability.name}；请在 manifest 声明 " +
                    "jasmine.plugin.capabilities 并在安装时授权",
            )
        }
    }

    /**
     * The plugin's `uses-permission` list, parsed at install. The host must
     * pre-declare these in its own manifest (Android cannot grant permissions
     * at runtime); use this to verify coverage.
     */
    fun requiredPermissionsOf(pluginId: String): List<String> =
        requireExecutor().readPermissions(pluginId)

    /** Logs permissions a plugin requests but the host does not declare. */
    private fun warnMissingHostPermissions(pluginId: String, permissions: List<String>) {
        if (permissions.isEmpty()) return
        val application = app ?: return
        val hostPermissions = runCatching {
            application.packageManager
                .getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
        val missing = permissions.filter { it !in hostPermissions }
        if (missing.isNotEmpty()) {
            Log.w(
                "PluginRuntime",
                "插件 [$pluginId] 声明了宿主未声明的权限，相关功能将失效: $missing",
            )
        }
    }

    // --- proxy support (internal to the runtime) ----------------------------

    internal val coreHandle: PluginCoreHandle get() = requireCore()

    /**
     * Re-reads the ledger from disk ([PluginCoreHandle.repair]), reconciling
     * registry/index/loaded. The isolated process calls this before loading a
     * plugin so a host-side install/update is visible across the process
     * boundary (the two processes hold independent in-memory ledgers).
     */
    suspend fun refreshLedger(): com.lhzkml.jasmine.core.plugin.rust.FfiAuditReport =
        withContext(Dispatchers.IO) { requireCore().repair() }

    /** Instantiates a loaded plugin's component class (receiver, provider, …). */
    internal fun instantiateComponent(pluginId: String, className: String): Any {
        val plugin = lifecycle?.loadedPlugins?.get(pluginId)
            ?: throw IllegalStateException("插件未加载: $pluginId")
        return plugin.classLoader.loadClass(className).getDeclaredConstructor().newInstance()
    }

    /** Structured provider spec for the proxy layer's exported gate. */
    internal fun providerSpecOf(
        pluginId: String,
        className: String,
    ): com.lhzkml.jasmine.core.plugin.internal.ProviderSpec? =
        pluginRecord(pluginId)
            ?.providersJson.providersFromJson()
            ?.firstOrNull { it.className == className }

    private fun requireCore(): PluginCoreHandle =
        core ?: error("PluginHost 未初始化，请先调用 initialize()")

    private fun requireExecutor(): InstallExecutor =
        executor ?: error("PluginHost 未初始化")

    private fun requireLifecycle(): LifecycleExecutor =
        lifecycle ?: error("PluginHost 未初始化")

    private fun requireApp(): Application = app ?: error("PluginHost 未初始化")

    private fun refreshMenuEntries(lc: com.lhzkml.jasmine.core.plugin.internal.LifecycleExecutor) {
        _loadedMenuEntries.value = lc.loadedPlugins.mapNotNull { (pluginId, plugin) ->
            plugin.entry.menuEntry?.let { pluginId to it }
        }.toMap()
    }
}
