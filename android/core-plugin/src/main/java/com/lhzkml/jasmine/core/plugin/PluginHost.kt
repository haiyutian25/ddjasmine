package com.lhzkml.jasmine.core.plugin

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.lhzkml.jasmine.core.database.JasmineDatabase
import com.lhzkml.jasmine.core.database.JasmineDatabaseProvider
import com.lhzkml.jasmine.core.database.PluginGrant
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** 用户授权弹框的最长等待；超时按拒绝处理（见 requestUserGrant）。 */
    private const val USER_GRANT_TIMEOUT_MS = 120_000L

    private val mutex = Mutex()

    /** 框架内部异步任务（如 UI 伴侣加载后启动隔离进程），不依赖调用方生命周期。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var core: PluginCoreHandle? = null
    private var executor: InstallExecutor? = null
    private var lifecycle: LifecycleExecutor? = null
    private var app: Application? = null

    /** 授权账本：持久化复用宿主的 Room（core-database），跨进程/重启保留。 */
    private var grantDb: JasmineDatabase? = null

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

    /**
     * 崩溃熔断标记目录（宿主在 initialize 前设置，与 CrashHook.install 的
     * crashMarkerDir 同一目录）。安装/卸载成功时清除对应 `<pluginId>.crash`
     * 标记——否则插件崩溃一次即被永久禁用，重装/更新同 id 都无法解禁。
     */
    var crashMarkerDir: File? = null

    private fun clearCrashMarker(pluginId: String) {
        crashMarkerDir?.let { dir ->
            runCatching { File(dir, "$pluginId.crash").delete() }
        }
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

    /** Lazily-created supervisor backing [childProcessSupervisor]. */
    @Volatile
    private var childSupervisor: com.lhzkml.jasmine.core.plugin.proxy.ChildProcessSupervisor? = null

    /**
     * Subprocess lifecycle supervisor (§5.3): managed children started via
     * [execBridge]'s `runViaLinker`, with output pumping, death callbacks and
     * opt-in restart. Children are reaped when the owning plugin unloads or
     * uninstalls.
     */
    fun childProcessSupervisor(): com.lhzkml.jasmine.core.plugin.proxy.ChildProcessSupervisor {
        childSupervisor?.let { return it }
        return synchronized(this) {
            childSupervisor ?: com.lhzkml.jasmine.core.plugin.proxy.ChildProcessSupervisor(execBridge())
                .also { childSupervisor = it }
        }
    }

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
            // 两阶段提交：先完成全部可能失败的工作（打开账本、回放授权、
            // 加载启用插件），成功后才提交状态。此前 core 在 loadEnabled
            // 之前提交——加载失败会同时造成 ready 永挂起、重试时误判
            // “已初始化”而静默跳过全部插件加载。
            try {
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
                // 宿主加载隔离插件的 UI 伴侣后，异步启动隔离进程的主入口。
                // 不能在加载批次（持锁）内同步做跨进程操作。
                lc.onUiCompanionLoaded = { pluginId ->
                    scope.launch {
                        runCatching {
                            com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager
                                .isolate(pluginId)
                        }.onFailure {
                            Log.e("PluginHost", "启动隔离进程失败: $pluginId", it)
                        }
                    }
                }
                // 授权账本：复用宿主的 Room 单例并回放持久化授权到 Rust 核心（Rust grants 会话级）。
                val db = JasmineDatabaseProvider.get(application)
                db?.pluginGrantDao()?.grantedEntries()?.forEach { g ->
                    handle.recordGrant(g.pluginId, g.permissionKey, true)
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
                core = handle
                executor = install
                lifecycle = lc
                app = application
                grantDb = db
                ready.complete(Unit)
            } catch (e: Throwable) {
                core = null
                executor = null
                lifecycle = null
                app = null
                grantDb = null
                throw e
            }
        }
    }

    /** Suspends until [initialize] has finished (idempotent once ready). */
    suspend fun awaitReady() {
        ready.await()
    }

    /**
     * 用户授权的统一入口，带超时兜底：默认实现经 application.startActivity
     * 弹框，Android 10+ 后台启动限制（BAL）会静默丢弃该调用（无异常、无
     * 结果广播），continuation 将永不恢复——超时按拒绝处理，保证调用方
     * （安装/更新/checkApi）绝不永久挂起。
     */
    private suspend fun requestUserGrant(prompt: AuthorizationPrompt): Boolean {
        val handler = authorizationHandler ?: return false
        return withTimeoutOrNull(USER_GRANT_TIMEOUT_MS) {
            handler.onAuthorization(prompt)
        } == true
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
        val handle = requireCore()
        val install = requireExecutor()

        // ---- 锁外阶段：只读裁决 + 用户授权。授权会挂起等待用户操作，
        // 绝不能持锁（此前在 mutex 内等待，后台 BAL 丢弃授权 Activity 时
        // continuation 永不恢复，全局锁被永久持有，整个框架死锁）。----
        val metadata = install.readMetadata(apk)
        val packageSha256 = install.digestOf(apk)
        val signatureDigests = Signatures.packageDigests(requireApp(), apk.absolutePath).toList()
        val request = FfiInstallRequest(
            pluginId = metadata.packageName,
            versionCode = metadata.versionCode.toULong(),
            signatureDigests = signatureDigests,
            packageSha256 = packageSha256,
            expectedSha256 = expectedSha256,
            forceOverwrite = forceOverwrite,
            capabilities = emptyList(),
        )
        when (val verdict = handle.adjudicateInstall(request)) {
            FfiVerdict.Allow -> Unit
            is FfiVerdict.RequireUserGrant -> {
                val granted = requestUserGrant(
                    AuthorizationPrompt(AuthorizationPrompt.Kind.Install, metadata.packageName, verdict.reason),
                )
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
                    val granted = requestUserGrant(
                        AuthorizationPrompt(AuthorizationPrompt.Kind.Install, metadata.packageName, verdict.reason),
                    )
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

        // ---- 锁内阶段：payload 落盘 + 事务提交。全部磁盘写与账本提交
        // 纳入同一 try：任一步失败都走 rollback（此前四次 write* 在 try
        // 之外，中途失败会在磁盘留下与账本不一致的半成品）。----
        mutex.withLock {
            val backup = install.placePayload(metadata.packageName, apk, classes)
            val record = try {
                install.writePermissions(metadata.packageName, permissions)
                install.writeDependencies(metadata.packageName, metadata.dependencies)
                install.writeUiEntryClass(metadata.packageName, metadata.uiEntryClass)
                install.writeApplicationClass(metadata.packageName, metadata.applicationClass)
                val r = install.buildRecord(
                    metadata, signatureDigests, packageSha256, classes, receivers, providers,
                    capabilities,
                )
                handle.commitInstall(r)
                install.dropBackup(backup)
                r
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
            // 新装/更新成功即解除崩溃熔断（新版本理应重新获得加载机会）。
            clearCrashMarker(metadata.packageName)
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
        // 卸载路径不经过 unloadPlugin，这里同样要回收该插件的子进程（§5.3）。
        childSupervisor?.stopAll(pluginId)
        mutex.withLock {
            val lc = requireLifecycle()
            if (lc.isLoaded(pluginId)) lc.unload(pluginId)
            // 回收隔离进程：此前只卸宿主 UI 伴侣，隔离进程里的主入口副本继续
            // 运行（ClassLoader 指向即将删除的 payload）、服务存活、槽位与
            // isolated_plugins.json 不清理——"双活"在卸载路径复活。
            com.lhzkml.jasmine.core.plugin.process.ProcessIsolationManager.release(pluginId)
            val record = requireCore().commitUninstall(pluginId)
            File(record.installPath).deleteRecursively()
            grantDb?.pluginGrantDao()?.deleteByPlugin(pluginId)
            clearCrashMarker(pluginId)
            emit(PluginEvent.Uninstalled(pluginId))
            record
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
        // 先回收该插件经子进程监督器启动的子进程（§5.3），避免卸载后孤儿进程残留。
        childSupervisor?.stopAll(pluginId)
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

    /** 宿主里加载的是否为隔离插件的 UI 伴侣（主入口在隔离进程）。 */
    fun isUiCompanionLoaded(pluginId: String): Boolean =
        lifecycle?.isUiOnlyLoaded(pluginId) == true

    /** 转发配置变化，刷新插件 Resources 快照（宿主 Application 回调转发到此）。 */
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        lifecycle?.onConfigurationChanged(newConfig)
    }

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
                val granted = requestUserGrant(
                    AuthorizationPrompt(
                        AuthorizationPrompt.Kind.Api,
                        callerPluginId ?: targetPluginId,
                        verdict.reason,
                    ),
                )
                if (granted && callerPluginId != null) {
                    handle.recordGrant(callerPluginId, permissionKey, true)
                    grantDb?.pluginGrantDao()?.upsert(
                        PluginGrant(pluginId = callerPluginId, permissionKey = permissionKey, granted = true),
                    )
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
        // 只看本应用进程的 native 崩溃（此前传 null 会把其它应用/无关进程
        // 的退出也计入），取最近一条。
        val info = am.getHistoricalProcessExitReasons(application.packageName, 0, 5)
            .firstOrNull {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE &&
                    it.processName == application.packageName
            } ?: return
        // 去重：同一次崩溃只上报一次（此前每次启动都重复 emit）。以退出
        // 时间戳为幂等键，落盘后不再重复。
        val observed = File(application.filesDir, "native_crash_observed")
        val stamp = info.timestamp.toString()
        if (observed.exists() && observed.readText() == stamp) return
        runCatching { observed.writeText(stamp) }
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
