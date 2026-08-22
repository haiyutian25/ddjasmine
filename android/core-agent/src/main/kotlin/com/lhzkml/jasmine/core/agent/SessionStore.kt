package com.lhzkml.jasmine.core.agent

/**
 * The session-log access an agent loop needs. Implemented over the Rust
 * spine in core-data; interface-only here so the loop stays JVM-testable.
 */
interface SessionStore {

    /** Appends one event; returns its seq. */
    fun append(sessionId: String, eventType: String, payloadJson: String): Long

    /** Every event type in log order (for turn numbering). */
    fun eventTypes(sessionId: String): List<String>

    /** The derived model history (Rust projection). */
    fun messages(sessionId: String): List<LlmMessage>
}
