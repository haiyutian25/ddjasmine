package com.lhzkml.jasmine.core.data.di

import com.lhzkml.jasmine.core.agent.AgentLoop
import com.lhzkml.jasmine.core.agent.LlmService
import com.lhzkml.jasmine.core.agent.LlmServiceKey
import com.lhzkml.jasmine.core.agent.SessionStore
import com.lhzkml.jasmine.core.data.ConfigurableLlmService
import com.lhzkml.jasmine.core.data.KernelHolder
import com.lhzkml.jasmine.core.data.RustSessionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Agent wiring: the loop over the process kernel, the session store over the
 * Rust spine, and the user-configured provider seam. No provider endpoint or
 * model is built in; [ConfigurableLlmService] resolves persisted settings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {

    @Binds
    @Singleton
    abstract fun bindsSessionStore(store: RustSessionStore): SessionStore

    @Binds
    @Singleton
    abstract fun bindsLlmService(service: ConfigurableLlmService): LlmService

    companion object {

        @Provides
        @Singleton
        fun agentLoop(holder: KernelHolder, llmService: LlmService): AgentLoop {
            // AgentLoop resolves providers through the kernel registry, not
            // through Hilt directly. Register the Hilt-configured provider
            // exactly once when the singleton loop is created.
            if (holder.kernel.registry.get(LlmServiceKey) == null) {
                holder.kernel.registry.provide(LlmServiceKey, llmService)
            }
            return AgentLoop(holder.kernel)
        }
    }
}
