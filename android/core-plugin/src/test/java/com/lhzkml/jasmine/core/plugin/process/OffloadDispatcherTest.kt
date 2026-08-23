package com.lhzkml.jasmine.core.plugin.process

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the named-capability dispatcher's local path and wire protocol. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OffloadDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun register_and_dispatch_local() {
        OffloadDispatcher.register("echo") { argv, _ ->
            OffloadResult(exitCode = 0, output = argv.drop(1).joinToString(" "))
        }
        val result = OffloadDispatcher.dispatch("echo", listOf("hello", "world"))
        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.output)
        OffloadDispatcher.unregister("echo")
    }

    @Test
    fun argv0_is_command_name() {
        OffloadDispatcher.register("whoami") { argv, _ ->
            OffloadResult(exitCode = 0, output = argv.first())
        }
        assertEquals("whoami", OffloadDispatcher.dispatch("whoami").output)
        OffloadDispatcher.unregister("whoami")
    }

    @Test
    fun request_response_json_roundTrip() {
        val request = OffloadRequest(
            argv = listOf("calendar", "--today"),
            env = mapOf("SESSION" to "abc"),
        )
        val reqBytes = json.encodeToString(OffloadRequest.serializer(), request).encodeToByteArray()
        val decodedRequest = json.decodeFromString(
            OffloadRequest.serializer(),
            reqBytes.decodeToString(),
        )
        assertEquals(request.argv, decodedRequest.argv)
        assertEquals(request.env, decodedRequest.env)

        val response = OffloadResult(exitCode = 0, output = "2026-08-24")
        val respBytes = json.encodeToString(OffloadResult.serializer(), response).encodeToByteArray()
        val decodedResponse = json.decodeFromString(
            OffloadResult.serializer(),
            respBytes.decodeToString(),
        )
        assertEquals(response, decodedResponse)
    }
}
