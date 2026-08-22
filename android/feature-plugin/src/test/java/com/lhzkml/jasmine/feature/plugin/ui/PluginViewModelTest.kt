package com.lhzkml.jasmine.feature.plugin.ui

import com.lhzkml.jasmine.core.data.KernelHolder
import com.lhzkml.jasmine.core.data.PluginRepository
import com.lhzkml.jasmine.core.data.PluginRow
import com.lhzkml.jasmine.core.data.PluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PluginViewModelTest {

    private class FakePluginRepository : PluginRepository {
        var rowsResult: List<PluginRow> = emptyList()
        var warningsResult: List<String> = emptyList()
        val toggles = mutableListOf<Pair<String, Boolean>>()

        override fun rows(): List<PluginRow> = rowsResult
        override fun warnings(): List<String> = warningsResult
        override fun setDisabled(id: String, disabled: Boolean) {
            toggles.add(id to disabled)
        }
    }

    @Test
    fun `refresh exposes the composed rows and warnings`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repo = FakePluginRepository().apply {
                rowsResult = listOf(
                    PluginRow(id = "custom-provider", name = "custom-provider", group = false, disabled = false),
                    PluginRow(id = "tool-bash", name = "tool-bash", group = false, disabled = true),
                )
                warningsResult = listOf("patch target 'ghost' not found; skipped")
            }
            val viewModel = PluginViewModel(repo, PluginRuntime(KernelHolder(), emptyMap()), dispatcher)
            viewModel.refresh()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(2, state.rows.size)
            assertEquals(true, state.rows[1].disabled)
            assertEquals(1, state.warnings.size)
            assertEquals(false, state.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `setDisabled persists the toggle and refreshes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repo = FakePluginRepository().apply {
                rowsResult = listOf(PluginRow("tool-bash", "tool-bash", false, false))
            }
            val viewModel = PluginViewModel(repo, PluginRuntime(KernelHolder(), emptyMap()), dispatcher)
            viewModel.setDisabled("tool-bash", disabled = true)
            advanceUntilIdle()
            assertEquals(listOf("tool-bash" to true), repo.toggles)
            assertEquals(1, viewModel.uiState.value.rows.size)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
