package com.lhzkml.jasmine.core.data.di

import com.lhzkml.jasmine.core.kernel.PluginSpec
import com.lhzkml.jasmine.plugins.GeneratedPluginIndex
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the KSP-generated plugin index as the runtime's id→spec map. */
@Module
@InstallIn(SingletonComponent::class)
object PluginIndexModule {

    @Provides
    @Singleton
    fun pluginIndex(): Map<String, PluginSpec> =
        GeneratedPluginIndex.definitions.associate { it.id to it.spec() }
}
