package com.lhzkml.jasmine.core.plugin

/**
 * Marks a sensitive framework API method for charter gating. The
 * `core-plugin-ksp` processor generates a `<Interface>Gated` wrapper whose
 * suspend methods first pass [PluginHost.checkApi] and only then delegate.
 *
 * @param rule access rule evaluated by the charter.
 * @param hardFail true denies outright; false routes failures to the
 *   authorization flow (session-cached per caller + permission key).
 * @param targetParam name of a String parameter carrying the target plugin
 *   id, used by [ApiRule.SelfOrHost]; empty for rules without a target.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class GatedApi(
    val rule: ApiRule = ApiRule.Host,
    val hardFail: Boolean = false,
    val targetParam: String = "",
)
