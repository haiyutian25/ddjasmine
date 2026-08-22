package com.lhzkml.jasmine.feature.plugin.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.lhzkml.jasmine.feature.plugin.ui.PluginScreen

@Composable
fun EntryProviderScope<NavKey>.PluginEntryProvider(backStack: NavBackStack<NavKey>) {
    entry<PluginList> {
        PluginScreen()
    }
}
