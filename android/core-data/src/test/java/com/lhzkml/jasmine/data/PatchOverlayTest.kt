package com.lhzkml.jasmine.data

import com.lhzkml.jasmine.core.data.PatchLayerSpec
import com.lhzkml.jasmine.core.data.PatchOverlay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchOverlayTest {

    @Test
    fun `applyToggle adds an entry to an empty home overlay`() {
        val layers = PatchOverlay.applyToggle(emptyList(), "tool-bash", disabled = true)
        assertEquals(1, layers.size)
        assertEquals(PatchOverlay.HOME_LAYER, layers.single().label)
        assertTrue(layers.single().yaml.contains("- id: tool-bash"))
        assertTrue(layers.single().yaml.contains("disabled: true"))
    }

    @Test
    fun `applyToggle replaces the same id instead of duplicating`() {
        val once = PatchOverlay.applyToggle(emptyList(), "tool-bash", disabled = true)
        val twice = PatchOverlay.applyToggle(once, "tool-bash", disabled = false)
        val entries = PatchOverlay.parseEntries(twice.single().yaml)
        assertEquals(1, entries.count { it.id == "tool-bash" })
        assertEquals(false, entries.single { it.id == "tool-bash" }.disabled)
    }

    @Test
    fun `applyToggle keeps other layers and entries untouched`() {
        val base = PatchLayerSpec("bundle-base", "- insert:\n  - id: llm\n    name: llm\n")
        val toggled = PatchOverlay.applyToggle(listOf(base), "tool-bash", disabled = true)
        assertEquals(listOf("bundle-base", PatchOverlay.HOME_LAYER), toggled.map { it.label })
        assertEquals(base, toggled.first())
    }

    @Test
    fun `toggling two ids keeps home entries ordered and independent`() {
        val one = PatchOverlay.applyToggle(emptyList(), "zzz", disabled = true)
        val two = PatchOverlay.applyToggle(one, "aaa", disabled = false)
        val entries = PatchOverlay.parseEntries(two.single().yaml)
        assertEquals(listOf("aaa", "zzz"), entries.map { it.id })
        assertEquals(false, entries.first().disabled)
        assertEquals(true, entries.last().disabled)
    }

    @Test
    fun `null disabled pins explicit enabled and parses back`() {
        val layers = PatchOverlay.applyToggle(emptyList(), "skill", disabled = null)
        val entry = PatchOverlay.parseEntries(layers.single().yaml).single()
        assertEquals(null, entry.disabled)
    }

    @Test
    fun `entries without a parseable id survive but never match toggles`() {
        val exotic = PatchLayerSpec(
            PatchOverlay.HOME_LAYER,
            "- id: tool-bash\n  disabled: true\n- insert:\n  - id: nested\n",
        )
        val toggled = PatchOverlay.applyToggle(listOf(exotic), "tool-bash", disabled = false)
        val yaml = toggled.single().yaml
        assertTrue(yaml.contains("- insert:"), "unparseable entries are preserved")
        assertEquals(1, PatchOverlay.parseEntries(yaml).count { it.id == "tool-bash" })
    }
}
