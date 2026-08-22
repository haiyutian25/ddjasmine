package com.lhzkml.jasmine.feature.session.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Home destination: the chat page. */
@Serializable
data object Chat : NavKey

/** The settings destination. */
@Serializable
data object Settings : NavKey

/** The model & credentials destination. */
@Serializable
data object ProviderSettings : NavKey
