package com.lhzkml.jasmine.core.plugin.process

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-process service bridge. Plugins in an isolated process publish
 * service handles under a string name; consumers in the host (or another
 * plugin process) resolve the name to a Binder token they can
 * `queryLocalInterface`/`transact` against. This is the process-boundary
 * half of `ServiceKey`: in-process lookups stay a plain `Map`, while
 * cross-process lookups round-trip through this bridge.
 *
 * Hand-written `IInterface` (no AIDL) so the library keeps
 * `buildFeatures.aidl = false`.
 *
 * The server side is a **process-local singleton** — the same directory is
 * returned on every bind, so a plugin publishing at load and a host resolving
 * later see one shared namespace. In-process callers can use [local] to
 * register/query directly (no self-transact).
 */
class PluginProcessBridge private constructor(
    private val handle: IBinder,
) : IInterface {

    override fun asBinder(): IBinder = handle

    fun register(name: String, token: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(name)
            data.writeStrongBinder(token)
            handle.transact(TX_REGISTER, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun unregister(name: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(name)
            handle.transact(TX_UNREGISTER, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun resolve(name: String): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(name)
            handle.transact(TX_RESOLVE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun names(): List<String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            handle.transact(TX_NAMES, data, reply, 0)
            reply.readException()
            reply.createStringArrayList() ?: emptyList()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val DESCRIPTOR = "jasmine.plugin.process.bridge"
        private const val TX_REGISTER = 1
        private const val TX_UNREGISTER = 2
        private const val TX_RESOLVE = 3
        private const val TX_NAMES = 4

        /** The process-local singleton server (name→Binder directory). */
        private val serverInstance = Server()

        /** The bridge server side, shared by every bind in this process. */
        fun server(): IBinder = serverInstance

        /** Wraps a remote binder as a typed bridge. */
        fun wrap(binder: IBinder?): PluginProcessBridge? =
            binder?.let { PluginProcessBridge(it) }

        /**
         * In-process direct access to the singleton directory — for code
         * already running in the isolated process, so it registers/queries
         * without a self-transact round-trip.
         */
        val local: LocalDirectory get() = LocalDirectory

        /** Direct (no Binder) access to the singleton directory. */
        object LocalDirectory {
            fun register(name: String, token: IBinder) = serverInstance.put(name, token)
            fun unregister(name: String) = serverInstance.remove(name)
            fun resolve(name: String): IBinder? = serverInstance.get(name)
            fun names(): List<String> = serverInstance.keys().toList()
        }

        private class Server : Binder() {
            private val services = ConcurrentHashMap<String, IBinder>()

            fun put(name: String, token: IBinder) {
                services[name] = token
            }

            fun remove(name: String) {
                services.remove(name)
            }

            fun get(name: String): IBinder? = services[name]

            fun keys(): MutableSet<String> = services.keys

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                when (code) {
                    TX_REGISTER -> {
                        data.enforceInterface(DESCRIPTOR)
                        val name = data.readString() ?: ""
                        val token = data.readStrongBinder()
                        put(name, token)
                        reply?.writeNoException()
                        return true
                    }
                    TX_UNREGISTER -> {
                        data.enforceInterface(DESCRIPTOR)
                        data.readString()?.let { remove(it) }
                        reply?.writeNoException()
                        return true
                    }
                    TX_RESOLVE -> {
                        data.enforceInterface(DESCRIPTOR)
                        val name = data.readString()
                        reply?.writeNoException()
                        reply?.writeStrongBinder(name?.let { get(it) })
                        return true
                    }
                    TX_NAMES -> {
                        data.enforceInterface(DESCRIPTOR)
                        reply?.writeNoException()
                        reply?.writeStringList(keys().toList())
                        return true
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
            }
        }
    }
}
