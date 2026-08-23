package com.lhzkml.jasmine.core.plugin.process

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
            while (!closed.get()) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                } catch (_: IllegalStateException) {
                    break
                }
                thread(name = "abstract-socket-$name-worker", isDaemon = true) {
                    runCatching {
                        client.use { c ->
                            val input = DataInputStream(c.inputStream)
                            val output = DataOutputStream(c.outputStream)
                            val request = readFrame(input) ?: return@use
                            val response = handler.handle(request)
                            writeFrame(output, response)
                        }
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
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
