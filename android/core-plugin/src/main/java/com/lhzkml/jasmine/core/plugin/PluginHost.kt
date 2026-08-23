package com.lhzkml.jasmine.core.plugin

import android.app.Application
import com.lhzkml.jasmine.core.plugin.internal.InstallException
import com.lhzkml.jasmine.core.plugin.internal.InstallExecutor
import com.lhzkml.jasmine.core.plugin.internal.LifecycleExecutor
import com.lhzkml.jasmine.core.plugin.internal.LoadFailureCallback
import com.lhzkml.jasmine.core.plugin.internal.Signatures
import com.lhzkml.jasmine.core.plugin.internal.providersFromJson
import com.lhzkml.jasmine.core.plugin.rust.FfiAccessRule
import com.lhzkml.jasmine.core.plugin.rust.FfiAuditReport
import com.lhzkml.jasmine.core.plugin.rust.FfiCallerIdentity
import com.lhzkml.jasmine.core.plugin.rust.FfiInstallRequest
import com.lhzkml.jasmine.core.plugin.rust.FfiPluginRecord
import com.lhzkml.jasmine.core.plugin.rust.FfiSignatureStrategy
import com.lhzkml.jasmine.core.plugin.rust.FfiVerdict
import com.lhzkml.jasmine.core.plugin.rust.PluginCoreHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
     * rotations) and loads every enabled plugin.
     */
    suspend fun initialize(application: Application, policy: SignaturePolicy) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (core != null) return@withLock
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
                failureCallback = loadFailureCallback,
            )
            lc.onChange = { refreshMenuEntries(lc) }
            core = handle
            executor = install
            lifecycle = lc
            app = application
            lc.loadEnabled(handle.allRecords())
            refreshMenuEntries(lc)
        }
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
            val backup = install.placePayload(metadata.packageName, apk, classes)
            val record = install.buildRecord(
                metadata, request.signatureDigests, packageSha256, classes, receivers, providers,
            )
            try {
                handle.commitInstall(record)
                install.dropBackup(backup)
            } catch (e: Throwable) {
                install.rollback(metadata.packageName, backup)
                throw e
            }
            record
        }
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
            record
        }
    }

    /**
     * Loads a plugin; when it is already loaded, executes the core's
     * chained-restart plan instead (dependents unload first, reload last).
     */
    suspend fun launchPlugin(pluginId: String): Unit = withContext(Dispatchers.IO) {
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
    }

    /** Unloads a running plugin; the registry entry stays. */
    suspend fun unloadPlugin(pluginId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { requireLifecycle().unload(pluginId) }
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
                }
                granted
            }
        }
    }

    // --- proxy support (internal to the runtime) ----------------------------

    internal val coreHandle: PluginCoreHandle get() = requireCore()

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
