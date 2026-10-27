package com.agentifiers.amp.sync.internal

import androidx.datastore.core.DataStore
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.store.AccountDatabase
import com.agentifiers.amp.store.AccountDatabases
import com.agentifiers.amp.store.AppPreferences
import com.agentifiers.amp.store.RosterProto
import com.agentifiers.amp.store.WorkspacesProto
import com.agentifiers.amp.sync.api.EngineConfig
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.api.SignInStep
import com.agentifiers.amp.sync.internal.push.PushKeys
import com.agentifiers.amp.sync.internal.push.PushRegistration
import com.agentifiers.amp.sync.internal.push.PushSecrets
import com.agentifiers.amp.sync.internal.vault.SealedFiles
import com.agentifiers.amp.sync.internal.vault.TokenVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Sealed files kept in memory; the vault's locking and compare-and-set run unchanged on top. */
internal class MemorySealedFiles : SealedFiles {
    private val files = ConcurrentHashMap<String, ByteArray>()
    override fun read(name: String): ByteArray? = files[name]
    override fun write(name: String, plaintext: ByteArray) {
        files[name] = plaintext
    }
    override fun destroy(name: String) {
        files.remove(name)
    }
    override fun names(): List<String> = files.keys.toList()
}

internal class MemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val writes = Mutex()
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T = writes.withLock { transform(state.value).also { state.value = it } }
}

internal class MemoryPreferences : AppPreferences {
    override val roster = MemoryDataStore(RosterProto())
    override val workspaces = MemoryDataStore(WorkspacesProto())
}

/** Records which account databases sign-out deleted. An open one has empty tables that engine tests never write. */
internal class DeletedDatabases : AccountDatabases {
    val deleted = mutableListOf<AccountId>()
    override fun forAccount(accountId: AccountId): AccountDatabase =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AccountDatabase::class.java)) { _, method, _ -> emptyTable(method.returnType) } as AccountDatabase
    override suspend fun delete(accountId: AccountId) {
        synchronized(deleted) { deleted += accountId }
    }
}

/** A table with no rows: its observers see an empty list, and any other call fails the test. */
private fun emptyTable(type: Class<*>): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
    if (method.returnType == Flow::class.java) flowOf(emptyList<Any>())
    else throw UnsupportedOperationException("engine tests do not call ${type.simpleName}.${method.name}")
}

/** One account's push registration in memory. Engine tests decrypt no push, so there are no keys. */
internal class MemoryPushSecrets : PushSecrets {
    @Volatile private var registration: PushRegistration? = null
    override fun keys(): PushKeys = throw UnsupportedOperationException("engine tests decrypt no push")
    override fun registration(): PushRegistration? = registration
    override fun saveRegistration(registration: PushRegistration?) {
        this.registration = registration
    }
    override fun wipe() {
        registration = null
    }
}

/** One app process against a fake server: files and preferences survive [restart], like the device's storage. */
internal class EngineHarness(val server: FakeAmpServer) {
    val files = MemorySealedFiles()
    val prefs = MemoryPreferences()
    val databases = DeletedDatabases()
    val imageCache: File = Files.createTempDirectory("images").toFile()
    val vault = TokenVault(files)
    val pushSecrets = ConcurrentHashMap<AccountId, MemoryPushSecrets>()
    val environment = ServerEnvironment(server.ampBaseUrl, server.actorsBaseUrl)
    var engines = start()
        private set

    fun restart(): DefaultEngines = start().also { engines = it }

    private fun start() = DefaultEngines(
        EnginePlatform(
            files,
            imageCache,
            allowLocalHttp = true,
            retireLegacy = {},
            pushSecrets = { pushSecrets.getOrPut(it, ::MemoryPushSecrets) },
            imageLoader = { _, _, _, _ -> throw UnsupportedOperationException("engine tests load no images") },
        ),
        databases,
        prefs,
        EngineConfig(environment, rivetPublishableToken = "pk", clientVersion = "test", networkLog = false, pushRelayUrl = null),
    )

    suspend fun ready(): AccountRoster = (engines.roster.first { it is Loadable.Ready } as Loadable.Ready).value

    suspend fun rosterWhere(predicate: (AccountRoster) -> Boolean): AccountRoster =
        (engines.roster.first { it is Loadable.Ready && predicate(it.value) } as Loadable.Ready).value

    /** Collects one sign-in attempt, answering its confirmation with [add], and returns every step. */
    suspend fun signIn(steps: Flow<SignInStep>, add: Boolean = true, onStep: (SignInStep) -> Unit = {}): List<SignInStep> {
        val seen = mutableListOf<SignInStep>()
        steps.collect { step ->
            seen += step
            onStep(step)
            if (step is SignInStep.Confirm) if (add) step.candidate.add() else step.candidate.cancel()
        }
        return seen
    }
}
