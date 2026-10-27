@file:OptIn(ExperimentalSerializationApi::class)

package com.agentifiers.amp.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.InputStream
import java.io.OutputStream

/** `roster.pb`: every signed-in account and the last one used. Secrets live in :sync's TokenVault, never here. */
@Serializable
data class RosterProto(
    @ProtoNumber(1) val accounts: List<AccountEntry> = emptyList(),
    @ProtoNumber(2) val lastUsedId: String? = null,
)

@Serializable
data class AccountEntry(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val email: String,
    @ProtoNumber(3) val baseUrl: String,
    @ProtoNumber(4) val workspaceJson: String,
    @ProtoNumber(5) val pushInstance: String,
    @ProtoNumber(6) val actorsBaseUrl: String = "",
    @ProtoNumber(7) val userId: String = "",
    /** `OAuth` or `AccessToken`, the name of `CredentialKind`. */
    @ProtoNumber(8) val credentialKind: String = "",
    /** Amp rejected the stored credential; the account stays listed until the user signs in again or out. */
    @ProtoNumber(9) val needsSignIn: Boolean = false,
)

/** `workspaces.pb`: the saved arrangement of each window, so the cover, inner display and each DeX window restore their own. */
@Serializable
data class WorkspacesProto(@ProtoNumber(1) val windows: List<WorkspaceEntry> = emptyList())

/**
 * [windowKey] is the task id plus the window form (cover or inner). Each window carries its own account.
 * [stacksJson] maps each column role to the routes pushed over its base pane; [thread] and the tab keys rebuild
 * the base panes.
 */
@Serializable
data class WorkspaceEntry(
    @ProtoNumber(1) val windowKey: String,
    @ProtoNumber(2) val accountId: String,
    @ProtoNumber(3) val arrangement: String,
    @ProtoNumber(4) val stacksJson: String,
    @ProtoNumber(5) val dividerOffsetsDp: List<Float> = emptyList(),
    @ProtoNumber(6) val restore: String? = null,
    @ProtoNumber(7) val focus: String = "",
    @ProtoNumber(8) val thread: String? = null,
    @ProtoNumber(9) val machineTab: String = "",
    @ProtoNumber(10) val secondTab: String = "",
)

/** One DataStore instance per file. Two instances on one file throw, so only [StoreModule] creates this. */
interface AppPreferences {
    val roster: DataStore<RosterProto>
    val workspaces: DataStore<WorkspacesProto>
}

internal class ProtoFileSerializer<T>(private val serializer: KSerializer<T>, override val defaultValue: T) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T =
        try {
            ProtoBuf.decodeFromByteArray(serializer, input.readBytes())
        } catch (e: SerializationException) {
            throw CorruptionException("unreadable ${serializer.descriptor.serialName}", e)
        }

    override suspend fun writeTo(t: T, output: OutputStream) = output.write(ProtoBuf.encodeToByteArray(serializer, t))
}

internal val rosterSerializer = ProtoFileSerializer(RosterProto.serializer(), RosterProto())
internal val workspacesSerializer = ProtoFileSerializer(WorkspacesProto.serializer(), WorkspacesProto())

internal class DataStoreAppPreferences(context: Context) : AppPreferences {
    override val roster: DataStore<RosterProto> = DataStoreFactory.create(
        serializer = rosterSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { rosterSerializer.defaultValue },
        produceFile = { context.dataStoreFile("roster.pb") },
    )
    override val workspaces: DataStore<WorkspacesProto> = DataStoreFactory.create(
        serializer = workspacesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { workspacesSerializer.defaultValue },
        produceFile = { context.dataStoreFile("workspaces.pb") },
    )
}
