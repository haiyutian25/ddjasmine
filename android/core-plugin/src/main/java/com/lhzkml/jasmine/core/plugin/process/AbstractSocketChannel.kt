package com.lhzkml.jasmine.core.plugin.process

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Abstract unix-socket frame channel — lightweight same-UID IPC between the
 * isolated plugin process and the host. Simpler than a Binder bridge for RPC
 * that only moves byte frames (no parcel / AIDL ceremony). Mirrors OpenMinis's
 * `native_offload` transport, but as a general-purpose request/response
 * channel instead of being tied to execve interception.
 *
 * Frame format (big-endian): `[u32 payload_length][payload bytes]`.
 *
 * Transport is request/response over short-lived connections: the client
 * connects, writes one frame, reads one frame, closes. This keeps the server
 * trivially concurrent (one thread per connection) with no session state.
 */
object AbstractSocketChannel {

    /** Max payload size (defensive bound, mirrors OpenMinis's string caps). */
    const val MAX_FRAME_BYTES: Int = 1 shl 20 // 1 MiB

    /** 读超时：对端连上后不发数据/慢发，不能无限钉死宿主线程。 */
    private const val SO_TIMEOUT_MS = 10_000

    /** 服务端并发连接上限：恶意/故障插件不能无上限地吃宿主线程。 */
    private const val MAX_CONCURRENT_CONNECTIONS = 32

    /** A request/response handler: consumes a request frame, returns a response frame. */
    fun interface Handler {
        fun handle(request: ByteArray): ByteArray
    }

    /**
     * Server side: binds an abstract socket (with bounded retry for the
     * EADDRINUSE that follows an OOM-killed predecessor) and dispatches each
     * accepted connection to [handler] on a worker thread.
     */
    class Server(private val name: String) {
        private val closed = AtomicBoolean(false)
        private var serverSocket: LocalServerSocket? = null
        private val activeConnections = AtomicInteger(0)

        /** Binds and starts the accept loop. Throws on failure after retries. */
        fun start(handler: Handler) {
            val socket = bindWithRetry()
                ?: throw IOException("failed to bind abstract socket '$name' after retries")
            serverSocket = socket
            thread(name = "abstract-socket-$name", isDaemon = true) { acceptLoop(socket, handler) }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { serverSocket?.close() }
            serverSocket = null
        }

        private fun bindWithRetry(): LocalServerSocket? {
            // Backoff ~1.55s total — the kernel frees an abstract socket only
            // after the owning process is fully reaped, so an immediate rebind
            // after a process kill can transiently fail with EADDRINUSE.
            val delays = longArrayOf(0L, 50L, 100L, 200L, 400L, 800L)
            for (delay in delays) {
                if (delay > 0) Thread.sleep(delay)
                try {
                    return LocalServerSocket(name)
                } catch (_: IOException) {
                    // retry
                }
            }
            return null
        }

        private fun acceptLoop(socket: LocalServerSocket, handler: Handler) {
            val myUid = Process.myUid()
            while (!closed.get()) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                } catch (_: IllegalStateException) {
                    break
                }
                // 对端鉴权：abstract socket 无内置鉴权，校验对端 UID==本应用，
                // 拒绝其它应用进程连入执行命名能力（绕过 requireCapability）。
                val peerUid = runCatching { client.peerCredentials.uid }.getOrDefault(-1)
                if (peerUid != myUid) {
                    runCatching { client.close() }
                    continue
                }
                // 并发上限：超限直接拒绝，避免恶意插件开大量连接拖垮宿主。
                if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                    runCatching { client.close() }
                    continue
                }
                activeConnections.incrementAndGet()
                thread(name = "abstract-socket-$name-worker", isDaemon = true) {
                    try {
                        runCatching {
                            client.use { c ->
                                c.setSoTimeout(SO_TIMEOUT_MS)
                                val input = DataInputStream(c.inputStream)
                                val output = DataOutputStream(c.outputStream)
                                val request = readFrame(input) ?: return@use
                                val response = handler.handle(request)
                                writeFrame(output, response)
                            }
                        }
                    } finally {
                        activeConnections.decrementAndGet()
                    }
                }
            }
        }
    }

    /** Client side: connects, sends one request frame, reads one response frame. */
    class Client(private val name: String) {
        fun request(payload: ByteArray): ByteArray {
            val socket = LocalSocket()
            return try {
                socket.connect(
                    LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT),
                )
                // 读响应超时：服务端 handler 挂死时调用线程不能永久阻塞。
                socket.setSoTimeout(SO_TIMEOUT_MS)
                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)
                writeFrame(output, payload)
                readFrame(input)
                    ?: throw IOException("empty response from '$name'")
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /** Reads a length-prefixed frame, or null on EOF/oversize. */
    fun readFrame(input: DataInputStream): ByteArray? {
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) return null
        val payload = ByteArray(length)
        input.readFully(payload)
        return payload
    }

    /** Writes a length-prefixed frame. */
    fun writeFrame(output: DataOutputStream, payload: ByteArray) {
        // 与 readFrame 的 MAX_FRAME_BYTES 上限对称：超限响应在此明确失败，
        // 而不是写出去后被对端 readFrame 拒绝、报出误导的 "empty response"。
        if (payload.size > MAX_FRAME_BYTES) {
            throw IOException("frame too large: ${payload.size} > $MAX_FRAME_BYTES")
        }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
