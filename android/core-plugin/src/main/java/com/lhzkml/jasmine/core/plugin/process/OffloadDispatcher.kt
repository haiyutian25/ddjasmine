package com.lhzkml.jasmine.core.plugin.process

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Named-capability dispatch — the framework-level counterpart of OpenMinis's
 * `native_offload`: the host registers handlers under command names, and a
 * caller (host or isolated plugin process) invokes one by name with plain
 * argv/env, getting back an exit code + output. The command name is `argv[0]`,
 * exactly like a shell command.
 *
 * Dispatch is process-aware: a locally-registered handler runs in-process; an
 * unregistered name routes over an [AbstractSocketChannel] to the process that
 * owns it. This gives plugins a lightweight "call a host capability by name"
 * channel that needs no JNI binding and no Binder interface.
 *
 * Wire protocol (JSON over length-prefixed frames):
 *   request  = {"argv":[...],"env":{...}}
 *   response = {"exitCode":int,"output":string}
 */

@Serializable
data class OffloadRequest(val argv: List<String>, val env: Map<String, String> = emptyMap())

@Serializable
data class OffloadResult(val exitCode: Int, val output: String)

/** Handles a named offload: argv[0] is the command name (already matched). */
fun interface OffloadHandler {
    fun handle(argv: List<String>, env: Map<String, String>): OffloadResult
}

object OffloadDispatcher {

    /** Abstract socket name the host serves offload requests on. */
    const val SOCKET_NAME = "jasmine-offload"

    private val handlers = ConcurrentHashMap<String, OffloadHandler>()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var server: AbstractSocketChannel.Server? = null

    /** Registers (or replaces) a named handler, in this process. */
    fun register(name: String, handler: OffloadHandler) {
        handlers[name] = handler
    }

    /** Unregisters a named handler. */
    fun unregister(name: String) {
        handlers.remove(name)
    }

    /** Names registered in this process. */
    fun registeredNames(): List<String> = handlers.keys().toList().sorted()

    /**
     * Starts the offload server on [SOCKET_NAME] (host side). Requests routed
     * here resolve the command name against [handlers]. Idempotent.
     */
    @Synchronized
    fun startServer() {
        if (server != null) return
        val s = AbstractSocketChannel.Server(SOCKET_NAME)
        s.start { requestBytes ->
            runCatching { dispatchRequestBytes(requestBytes) }
                .getOrElse { e ->
                    json.encodeToString(
                        OffloadResult.serializer(),
                        OffloadResult(exitCode = 1, output = "offload error: ${e.message}"),
                    ).encodeToByteArray()
                }
        }
        server = s
    }

    /** Stops the offload server, if started. */
    @Synchronized
    fun stopServer() {
        server?.close()
        server = null
    }

    /**
     * Invokes a named capability. Runs the local handler when registered
     * here; otherwise forwards over the abstract socket to the server (the
     * host process). Throws when no handler exists anywhere.
     */
    fun dispatch(name: String, argv: List<String> = emptyList(), env: Map<String, String> = emptyMap()): OffloadResult {
        val fullArgv = listOf(name) + argv
        handlers[name]?.let { return it.handle(fullArgv, env) }
        return dispatchRemote(name, fullArgv, env)
    }

    /** Invokes a handler over the abstract socket (cross-process). */
    fun dispatchRemote(name: String, argv: List<String>, env: Map<String, String>): OffloadResult {
        val requestBytes = json.encodeToString(
            OffloadRequest.serializer(),
            OffloadRequest(argv = argv, env = env),
        ).encodeToByteArray()
        val responseBytes = AbstractSocketChannel.Client(SOCKET_NAME).request(requestBytes)
        return json.decodeFromString(
            OffloadResult.serializer(),
            responseBytes.decodeToString(),
        )
    }

    /** Resolves a request frame to its response frame, on the server side. */
    private fun dispatchRequestBytes(requestBytes: ByteArray): ByteArray {
        val request = json.decodeFromString(OffloadRequest.serializer(), requestBytes.decodeToString())
        val name = request.argv.firstOrNull()
            ?: throw IllegalArgumentException("offload request missing argv[0]")
        val handler = handlers[name]
            ?: throw IllegalArgumentException("no offload handler registered for '$name'")
        val result = handler.handle(request.argv, request.env)
        return json.encodeToString(OffloadResult.serializer(), result).encodeToByteArray()
    }
}
