package com.lhzkml.jasmine.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lhzkml.jasmine.feature.plugin.navigation.PluginList
import com.lhzkml.jasmine.feature.plugin.ui.PluginScreen
import com.lhzkml.jasmine.feature.session.navigation.Chat
import com.lhzkml.jasmine.feature.session.navigation.ProviderSettings
import com.lhzkml.jasmine.feature.session.navigation.Settings
import com.lhzkml.jasmine.feature.session.ui.ChatScreen

/**
 * App navigation over Navigation3: chat is the root; plugins and settings
 * are pushed destinations with real back semantics. No hand-rolled tab
 * switching — the library the template ships with does the work.
 */
@Composable
fun JasmineNavigation() {

    val backStack = rememberNavBackStack(Chat)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<Chat> {
                ChatScreen(
                    onOpenSettings = { backStack.add(Settings) },
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onOpenPlugins = { backStack.add(PluginList) },
                    onOpenProviderSettings = { backStack.add(ProviderSettings) },
                )
            }
            entry<ProviderSettings> {
                ProviderSettingsScreen()
            }
            entry<PluginList> {
                PluginScreen()
            }
        }
    )
}
