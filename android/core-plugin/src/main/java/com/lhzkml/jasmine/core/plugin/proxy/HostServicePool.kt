package com.lhzkml.jasmine.core.plugin.proxy

/**
 * The manifest-declared service pool: ten concrete proxies, registered in
 * this library's own manifest and merged into the host app. Pool capacity
 * is ten concurrent plugin-service instances; raise by adding slots here
 * and in the manifest.
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

/** The manifest-declared default pool, in slot order. */
val defaultServicePool: List<Class<out HostService>> = listOf(
    HostService1::class.java, HostService2::class.java, HostService3::class.java,
    HostService4::class.java, HostService5::class.java, HostService6::class.java,
    HostService7::class.java, HostService8::class.java, HostService9::class.java,
    HostService10::class.java,
)
