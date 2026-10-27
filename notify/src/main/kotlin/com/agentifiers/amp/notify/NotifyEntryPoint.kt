package com.agentifiers.amp.notify

import androidx.datastore.core.DataStore
import com.agentifiers.amp.store.AccountDatabases
import com.agentifiers.amp.store.AppSettingsProto
import com.agentifiers.amp.sync.api.BackgroundAccessProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The only way :notify reaches the engine. Services, receivers and workers read it with
 * `EntryPointAccessors.fromApplication(context, NotifyEntryPoint::class.java)`, so they get BackgroundAccess
 * and the ledger database, never a viewer lease.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifyEntryPoint {
    fun background(): BackgroundAccessProvider
    fun databases(): AccountDatabases
    fun appSettings(): DataStore<AppSettingsProto>
}
