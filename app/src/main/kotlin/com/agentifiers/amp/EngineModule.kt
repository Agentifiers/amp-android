package com.agentifiers.amp

import android.content.Context
import com.agentifiers.amp.sync.api.AmpEngines
import com.agentifiers.amp.sync.api.BackgroundAccessProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Binds the engine parts AmpApplication built. LeaseIssuer is never bound, so injection cannot reach it. */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    fun engines(@ApplicationContext context: Context): AmpEngines = (context as AmpApplication).parts.engines

    @Provides
    fun background(@ApplicationContext context: Context): BackgroundAccessProvider = (context as AmpApplication).parts.background
}
