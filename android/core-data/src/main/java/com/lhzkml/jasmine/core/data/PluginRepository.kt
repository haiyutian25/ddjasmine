package com.lhzkml.jasmine.core.data

import android.content.Context
import com.lhzkml.jasmine.rust.FfiPatchLayerInput
import com.lhzkml.jasmine.rust.composeYamlLayers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** One composed plugin row: the unit the plugin list renders and toggles. */
data class PluginRow(
    /** Patch-targeting identity (the row id). */
    val id: String,
    /** Display name. */
    val name: String,
    /** Whether the row groups nested plugins. */
    val group: Boolean,
    /** Whether the composed row is disabled. */
    val disabled: Boolean,
)

/**
 * The plugin profile: a base bundle layer plus a persisted user overlay,
 * composed through the Rust `compose` crate. All methods block on JNI and
 * must run off the main thread.
 */
interface PluginRepository {
    /** Composed rows, in insertion order. */
    fun rows(): List<PluginRow>

    /** Warnings the composer emitted (skipped patches, unknown keys). */
    fun warnings(): List<String>

    /** Enables or disables one plugin by id, persisting the overlay. */
    fun setDisabled(id: String, disabled: Boolean)
}

@Singleton
class DefaultPluginRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : PluginRepository {

    private val overlaysFile: File
        get() = File(context.filesDir, "profile").apply { mkdirs() }.resolve("overlays.json")

    private fun loadOverlays(): List<PatchLayerSpec> = runCatching {
        if (!overlaysFile.exists()) return emptyList()
        val array = JSONArray(overlaysFile.readText())
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            PatchLayerSpec(label = obj.getString("label"), yaml = obj.getString("yaml"))
        }
    }.getOrDefault(emptyList())

    private fun saveOverlays(overlays: List<PatchLayerSpec>) {
        val array = JSONArray()
        overlays.forEach { layer ->
            array.put(JSONObject().put("label", layer.label).put("yaml", layer.yaml))
        }
        overlaysFile.writeText(array.toString())
    }

    private fun compose(layers: List<PatchLayerSpec>) =
        composeYamlLayers(layers.map { FfiPatchLayerInput(label = it.label, yaml = it.yaml) })

    override fun rows(): List<PluginRow> {
        val layers = listOf(BASE_LAYER) + loadOverlays()
        return compose(layers).rows.map { row ->
            PluginRow(
                id = row.id,
                name = row.name,
                group = row.group,
                disabled = row.disabled == true,
            )
        }
    }

    override fun warnings(): List<String> {
        val layers = listOf(BASE_LAYER) + loadOverlays()
        return compose(layers).warnings
    }

    override fun setDisabled(id: String, disabled: Boolean) {
        saveOverlays(PatchOverlay.applyToggle(loadOverlays(), id, disabled))
    }

    private companion object {
        /**
         * The trimmed first-plugins bundle (upstream base bundle's 80 rows
         * cut to the Android shortlist). Provider connections are host settings,
         * not a preinstalled vendor row; no LLM vendor is seeded here.
         */
        val BASE_LAYER = PatchLayerSpec(
            label = "bundle-base",
            yaml = """
                - insert:
                  - id: tool-bash
                    name: tool-bash
                  - id: tool-fs
                    name: tool-fs
                  - id: web-search
                    name: web-search
                  - id: skill
                    name: skill
                  - id: approval
                    name: approval
                  - id: session-title
                    name: session-title
            """.trimIndent(),
        )
    }
}
