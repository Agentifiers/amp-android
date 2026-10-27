@file:OptIn(ExperimentalSerializationApi::class)

package com.agentifiers.amp.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.model.LockAfter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** `app-settings.pb`: settings for the whole app rather than one account. */
@Serializable
data class AppSettingsProto(
    /** The [LockAfter] name while app lock is on, null while it is off. */
    @ProtoNumber(1) val appLockAfter: String? = null,
    /** Settings > Notifications > "Notify even when a thread is open elsewhere". A file without the field reads as on. */
    @ProtoNumber(2) val notifyWhenOpenElsewhere: Boolean = true,
) {
    /** A name this build does not know still means on, so an older build never drops a lock a newer one set. */
    val appLock: AppLock
        get() = appLockAfter?.let { name -> AppLock.On(LockAfter.entries.firstOrNull { it.name == name } ?: LockAfter.FiveMinutes) } ?: AppLock.Off

    fun withAppLock(lock: AppLock): AppSettingsProto = copy(appLockAfter = (lock as? AppLock.On)?.after?.name)
}

internal val appSettingsSerializer = ProtoFileSerializer(AppSettingsProto.serializer(), AppSettingsProto())

/** One instance per file, like [AppPreferences]; only [StoreModule] calls this. */
internal fun appSettingsDataStore(context: Context): DataStore<AppSettingsProto> = DataStoreFactory.create(
    serializer = appSettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { appSettingsSerializer.defaultValue },
    produceFile = { context.dataStoreFile("app-settings.pb") },
)
