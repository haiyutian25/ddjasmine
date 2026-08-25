package com.lhzkml.jasmine.core.plugin.process

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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

    /**
     * 为 [instanceId] 分配一个隔离服务代理类名（池权威在隔离进程，宿主经此
     * 同步分配；返回 null 表示本槽池耗尽）。
     */
    fun acquireServiceSlot(instanceId: String): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(instanceId)
            handle.transact(TX_ACQUIRE_SLOT, data, reply, 0)
            reply.readException()
            reply.readString()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 归还一个隔离服务槽位（宿主主动回滚 / 停止时调用）。 */
    fun releaseServiceSlot(instanceId: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(instanceId)
            handle.transact(TX_RELEASE_SLOT, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** 查询 instanceId 当前占用的代理类名，未占用返回 null。 */
    fun serviceProxyClass(instanceId: String): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(instanceId)
            handle.transact(TX_PROXY_CLASS, data, reply, 0)
            reply.readException()
            reply.readString()
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
        private const val TX_ACQUIRE_SLOT = 5
        private const val TX_RELEASE_SLOT = 6
        private const val TX_PROXY_CLASS = 7

        /**
         * 宿主目录在隔离进程目录里的保留名：宿主在 bind 各槽 bridge 时把自己的
         * server binder 注册到隔离进程，隔离进程据此解析宿主发布的远程服务
         * （RemoteServices 双向：隔离发布→宿主消费，宿主发布→隔离消费）。
         */
        const val HOST_DIRECTORY_KEY = "__jasmine_host_directory"

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

        /**
         * 隔离服务池的进程内直接访问（隔离进程侧 release 用，避免自 transact）。
         * 池权威在本进程的 [Server]，宿主经 bridge 的 acquireServiceSlot 等方法
         * 同步调用。
         */
        val servicePool: ServicePoolDirectory get() = ServicePoolDirectory

        object ServicePoolDirectory {
            fun acquire(instanceId: String): String? = serverInstance.acquireServiceSlotLocal(instanceId)
            fun release(instanceId: String) = serverInstance.releaseServiceSlotLocal(instanceId)
            fun proxyClass(instanceId: String): String? = serverInstance.serviceProxyClassLocal(instanceId)
        }

        /** 种子本进程（=本隔离槽）的隔离服务代理池。 */
        fun seedServicePool(classNames: List<String>) = serverInstance.seedServicePool(classNames)

        private class Server : Binder() {
            private val services = ConcurrentHashMap<String, IBinder>()

            // 隔离服务池（本进程=本隔离槽）：instanceId → 代理类名，权威在此。
            private val poolAvailable = ConcurrentLinkedQueue<String>()
            private val poolActive = ConcurrentHashMap<String, String>()

            fun seedServicePool(classNames: List<String>) {
                poolAvailable.clear()
                poolAvailable.addAll(classNames)
            }

            // @Synchronized：check(poolActive)→poll→登记 三步必须原子。两条并发
            // 入口（宿主经 Binder 的 TX_ACQUIRE_SLOT、隔离进程内本地 acquire）
            // 都落在同一 serverInstance 上，此前非原子会让同 instanceId 双方各
            // poll 走一个代理类、后写覆盖前者 → 先分配的代理类永久泄漏。
            @Synchronized
            fun acquireServiceSlotLocal(instanceId: String): String? {
                poolActive[instanceId]?.let { return it }
                return poolAvailable.poll()?.also { poolActive[instanceId] = it }
            }

            fun releaseServiceSlotLocal(instanceId: String) {
                poolActive.remove(instanceId)?.let { poolAvailable.offer(it) }
            }

            fun serviceProxyClassLocal(instanceId: String): String? = poolActive[instanceId]

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
                        val name = data.readString()
                        val token = data.readStrongBinder()
                        // 服务端不信任入站 Parcel：null binder 会让 ConcurrentHashMap
                        // 抛 NPE、空名会污染目录，均直接拒绝。
                        if (!name.isNullOrEmpty() && token != null) {
                            put(name, token)
                        }
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
                    TX_ACQUIRE_SLOT -> {
                        data.enforceInterface(DESCRIPTOR)
                        val id = data.readString() ?: ""
                        reply?.writeNoException()
                        reply?.writeString(acquireServiceSlotLocal(id))
                        return true
                    }
                    TX_RELEASE_SLOT -> {
                        data.enforceInterface(DESCRIPTOR)
                        data.readString()?.let { releaseServiceSlotLocal(it) }
                        reply?.writeNoException()
                        return true
                    }
                    TX_PROXY_CLASS -> {
                        data.enforceInterface(DESCRIPTOR)
                        val id = data.readString() ?: ""
                        reply?.writeNoException()
                        reply?.writeString(serviceProxyClassLocal(id))
                        return true
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
            }
        }
    }
}
