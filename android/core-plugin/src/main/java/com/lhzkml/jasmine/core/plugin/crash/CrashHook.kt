package com.lhzkml.jasmine.core.plugin.crash

import com.lhzkml.jasmine.core.plugin.PluginEvent
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.internal.PluginLinkException
import com.lhzkml.jasmine.core.plugin.rust.FfiCrashKind
import com.lhzkml.jasmine.core.plugin.rust.FfiDependencyFailure
import com.lhzkml.jasmine.core.plugin.rust.FfiExceptionFrame
import java.io.File

/** The crash hook's decision, surfaced to the host for policy (disable + restart). */
data class PluginCrash(
    val culpritPluginId: String?,
    val kind: Kind,
    val throwable: Throwable,
) {
    enum class Kind { Dependency, ClassCast, ResourceNotFound, ApiIncompatible, Other }
}

/** Host policy hook for plugin-attributed crashes. */
fun interface PluginCrashCallback {
    fun onPluginCrash(crash: PluginCrash)
}

/**
 * Installs early (before the runtime's own initialization, so plugin
 * failures during load are covered). Classification happens in the Rust
 * core: cause-chain attribution against the class index plus category
 * precedence. Non-attributable crashes fall through to the previously
 * installed handler untouched.
 */
object CrashHook {

    private var previous: Thread.UncaughtExceptionHandler? = null

    /**
     * Installs the hook — call before the runtime initializes, so plugin
     * failures during framework load are covered; attribution simply
     * reports nothing until the core is up.
     *
     * [crashMarkerDir]（可选）启用崩溃熔断：归因到插件后同步写入
     * `<pluginId>.crash` 标记，宿主下次启动据此跳过该插件，避免反复崩溃。
     */
    fun install(callback: PluginCrashCallback, crashMarkerDir: File? = null) {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val verdict = classify(throwable)
            if (verdict != null) {
                runCatching { callback.onPluginCrash(verdict) }
                // 归因到插件的崩溃也走统一事件通道，供宿主做禁用/熔断/上报。
                // emit 必须无防护地兜住：监听器抛异常会中断本 handler，导致
                // 后面的 previous?.uncaughtException / exitProcess 被跳过。
                runCatching {
                    PluginHost.emit(
                        PluginEvent.Crash(
                            pluginId = verdict.culpritPluginId ?: "",
                            kind = verdict.kind.name,
                            blameAttributed = verdict.culpritPluginId != null,
                        ),
                    )
                }
                // 崩溃熔断：同步写标记（崩溃 handler 里只做最轻量、无锁的写入）。
                verdict.culpritPluginId?.let { id ->
                    crashMarkerDir?.let { dir ->
                        runCatching { File(dir, "$id.crash").createNewFile() }
                    }
                }
            }
            previous?.uncaughtException(thread, throwable)
                ?: kotlin.system.exitProcess(10)
        }
    }

    /** Classifies without installing anything (tests, manual checks). */
    fun classify(throwable: Throwable): PluginCrash? {
        if (!PluginHost.isInitialized) return null
        val chain = mutableListOf<FfiExceptionFrame>()
        var dependencyFailure: FfiDependencyFailure? = null
        var current: Throwable? = throwable
        while (current != null) {
            if (current is PluginLinkException) {
                dependencyFailure = FfiDependencyFailure(
                    culpritPluginId = current.culpritPluginId,
                    missingClass = current.missingClassName,
                )
            }
            chain += FfiExceptionFrame(
                className = current.javaClass.name,
                stackClasses = current.stackTrace.map { it.className },
            )
            current = current.cause
        }
        val verdict = PluginHost.coreHandle.classifyCrash(chain, dependencyFailure)
            ?: return null
        val kind = when (verdict.kind) {
            FfiCrashKind.DEPENDENCY -> PluginCrash.Kind.Dependency
            FfiCrashKind.CLASS_CAST -> PluginCrash.Kind.ClassCast
            FfiCrashKind.RESOURCE_NOT_FOUND -> PluginCrash.Kind.ResourceNotFound
            FfiCrashKind.API_INCOMPATIBLE -> PluginCrash.Kind.ApiIncompatible
            FfiCrashKind.OTHER -> PluginCrash.Kind.Other
        }
        return PluginCrash(verdict.culpritPluginId, kind, throwable)
    }
}
