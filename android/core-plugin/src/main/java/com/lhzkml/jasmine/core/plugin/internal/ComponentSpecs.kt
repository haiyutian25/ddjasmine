package com.lhzkml.jasmine.core.plugin.internal

import com.lhzkml.jasmine.core.plugin.rust.FfiIntentFilter
import com.lhzkml.jasmine.core.plugin.rust.FfiProviderSpec
import com.lhzkml.jasmine.core.plugin.rust.FfiStaticReceiver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted component shapes. The ledger carries them as opaque JSON (it
 * never interprets them); the proxy layer decodes them at load to register
 * dispatch routes with the Rust core.
 */
@Serializable
internal data class IntentFilterSpec(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
)

@Serializable
internal data class ReceiverSpec(
    val className: String,
    val enabled: Boolean = true,
    val exported: Boolean = false,
    val intentFilters: List<IntentFilterSpec> = emptyList(),
)

@Serializable
internal data class ProviderMetaSpec(
    val name: String,
    val value: String? = null,
    val resource: Int? = null,
)

@Serializable
internal data class ProviderSpec(
    val className: String,
    val authorities: List<String> = emptyList(),
    val enabled: Boolean = true,
    val exported: Boolean = false,
    val metaData: List<ProviderMetaSpec> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

internal fun List<ReceiverSpec>.receiversToJsonOrNull(): String? =
    if (isEmpty()) null else json.encodeToString(this)

internal fun List<ProviderSpec>.providersToJsonOrNull(): String? =
    if (isEmpty()) null else json.encodeToString(this)

internal fun String?.receiversFromJson(): List<ReceiverSpec> =
    this?.let { runCatching { json.decodeFromString<List<ReceiverSpec>>(it) }.getOrNull() }
        ?: emptyList()

internal fun String?.providersFromJson(): List<ProviderSpec> =
    this?.let { runCatching { json.decodeFromString<List<ProviderSpec>>(it) }.getOrNull() }
        ?: emptyList()

internal fun ReceiverSpec.toFfi(): FfiStaticReceiver = FfiStaticReceiver(
    className = className,
    enabled = enabled,
    exported = exported,
    intentFilters = intentFilters.map {
        FfiIntentFilter(actions = it.actions, categories = it.categories, schemes = it.schemes)
    },
)

internal fun ProviderSpec.toFfi(): FfiProviderSpec = FfiProviderSpec(
    className = className,
    authorities = authorities,
    enabled = enabled,
    exported = exported,
)
