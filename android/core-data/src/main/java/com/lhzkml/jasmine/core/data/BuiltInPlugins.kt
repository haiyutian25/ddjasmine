package com.lhzkml.jasmine.core.data

import com.lhzkml.jasmine.core.kernel.Context
import com.lhzkml.jasmine.core.kernel.JasminePlugin
import com.lhzkml.jasmine.core.kernel.PluginDefinition
import com.lhzkml.jasmine.core.kernel.PluginSpec
import com.lhzkml.jasmine.core.kernel.ServiceKey

/** Session titling service, provided by the session-title plugin. */
val TitleService = ServiceKey<String>("session-title/service")

/**
 * The first real plugins, indexed at compile time by core-kernel-ksp into
 * `GeneratedPluginIndex`. Each exercises a slice of the kernel contract:
 * service provisioning (registration effects) and dependency-driven
 * activation.
 */
@JasminePlugin(id = "session-title")
class SessionTitlePlugin : PluginDefinition {
    override val id: String = "session-title"

    override fun spec(): PluginSpec = PluginSpec(
        name = id,
        dependencies = emptyList(),
    ) { ctx: Context ->
        ctx.provide(TitleService, "jasmine-title")
    }
}

/** Consumes the titling service: mounts only once it is available. */
@JasminePlugin(id = "skill")
class SkillPlugin : PluginDefinition {
    override val id: String = "skill"

    override fun spec(): PluginSpec = PluginSpec(
        name = id,
        dependencies = listOf(TitleService),
    ) { ctx: Context ->
        ctx.await(TitleService)
        ctx.effect { { /* skill unload */ } }
    }
}
