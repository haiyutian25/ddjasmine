package com.lhzkml.jasmine.core.kernel

/**
 * Marks a Kotlin class as a plugin the KSP processor indexes. The generated
 * `PluginIndex` instantiates annotated no-arg classes directly (no
 * reflection, R8-safe) and feeds their specs to the host.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class JasminePlugin(val id: String)

/** A plugin implementation's contract: a stable id plus its mount spec. */
interface PluginDefinition {
    /** Must match the composed profile row id and the annotation's id. */
    val id: String

    /** The spec the host mounts when the row is enabled. */
    fun spec(): PluginSpec
}
