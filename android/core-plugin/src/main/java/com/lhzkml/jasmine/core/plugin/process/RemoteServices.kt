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
     * bridge. Returns null when nothing published under [key].
     */
    fun resolve(key: RemoteServiceKey): IBinder? {
        PluginProcessBridge.local.resolve(key.name)?.let { return it }
        return ProcessIsolationManager.bridge()?.resolve(key.name)
    }

    /** Names visible through the current process's directory + bridge. */
    fun names(): List<String> {
        val seen = linkedSetOf<String>()
        seen += PluginProcessBridge.local.names()
        ProcessIsolationManager.bridge()?.names()?.let { seen += it }
        return seen.toList()
    }
}
