package com.lhzkml.jasmine.core.plugin.proxy

/**
 * The manifest-declared service pools. The host pool (twenty slots) runs
 * in-process plugin services; the isolated pool (eight slots) is declared with
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
class HostService11 : HostService()
class HostService12 : HostService()
class HostService13 : HostService()
class HostService14 : HostService()
class HostService15 : HostService()
class HostService16 : HostService()
class HostService17 : HostService()
class HostService18 : HostService()
class HostService19 : HostService()
class HostService20 : HostService()

/** Isolated-process service slots (declared with `process=":plugin_isolated"`). */
class IsolatedHostService1 : HostService()
class IsolatedHostService2 : HostService()
class IsolatedHostService3 : HostService()
class IsolatedHostService4 : HostService()
class IsolatedHostService5 : HostService()
class IsolatedHostService6 : HostService()
class IsolatedHostService7 : HostService()
class IsolatedHostService8 : HostService()

/** The manifest-declared default (in-process) pool, in slot order. */
val defaultServicePool: List<Class<out HostService>> = listOf(
    HostService1::class.java, HostService2::class.java, HostService3::class.java,
    HostService4::class.java, HostService5::class.java, HostService6::class.java,
    HostService7::class.java, HostService8::class.java, HostService9::class.java,
    HostService10::class.java, HostService11::class.java, HostService12::class.java,
    HostService13::class.java, HostService14::class.java, HostService15::class.java,
    HostService16::class.java, HostService17::class.java, HostService18::class.java,
    HostService19::class.java, HostService20::class.java,
)

/**
 * The isolated-process service pools, keyed by 1-based process slot. Each
 * slot gets its own proxies declared with the matching `android:process`
 * (`:plugin_isolated` / `_2` / `_3` / `_4`), so a slot-N plugin's services run
 * in the same process as the plugin itself.
 */
val isolatedServicePool: Map<Int, List<Class<out HostService>>> = mapOf(
    1 to listOf(IsolatedHostService1::class.java, IsolatedHostService2::class.java),
    2 to listOf(IsolatedHostService3::class.java, IsolatedHostService4::class.java),
    3 to listOf(IsolatedHostService5::class.java, IsolatedHostService6::class.java),
    4 to listOf(IsolatedHostService7::class.java, IsolatedHostService8::class.java),
)
