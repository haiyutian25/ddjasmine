package com.lhzkml.jasmine.core.data

import android.content.Context
import com.lhzkml.jasmine.rust.FfiModelMessage
import com.lhzkml.jasmine.rust.FfiSessionEvent
import com.lhzkml.jasmine.rust.FfiSessionHeader
import com.lhzkml.jasmine.rust.SessionLogHandle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One persisted session log, as shown in the session list. */
data class SessionSummary(
    /** File-stem session identifier (UUID). */
    val id: String,
    /** Header creation time, milliseconds since the epoch. */
    val createdAtMs: Long,
    /** Durable events in the log. */
    val eventCount: Long,
    /** Torn-tail bytes the last open repaired; zero for clean files. */
    val repairedTornTailBytes: Long,
)

/**
 * Session persistence over the Rust data spine. Every operation opens the
 * JSONL log through [SessionLogHandle], does its work, and closes — the
 * repository holds no native handles between calls. Calls block on JNI and
 * must run off the main thread.
 */
interface SessionRepository {
    /** Lists every session log in the store, newest first. */
    fun list(): List<SessionSummary>

    /** Creates a fresh session log and returns its id. */
    fun create(): String

    /** All events of one session, in log order. */
    fun events(sessionId: String): List<FfiSessionEvent>

    /** Appends one arbitrary event; returns the assigned seq. */
    fun appendEvent(sessionId: String, eventType: String, payloadJson: String): Long

    /** Event types in log order (turn numbering, diagnostics). */
    fun eventTypes(sessionId: String): List<String>

    /** The derived model history straight off the Rust projection. */
    fun messages(sessionId: String): List<FfiModelMessage>

    /** Appends one `user/message` event; returns the assigned seq. */
    fun appendUserMessage(sessionId: String, content: String): Long
}

@Singleton
class DefaultSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionRepository {

    private val sessionsDir: File
        get() = File(context.filesDir, "sessions").apply { mkdirs() }

    private fun pathOf(sessionId: String): File = File(sessionsDir, "$sessionId.jsonl")

    private fun <R> withOpenLog(sessionId: String, block: (SessionLogHandle) -> R): R =
        SessionLogHandle.openJsonl(path = pathOf(sessionId).absolutePath).use(block)

    override fun list(): List<SessionSummary> =
        sessionsDir.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
            .orEmpty()
            .map { file ->
                val summary = try {
                    withOpenLog(file.name.removeSuffix(".jsonl")) { log ->
                        SessionSummary(
                            id = file.name.removeSuffix(".jsonl"),
                            createdAtMs = log.header().createdAtMs,
                            eventCount = log.eventCount().toLong(),
                            repairedTornTailBytes = log.repairedTornTailBytes().toLong(),
                        )
                    }
                } catch (t: Throwable) {
                    // Unreadable logs stay visible-but-marked rather than hiding the store.
                    SessionSummary(
                        id = file.name.removeSuffix(".jsonl"),
                        createdAtMs = file.lastModified(),
                        eventCount = -1,
                        repairedTornTailBytes = -1,
                    )
                }
                summary
            }
            .sortedByDescending { it.createdAtMs }

    override fun create(): String {
        val id = UUID.randomUUID().toString()
        SessionLogHandle.createJsonl(
            path = pathOf(id).absolutePath,
            header = FfiSessionHeader(
                formatVersion = 0u,
                sessionId = id,
                createdAtMs = System.currentTimeMillis(),
            ),
        ).use { it.flush() }
        return id
    }

    override fun events(sessionId: String): List<FfiSessionEvent> =
        withOpenLog(sessionId) { log ->
            log.closeInterruptedTurns()
            log.events()
        }

    override fun appendEvent(sessionId: String, eventType: String, payloadJson: String): Long =
        withOpenLog(sessionId) { log ->
            log.append(eventType = eventType, payloadJson = payloadJson).toLong()
        }

    override fun eventTypes(sessionId: String): List<String> =
        withOpenLog(sessionId) { log -> log.events().map { it.eventType } }

    override fun messages(sessionId: String): List<FfiModelMessage> =
        withOpenLog(sessionId) { log -> log.deriveMessages(ULong.MAX_VALUE) }

    override fun appendUserMessage(sessionId: String, content: String): Long =
        appendEvent(sessionId, "user/message", """{"content":${content.toJsonString()}}""")
}

/** Minimal JSON string escaping (the payload crosses as a JSON string). */
private fun String.toJsonString(): String = buildString {
    append('"')
    for (ch in this@toJsonString) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
