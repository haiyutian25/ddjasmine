package com.lhzkml.jasmine.core.plugin.proxy

/**
 * Launch-mode slots for plugin activities. Each variant is a distinct
 * pre-registered proxy activity with its own `launchMode`/`theme`; the
 * runtime routes a plugin activity to the matching slot so real-world
 * requirements (single-task login, transparent dialog, …) don't collide.
 */
enum class PluginLaunchMode {
    /** Default: standard back-stack behavior. */
    Standard,

    /** `launchMode="singleTop"` — reuses the top instance. */
    SingleTop,

    /** `launchMode="singleTask"` — one instance as the task root. */
    SingleTask,

    /** `launchMode="singleInstance"` — its own task, no other activities. */
    SingleInstance,

    /** Transparent/no-title theme — for dialog-style plugin activities. */
    Transparent,
}

/** `singleTop` slot. */
open class HostActivitySingleTop : HostActivity()

/** `singleTask` slot. */
open class HostActivitySingleTask : HostActivity()

/** `singleInstance` slot. */
open class HostActivitySingleInstance : HostActivity()

/** Transparent theme slot. */
open class HostActivityTransparent : HostActivity()

/** Maps a launch mode to its proxy activity class. */
internal fun PluginLaunchMode.proxyClass(): Class<out HostActivity> = when (this) {
    PluginLaunchMode.Standard -> HostActivity::class.java
    PluginLaunchMode.SingleTop -> HostActivitySingleTop::class.java
    PluginLaunchMode.SingleTask -> HostActivitySingleTask::class.java
    PluginLaunchMode.SingleInstance -> HostActivitySingleInstance::class.java
    PluginLaunchMode.Transparent -> HostActivityTransparent::class.java
}
