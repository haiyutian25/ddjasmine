package com.lhzkml.jasmine.core.plugin.proxy

/**
 * The manifest-declared service pools. The host pool (ten slots) runs
 * in-process plugin services; the isolated pool (four slots) is declared with
 * `android:process=":plugin_isolated"` and runs services of plugins placed in
 * the isolated process. `bindService`/`startService` on an isolated slot
 * crosses the process boundary automatically (AMS passes the plugin's
 * `onBind` binder across), so no extra bridge is needed for service binding.
 */
class HostService1 : HostService()
class HostService2 : HostService()
class HostService3 : HostService()
class HostService4 : HostService()
class HostService5 : HostService()
class HostService6 : HostService()
class HostService7 : HostService()
class HostService8 : HostService()
class HostService9 : HostService()
class HostService10 : HostService()

/** Isolated-process service slots (declared with `process=":plugin_isolated"`). */
class IsolatedHostService1 : HostService()
class IsolatedHostService2 : HostService()
class IsolatedHostService3 : HostService()
class IsolatedHostService4 : HostService()

/** The manifest-declared default (in-process) pool, in slot order. */
val defaultServicePool: List<Class<out HostService>> = listOf(
    HostService1::class.java, HostService2::class.java, HostService3::class.java,
    HostService4::class.java, HostService5::class.java, HostService6::class.java,
    HostService7::class.java, HostService8::class.java, HostService9::class.java,
    HostService10::class.java,
)

/** The manifest-declared isolated-process pool, in slot order. */
val isolatedServicePool: List<Class<out HostService>> = listOf(
    IsolatedHostService1::class.java, IsolatedHostService2::class.java,
    IsolatedHostService3::class.java, IsolatedHostService4::class.java,
)
