package com.lhzkml.jasmine.core.plugin

/**
 * Structured runtime events the host can subscribe to for observability
 * (logging, metrics, crash aggregation, isolation tracking). Every lifecycle
 * milestone the framework reaches is emitted here; the host decides what to
 * record.
 */
sealed class PluginEvent {
    abstract val pluginId: String

    /** A plugin was installed or updated. */
    data class Installed(override val pluginId: String) : PluginEvent()

    /** A plugin was uninstalled. */
    data class Uninstalled(override val pluginId: String) : PluginEvent()

    /** A plugin loaded into the current process. */
    data class Loaded(override val pluginId: String) : PluginEvent()

    /** A plugin unloaded from the current process. */
    data class Unloaded(override val pluginId: String) : PluginEvent()

    /** A plugin was moved into the isolated process. */
    data class Isolated(override val pluginId: String) : PluginEvent()

    /** A plugin's isolation was released. */
    data class IsolationReleased(override val pluginId: String) : PluginEvent()

    /** A crash was classified against a plugin. */
    data class Crash(
        override val pluginId: String,
        val kind: String,
        val blameAttributed: Boolean,
    ) : PluginEvent()

    /** A load/unload/install failure surfaced to the host. */
    data class Failure(
        override val pluginId: String,
        val phase: String,
        val error: Throwable,
    ) : PluginEvent()
}

/** Observer of [PluginEvent]s; register via [PluginHost]. */
fun interface PluginEventListener {
    fun onEvent(event: PluginEvent)
}
