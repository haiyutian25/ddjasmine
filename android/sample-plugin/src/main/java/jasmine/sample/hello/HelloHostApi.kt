package jasmine.sample.hello

import com.lhzkml.jasmine.core.plugin.ApiRule
import com.lhzkml.jasmine.core.plugin.GatedApi

/**
 * Demo for the KSP permission weave: the processor generates
 * `HelloHostApiGated`, whose suspend methods pass the charter before
 * delegating.
 */
interface HelloHostApi {
    @GatedApi(rule = ApiRule.SelfOrHost, targetParam = "targetPluginId")
    fun readSharedNotes(targetPluginId: String): String
}
