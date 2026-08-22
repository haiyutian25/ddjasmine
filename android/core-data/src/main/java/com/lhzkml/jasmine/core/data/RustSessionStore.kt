package com.lhzkml.jasmine.core.data

import com.lhzkml.jasmine.core.agent.LlmMessage
import com.lhzkml.jasmine.core.agent.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent loop's session access over the Rust spine: events append
 * verbatim to the JSONL log, history comes straight off the Rust
 * projection. This is the M2 boundary between Kotlin behavior and Rust
 * data — the loop sees [SessionStore], the repository does the JNI.
 */
@Singleton
class RustSessionStore @Inject constructor(
    private val sessionRepository: SessionRepository,
) : SessionStore {

    override fun append(sessionId: String, eventType: String, payloadJson: String): Long =
        sessionRepository.appendEvent(sessionId, eventType, payloadJson)

    override fun eventTypes(sessionId: String): List<String> =
        sessionRepository.eventTypes(sessionId)

    override fun messages(sessionId: String): List<LlmMessage> =
        sessionRepository.messages(sessionId).map { message ->
            LlmMessage(role = message.role, content = message.content)
        }
}
