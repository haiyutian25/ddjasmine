package com.lhzkml.jasmine.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lhzkml.jasmine.feature.plugin.navigation.PluginContent
import com.lhzkml.jasmine.feature.plugin.navigation.PluginEntryProvider
import com.lhzkml.jasmine.feature.plugin.navigation.PluginList
import com.lhzkml.jasmine.feature.session.navigation.Chat
import com.lhzkml.jasmine.feature.session.navigation.ProviderEdit
import com.lhzkml.jasmine.feature.session.navigation.ProviderList
import com.lhzkml.jasmine.feature.session.navigation.Settings
import com.lhzkml.jasmine.feature.session.ui.ChatScreen

/**
 * App navigation over Navigation3: chat is the root; plugins, settings and
 * the provider pages are pushed destinations with real back semantics.
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
                    onOpenProviderSettings = { backStack.add(ProviderList) },
                    onOpenPluginContent = { pluginId ->
                        backStack.add(PluginContent(pluginId = pluginId))
                    },
                )
            }
            entry<ProviderList> {
                ProviderListScreen(
                    onOpenProvider = { id -> backStack.add(ProviderEdit(providerId = id)) },
                    onCreateProvider = { backStack.add(ProviderEdit(providerId = null)) },
                )
            }
            entry<ProviderEdit> { key ->
                ProviderEditScreen(
                    providerId = key.providerId,
                    onDone = { backStack.removeLastOrNull() },
                )
            }
            PluginEntryProvider(backStack)
        }
    )
}
