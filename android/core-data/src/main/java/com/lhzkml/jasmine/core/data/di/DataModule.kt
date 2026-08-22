package com.lhzkml.jasmine.core.data.di

import com.lhzkml.jasmine.core.data.DefaultPluginRepository
import com.lhzkml.jasmine.core.data.DefaultSessionRepository
import com.lhzkml.jasmine.core.data.PluginRepository
import com.lhzkml.jasmine.core.data.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Singleton
    @Binds
    fun bindsPluginRepository(
        pluginRepository: DefaultPluginRepository
    ): PluginRepository

    @Singleton
    @Binds
    fun bindsSessionRepository(
        sessionRepository: DefaultSessionRepository
    ): SessionRepository
}
