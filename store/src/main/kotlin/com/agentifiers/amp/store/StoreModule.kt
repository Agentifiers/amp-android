package com.agentifiers.amp.store

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StoreModule {

    @Provides
    @Singleton
    fun accountDatabases(@ApplicationContext context: Context): AccountDatabases = RoomAccountDatabases(context)

    @Provides
    @Singleton
    fun appPreferences(@ApplicationContext context: Context): AppPreferences = DataStoreAppPreferences(context)

    @Provides
    @Singleton
    fun appSettings(@ApplicationContext context: Context): DataStore<AppSettingsProto> = appSettingsDataStore(context)
}
