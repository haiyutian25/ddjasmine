package com.lhzkml.jasmine.core.data

/**
 * One patch layer: a named YAML document of patch entries (upstream
 * cordis.patch.yml layers). Layers compose in list order; a later layer's
 * set-patch overrides an earlier row's keys.
 */
data class PatchLayerSpec(val label: String, val yaml: String)

/**
 * The user-overlay algebra: enabling or disabling a plugin writes one
 * set-patch entry into the single `home` overlay layer — the last layer, so
 * it always wins. Pure and JVM-testable; the Rust `compose` crate does the
 * actual composition.
 */
object PatchOverlay {

    const val HOME_LAYER = "home"

    private val ID_LINE = Regex("""(?m)^- id: (\S+)""")
    private val DISABLED_LINE = Regex("""(?m)^  disabled: (true|false|null)""")

    /** One home-overlay entry: parsed toggling identity plus its raw text. */
    data class ToggleEntry(val id: String, val disabled: Boolean?, val raw: String)

    /** The set-patch entry that pins [id] to [disabled]. */
    fun overlayEntry(id: String, disabled: Boolean?): String = buildString {
        appendLine("- id: $id")
        append("  disabled: ")
        appendLine(disabled ?: "null")
    }

    /**
     * Parses a home-overlay YAML into its entries. Entries whose `id` line
     * does not parse keep an empty id: they never match toggles and their
     * raw text survives recomposition verbatim.
     */
    fun parseEntries(yaml: String): List<ToggleEntry> =
        yaml.split(Regex("(?m)^- ")).filter { it.isNotBlank() }.map { raw ->
            val entry = "- ${raw.trimEnd()}"
            ToggleEntry(
                id = ID_LINE.find(entry)?.groupValues?.get(1).orEmpty(),
                disabled = DISABLED_LINE.find(entry)?.groupValues?.get(1)
                    ?.let { if (it == "null") null else it.toBoolean() },
                raw = entry,
            )
        }

    /**
     * Applies one toggle: unparseable entries stay verbatim in place, every
     * other id keeps its own `disabled` value, [id]'s entry is replaced
     * with [disabled], and parseable entries are ordered by id. All other
     * layers pass through untouched.
     */
    fun applyToggle(
        layers: List<PatchLayerSpec>,
        id: String,
        disabled: Boolean?,
    ): List<PatchLayerSpec> {
        val home = layers.firstOrNull { it.label == HOME_LAYER }
        val others = layers.filter { it.label != HOME_LAYER }
        val entries = home?.let { parseEntries(it.yaml) }.orEmpty()
        val opaque = entries.filter { it.id.isEmpty() }.map { it.raw }
        val kept = entries.filter { it.id.isNotEmpty() && it.id != id }
            .associate { it.id to it.disabled }.toMutableMap()
        kept[id] = disabled
        val lines = opaque + kept.keys.sorted().map { overlayEntry(it, kept[it]) }
        return if (lines.isEmpty()) others else others + PatchLayerSpec(HOME_LAYER, lines.joinToString("\n"))
    }
}
