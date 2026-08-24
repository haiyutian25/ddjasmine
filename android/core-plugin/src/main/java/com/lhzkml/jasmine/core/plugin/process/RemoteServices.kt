package com.lhzkml.jasmine.core.plugin.process

import android.os.IBinder

/**
 * A cross-process service key. Unlike the in-process [com.lhzkml.jasmine.core
 * .plugin.ServiceKey] (whose value is an arbitrary object), a remote service
 * must be a [IBinder] (or an `IInterface` reachable through `asBinder`), so it
 * can cross the process boundary.
 */
class RemoteServiceKey(val name: String) {
    override fun toString(): String = "RemoteServiceKey($name)"
}

/**
 * Cross-process service publication and resolution.
 *
 * A plugin running in the isolated process publishes a Binder-backed service
 * under a [RemoteServiceKey]; [publish] registers it into that process's
 * bridge directory directly (no self-transact). A consumer — in the host or
 * another process — calls [resolve], which checks the local directory first
 * (same-process case) and then the host's bound bridge (cross-process case).
 *
 * This is the process-boundary counterpart of the in-process `ServiceKey`
 * table: same key semantics, but the value is always a Binder token.
 */
object RemoteServices {

    /** Publishes a Binder-backed service (isolated-process side). */
    fun publish(key: RemoteServiceKey, service: IBinder) {
        PluginProcessBridge.local.register(key.name, service)
    }

    /** Unpublishes a service. */
    fun unpublish(key: RemoteServiceKey) {
        PluginProcessBridge.local.unregister(key.name)
    }

    /**
     * Resolves a remote service. Same-process hits come from the local
     * directory; cross-process hits round-trip through the host's bound
     * bridge (host → isolated) or the host directory the host registered into
     * this process (isolated → host). Returns null when nothing is published
     * under [key].
     */
    fun resolve(key: RemoteServiceKey): IBinder? {
        PluginProcessBridge.local.resolve(key.name)?.let { return it }
        val app = ProcessIsolationManager.application()
        if (app != null && ProcessIdentity.isIsolatedProcess(app)) {
            // 隔离进程：经宿主注册进来的目录 binder 解析宿主发布的远程服务。
            val hostDir = PluginProcessBridge.local.resolve(PluginProcessBridge.HOST_DIRECTORY_KEY)
            hostDir?.let { PluginProcessBridge.wrap(it)?.resolve(key.name)?.let { r -> return r } }
            return null
        }
        // 宿主进程：经各槽 bridge 解析隔离进程发布的远程服务。
        for (bridge in ProcessIsolationManager.bridges()) {
            bridge.resolve(key.name)?.let { return it }
        }
        return null
    }

    /** Names visible through the current process's directory + all slot bridges. */
    fun names(): List<String> {
        val seen = linkedSetOf<String>()
        seen += PluginProcessBridge.local.names().filter { it != PluginProcessBridge.HOST_DIRECTORY_KEY }
        ProcessIsolationManager.bridges().forEach {
            seen += it.names().filter { n -> n != PluginProcessBridge.HOST_DIRECTORY_KEY }
        }
        return seen.toList()
    }
}
