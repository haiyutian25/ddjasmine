package com.lhzkml.jasmine.core.plugin.process

import android.os.Binder
import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the hand-written Binder bridge's register/resolve/unregister/names semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginProcessBridgeTest {

    @Test
    fun register_resolve_unregister_roundTrip() {
        val server = PluginProcessBridge.server()
        val bridge = PluginProcessBridge.wrap(server)
        assertNotNull(bridge)

        val token: IBinder = Binder()
        bridge!!.register("svc.a", token)

        val resolved = bridge.resolve("svc.a")
        assertNotNull(resolved)

        bridge.unregister("svc.a")
        assertNull(bridge.resolve("svc.a"))
    }

    @Test
    fun names_reflects_registered_services() {
        val bridge = PluginProcessBridge.wrap(PluginProcessBridge.server())!!
        bridge.register("svc.one", Binder())
        bridge.register("svc.two", Binder())

        val names = bridge.names().toSet()
        assertTrue(names.containsAll(setOf("svc.one", "svc.two")))
    }

    @Test
    fun local_directory_bypasses_binder_transact() {
        val token: IBinder = Binder()
        PluginProcessBridge.local.register("local.key", token)
        assertEquals(token, PluginProcessBridge.local.resolve("local.key"))
        PluginProcessBridge.local.unregister("local.key")
        assertNull(PluginProcessBridge.local.resolve("local.key"))
    }
}
