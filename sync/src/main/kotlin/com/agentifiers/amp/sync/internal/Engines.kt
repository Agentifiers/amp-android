package com.agentifiers.amp.sync.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.Command
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.internal.catalog.CatalogEngine
import com.agentifiers.amp.sync.internal.catalog.selfHostedThreadParams
import com.agentifiers.amp.sync.internal.wire.string
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.OrbProvider
import kotlinx.serialization.json.JsonObject
import com.agentifiers.amp.sync.internal.thread.Spawned
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountResource
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.CredentialKind
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.NewThreadSpec
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.store.AccountDatabases
import com.agentifiers.amp.store.AppPreferences
import com.agentifiers.amp.store.RosterProto
import com.agentifiers.amp.sync.api.AccountCommandResult
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.AmpEngines
import com.agentifiers.amp.sync.api.AttachmentUploads
import com.agentifiers.amp.sync.api.AuthenticatedImageLoader
import com.agentifiers.amp.sync.api.Catalog
import com.agentifiers.amp.sync.api.EngineConfig
import com.agentifiers.amp.sync.api.EngineParts
import com.agentifiers.amp.sync.api.Fleet
import com.agentifiers.amp.sync.api.LeaseIssuer
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.api.McpApps
import com.agentifiers.amp.sync.api.PuckDirectory
import com.agentifiers.amp.sync.api.SignIn
import com.agentifiers.amp.sync.api.SignInFailure
import com.agentifiers.amp.sync.api.SignInResult
import com.agentifiers.amp.sync.api.SignInStep
import com.agentifiers.amp.sync.api.StartedThread
import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.sync.api.ViewerToken
import com.agentifiers.amp.sync.internal.auth.AccountBook
import com.agentifiers.amp.sync.internal.auth.AccountTokens
import com.agentifiers.amp.sync.internal.auth.AuthCalls
import com.agentifiers.amp.sync.internal.auth.Identity
import com.agentifiers.amp.sync.internal.auth.PendingCredential
import com.agentifiers.amp.sync.internal.auth.RefreshAnswer
import com.agentifiers.amp.sync.internal.auth.RosterRecord
import com.agentifiers.amp.sync.internal.auth.SignInFlows
import com.agentifiers.amp.sync.internal.auth.identify
import com.agentifiers.amp.sync.internal.auth.toStored
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploader
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploadsEngine
import com.agentifiers.amp.sync.internal.fleet.FleetEngine
import com.agentifiers.amp.sync.internal.fleet.Organizer
import com.agentifiers.amp.sync.internal.fleet.StoreOrganizeLedger
import com.agentifiers.amp.sync.internal.puck.PuckEngine
import com.agentifiers.amp.sync.internal.images.AuthedImages
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Bearer
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.LOCAL_HOSTS
import com.agentifiers.amp.sync.internal.net.NetLog
import com.agentifiers.amp.sync.internal.net.BearerTokens
import com.agentifiers.amp.sync.internal.push.AccountBackground
import com.agentifiers.amp.sync.internal.push.LeaseLedger
import com.agentifiers.amp.sync.internal.push.LeasedThreadHandle
import com.agentifiers.amp.sync.internal.push.PushBackgroundProvider
import com.agentifiers.amp.sync.internal.push.PushRelayClient
import com.agentifiers.amp.sync.internal.push.PushSecrets
import com.agentifiers.amp.sync.internal.push.PushVault
import com.agentifiers.amp.sync.internal.push.WatchedThreads
import com.agentifiers.amp.sync.internal.settings.AccountSettings
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.portals.McpAppsEngine
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import com.agentifiers.amp.sync.internal.vault.PendingRevocation
import com.agentifiers.amp.sync.internal.vault.SealedFiles
import com.agentifiers.amp.sync.internal.vault.StoredCredential
import com.agentifiers.amp.sync.internal.vault.TinkSealedFiles
import com.agentifiers.amp.sync.internal.vault.TokenVault
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import com.agentifiers.amp.sync.api.Outcome as ApiOutcome
import com.agentifiers.amp.sync.internal.net.Outcome

/**
 * What the engine needs from the device: sealed files for the vault, the image cache root, whether this is a
 * debuggable build, each account's push secrets, and an image loader writing to one account's cache directory.
 */
internal class EnginePlatform(
    val sealed: SealedFiles,
    val imageCacheRoot: File,
    val allowLocalHttp: Boolean,
    val retireLegacy: () -> Unit,
    val pushSecrets: (AccountId) -> PushSecrets,
    val imageLoader: (client: OkHttpClient, endpoint: Endpoint, tokens: BearerTokens, cacheDir: File) -> AuthenticatedImageLoader,
) {
    companion object {
        fun android(context: Context): EnginePlatform {
            val files = TinkSealedFiles(context)
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return EnginePlatform(
                files,
                File(context.cacheDir, "images"),
                debuggable,
                files::retireSingleTokenVault,
                { PushVault(context, it) },
                { client, endpoint, tokens, cacheDir -> AuthedImages(context, client, endpoint, tokens, cacheDir) },
            )
        }
    }
}

/**
 * Up to five signed-in accounts, each with its own [AccountScope], vault file and roster entry. The roster lists
 * them in the order they were added; an account Amp stopped accepting stays listed as [AuthHealth.NeedsSignIn]
 * with its cached threads until the user signs in again or signs out.
 */
internal class DefaultEngines(
    private val platform: EnginePlatform,
    private val databases: AccountDatabases,
    private val prefs: AppPreferences,
    private val config: EngineConfig,
) : AmpEngines, SignIn, LeaseIssuer, AccountBook {
    constructor(context: Context, databases: AccountDatabases, prefs: AppPreferences, config: EngineConfig) :
        this(EnginePlatform.android(context), databases, prefs, config)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + StorageFailureHandler)
    private val vault = TokenVault(platform.sealed)
    private val log = NetLog(config.networkLog)
    private val client = OkHttpClient.Builder().apply { if (config.networkLog) addInterceptor(log.interceptor()) }.build()
    private val calls = AuthCalls(client)
    private val flows = SignInFlows(client, this, platform.allowLocalHttp, ::endpointOf)
    private val transitions = Mutex()

    /** Insertion-ordered, replaced whole under [transitions], so [scope] reads it without the lock. */
    @Volatile private var sessions: PersistentMap<AccountId, Session> = persistentMapOf()
    private var lastUsed: AccountId? = null

    private val mutableRoster = MutableStateFlow<Loadable<AccountRoster>>(Loadable.Loading)
    override val roster: StateFlow<Loadable<AccountRoster>> = mutableRoster.asStateFlow()
    override val signIn: SignIn get() = this

    private val relay = config.pushRelayUrl?.let { PushRelayClient(client, it.toHttpUrl()) }
    val parts = EngineParts(this, this, PushBackgroundProvider(roster, scope, hasPushRelay = relay != null) { sessions.mapValues { it.value.background } })

    init {
        scope.launch {
            transitions.withLock {
                withContext(Dispatchers.IO) { platform.retireLegacy() }
                val stored = prefs.roster.data.first()
                sessions = stored.accounts.mapNotNull(RosterRecord::fromEntry).mapNotNull(::open).associateBy { it.id }.toPersistentMap()
                lastUsed = stored.lastUsedId?.let(::AccountId)
                publish()
            }
            retryRevocations()
        }
    }

    override fun issue(windowId: String): ViewerToken = ViewerToken(windowId)

    /** The account's background access, for debug builds that encrypt an injected push to its keys. */
    fun background(account: AccountId): AccountBackground? = sessions[account]?.background

    /** The number of thread engines told to drop their socket, across every account. */
    fun dropThreadSockets(): Int = sessions.values.sumOf { it.dropSockets() }

    /** Stores [commands] in [thread]'s outbox, in the last-used account, for the debug `seed-outbox` event. Null when none is signed in. */
    suspend fun seedOutbox(thread: ThreadId, commands: List<Command.Durable>, inFlight: Boolean): Int? {
        val active = (roster.value as? Loadable.Ready)?.value?.lastUsed
        val session = active?.let(sessions::get) ?: sessions.values.firstOrNull() ?: return null
        return session.seed(thread, commands, inFlight)
    }

    override fun scope(account: AccountId): AccountScope =
        sessions[account] ?: throw IllegalArgumentException("No signed-in account ${account.raw}")

    override fun start(server: ServerEnvironment): Flow<SignInStep> = flows.device(server)

    override fun browser(server: ServerEnvironment): Flow<SignInStep> = flows.browser(server)

    override fun paste(server: ServerEnvironment, accessToken: String): Flow<SignInStep> = flows.paste(server, accessToken)

    override fun refuses(server: ServerEnvironment): Boolean =
        sessions.size >= AccountRoster.MAX_ACCOUNTS && sessions.values.none { it.account.value.let { a -> a.server == server && a.auth == AuthHealth.NeedsSignIn } }

    override suspend fun store(pending: PendingCredential): SignInStep = when (val stored = put(pending)) {
        is Stored.SignedIn -> SignInStep.SignedIn(stored.account)
        is Stored.AlreadySignedIn -> SignInStep.Failed(SignInFailure.AlreadySignedIn)
        Stored.Full -> SignInStep.Failed(SignInFailure.TooManyAccounts)
    }

    /** Automation's path: no confirmation, and the same account again is accepted as it stands. */
    override suspend fun withToken(server: ServerEnvironment, accessToken: String): SignInResult {
        if (accessToken.isBlank()) return SignInResult.Rejected("Paste an Amp access token.")
        val token = SignInFlows.tokenOrNull(accessToken) ?: return SignInResult.Rejected("The token contains spaces or characters a token never has.")
        val endpoint = endpointOf(server) ?: return SignInResult.Rejected("${server.ampBaseUrl} is not a server address.")
        val identity = when (val found = identify(client, endpoint, token)) {
            is Identity.Known -> found
            Identity.Rejected -> return SignInResult.Rejected("Amp rejected this token.")
            is Identity.Unreachable -> return SignInResult.Rejected("Could not check the token: ${found.reason}")
            Identity.Unreadable -> return SignInResult.Rejected("Could not check the token: Amp answered in a shape this version cannot read.")
        }
        return when (val stored = put(PendingCredential.AccessToken(server, identity.userId, identity.email, token))) {
            is Stored.SignedIn -> SignInResult.Accepted(stored.account.id)
            is Stored.AlreadySignedIn -> SignInResult.Accepted(stored.id)
            Stored.Full -> SignInResult.Rejected("You can keep up to 5 accounts signed in.")
        }
    }

    /**
     * Closes the account's sockets, ends its OAuth session at Amp, removes its push registration and keys, and
     * deletes its vault file and key, database and image cache, then its roster entry. Each step tolerates state
     * that is already gone.
     */
    override suspend fun signOut(account: AccountId) = transitions.withLock {
        sessions[account]?.let { session ->
            sessions = sessions.remove(account)
            if (lastUsed == account) lastUsed = null
            session.close()
            publish()
            revoke(session)
            session.background.forget()
        }
        vault.wipe(account)
        databases.delete(account)
        withContext(Dispatchers.IO) { File(platform.imageCacheRoot, account.raw).deleteRecursively() }
        persist()
    }

    private sealed interface Stored {
        class SignedIn(val account: Account) : Stored
        class AlreadySignedIn(val id: AccountId) : Stored
        data object Full : Stored
    }

    /** The account rules: a new account if there is room, a new credential for one that needs sign-in, never a second copy. */
    private suspend fun put(pending: PendingCredential): Stored = transitions.withLock {
        val record = RosterRecord.of(pending)
        val id = record.account.id
        val existing = sessions[id]
        if (existing != null) {
            if (existing.account.value.auth != AuthHealth.NeedsSignIn) return@withLock Stored.AlreadySignedIn(id)
            existing.tokens.replace(pending.toStored())
            existing.update(record.account)
            existing.fleet.refresh()
        } else {
            if (sessions.size >= AccountRoster.MAX_ACCOUNTS) return@withLock Stored.Full
            val session = checkNotNull(open(record)) { "sign-in proved a server it cannot address" }
            session.tokens.replace(pending.toStored())
            sessions = sessions.put(id, session)
            lastUsed = id
        }
        persist()
        publish()
        Stored.SignedIn(record.account)
    }

    /** Amp refused the account's credential for good: keep it listed, close its sockets, wipe nothing. */
    private suspend fun needsSignIn(account: AccountId) = transitions.withLock {
        val session = sessions[account] ?: return@withLock
        if (session.account.value.auth == AuthHealth.NeedsSignIn) return@withLock
        session.update(session.account.value.copy(auth = AuthHealth.NeedsSignIn))
        session.dropSockets()
        persist()
        publish()
    }

    /**
     * `oauth-logout`. When Amp cannot be reached, or no access token can be had without it, the refresh token
     * waits in its own file for the next start.
     */
    private suspend fun revoke(session: Session) {
        if (session.account.value.credential != CredentialKind.OAuth) return
        val base = session.account.value.server.ampBaseUrl.toHttpUrlOrNull() ?: return
        val bearer = session.tokens.current() as? Bearer.Token
        if (bearer != null && calls.logout(base, bearer.value)) return
        val stored = vault.read(session.id) as? StoredCredential.OAuth ?: return
        val server = session.account.value.server.ampBaseUrl
        vault.keepForRevocation(session.id, PendingRevocation(server, stored.apiUrl, stored.clientId, stored.refreshToken, calls.now()))
    }

    /** Each start retries every pending revocation once: refresh, then `oauth-logout`. After seven days it is dropped. */
    private suspend fun retryRevocations() {
        for ((account, pending) in vault.pendingRevocations()) {
            if (calls.now() - pending.since > REVOCATION_WINDOW) {
                vault.dropRevocation(account)
                continue
            }
            when (val answer = calls.refresh(pending.apiUrl, pending.clientId, pending.refreshToken)) {
                is RefreshAnswer.Tokens -> {
                    val base = pending.ampBaseUrl.toHttpUrlOrNull()
                    if (base != null && calls.logout(base, answer.grant.accessToken)) {
                        vault.dropRevocation(account)
                    } else {
                        val rotated = answer.grant.refreshToken ?: pending.refreshToken
                        vault.keepForRevocation(account, PendingRevocation(pending.ampBaseUrl, pending.apiUrl, pending.clientId, rotated, pending.since))
                    }
                }
                RefreshAnswer.InvalidGrant -> vault.dropRevocation(account)
                is RefreshAnswer.Unreachable -> Unit
            }
        }
    }

    private suspend fun persist() {
        val entries = sessions.values.map { RosterRecord(it.account.value, it.userId).toEntry() }
        prefs.roster.updateData { RosterProto(entries, lastUsed?.raw) }
    }

    private fun publish() {
        val accounts = sessions.values.map { it.account.value }.toPersistentList()
        mutableRoster.value = Loadable.Ready(AccountRoster(accounts, lastUsed ?: accounts.lastOrNull()?.id), Clock.System.now())
    }

    private fun open(record: RosterRecord): Session? {
        val endpoint = endpointOf(record.account.server) ?: return null
        return Session(record, endpoint)
    }

    private fun endpointOf(server: ServerEnvironment): Endpoint? {
        val amp = server.ampBaseUrl.toHttpUrlOrNull() ?: return null
        val actors = server.actorsBaseUrl.toHttpUrlOrNull() ?: return null
        // Amp's gateway needs the CLI's publishable token; a server on this machine (the fake) never checks it, so a
        // build without one still runs its flows.
        val token = config.rivetPublishableToken.ifBlank { if (amp.host in LOCAL_HOSTS) LOCAL_RIVET_TOKEN else "" }
        return Endpoint(amp, actors, token)
    }

    private inner class Session(record: RosterRecord, endpoint: Endpoint) : AccountScope {
        val id = record.account.id
        val userId = record.userId
        private val rejected: () -> Unit = { scope.launch { needsSignIn(id) } }
        val tokens = AccountTokens(id, vault, calls, record.account.auth == AuthHealth.NeedsSignIn, rejected)
        private val http = AmpHttp(client, endpoint, tokens)
        private val sessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + StorageFailureHandler)
        private val uploader = AttachmentUploader(client, endpoint, tokens)
        private val database = databases.forAccount(id)
        private val threads = ConcurrentHashMap<ThreadId, ThreadEngine>()
        private val handles = ConcurrentHashMap<ThreadId, ThreadHandle>()
        private val leases = LeaseLedger()
        private val watchedThreads = WatchedThreads(database.watchList, sessionScope)
        private val fleetEngine = FleetEngine(
            http, sessionScope, rejected, watchedThreads, StoreOrganizeLedger(database), leases.threads,
            dispatch = { thread, intent -> thread(thread).dispatch(intent) },
            threadState = { thread -> thread(thread).state },
        )
        private val deps = ThreadDeps(client, endpoint, http, config.clientVersion, log, rejected, database, uploader, Organizer(http, fleetEngine, sessionScope, rejected), fleetEngine::self)
        val background = AccountBackground(deps, database.outbox, database.pushSubscriptions, platform.pushSecrets(id), relay, endpoint.ownPushUrl(id), watchedThreads, leases.threads)

        private val mutableAccount = MutableStateFlow(record.account)
        override val account: StateFlow<Account> = mutableAccount.asStateFlow()
        override val fleet: Fleet = fleetEngine
        override val catalog: Catalog by lazy { CatalogEngine(http, sessionScope, rejected) }
        override val puck: PuckDirectory = PuckEngine(http, fleetEngine, sessionScope, rejected) { id -> thread(id).let { threads.getValue(id) } }
        private val imageLoader = lazy { platform.imageLoader(client, endpoint, tokens, File(platform.imageCacheRoot, id.raw)) }
        override val images: AuthenticatedImageLoader by imageLoader
        override val attachments: AttachmentUploads = AttachmentUploadsEngine(uploader)
        override val mcpApps: McpApps = McpAppsEngine(http)

        private val settings = AccountSettings(http, sessionScope)

        override fun <T> read(resource: AccountResource<T>): StateFlow<Loadable<T>> = settings.read(resource)

        override fun refresh(resource: AccountResource<*>) = settings.refresh(resource)

        override fun dispatch(command: AccountCommand): IntentId = settings.dispatch(command)

        override val commandResults: SharedFlow<AccountCommandResult> get() = settings.commandResults

        override fun thread(id: ThreadId): ThreadHandle = handles.computeIfAbsent(id) {
            LeasedThreadHandle(threads.computeIfAbsent(id) { ThreadEngine(it, deps, sessionScope) }, leases) { opened ->
                sessionScope.launch { background.subscribe(opened.id) }
            }
        }

        /**
         * Creates the thread, then spawns its executor on the new thread's socket; `runnerId` in the create call
         * is refused with 403 (S4). The first message is queued only after the spawn succeeds, so a failed spawn
         * leaves an empty thread the user can retry, respawn elsewhere, or discard. A self-hosted orb is the one
         * exception: `client_spawn_executor` cannot name a provider, so [startOnSelfHosted] creates and spawns in one call.
         */
        override suspend fun startThread(spec: NewThreadSpec): ApiOutcome<StartedThread> {
            val orb = spec.executor as? ExecutorChoice.Orb
            val selfHosted = orb?.provider as? OrbProvider.SelfHosted
            if (orb != null && selfHosted != null) return startOnSelfHosted(spec, orb, selfHosted)
            val created = when (val result = http.createThread(spec.mode.key)) {
                is Outcome.Ok -> result.value
                Outcome.Unauthorized -> return ApiOutcome.Failed(EngineError.Unauthorized).also { rejected() }
                is Outcome.Failed -> return ApiOutcome.Failed(errorOf(result))
            }
            val engine = threads.computeIfAbsent(created) { ThreadEngine(it, deps, sessionScope) }
            if (spec.labels.isNotEmpty()) {
                http.internal("addThreadLabels", buildJsonObject {
                    put("thread", created.raw)
                    putJsonArray("labels") { spec.labels.forEach { add(JsonPrimitive(it.raw)) } }
                })
            }
            return when (val spawned = engine.spawn(spec.executor)) {
                Spawned.Ok -> {
                    engine.dispatch(ThreadIntent.Send(spec.firstMessage, SendMode.Auto))
                    ApiOutcome.Ok(StartedThread.Running(created))
                }
                is Spawned.Failed -> ApiOutcome.Ok(StartedThread.SpawnFailed(created, spawned.reason))
            }
        }

        /** A refusal keeps the draft in the composer. Whether Amp leaves a thread behind when it refuses is not observed. */
        private suspend fun startOnSelfHosted(spec: NewThreadSpec, orb: ExecutorChoice.Orb, provider: OrbProvider.SelfHosted): ApiOutcome<StartedThread> =
            when (val result = http.internal("createRemoteExecutorThread", selfHostedThreadParams(spec, orb, provider, MessageId.mint()))) {
                is Outcome.Ok -> (result.value as? JsonObject)?.string("threadID")?.let(ThreadId::parse)
                    ?.let { ApiOutcome.Ok(StartedThread.Running(it)) }
                    ?: ApiOutcome.Failed(EngineError.Drift("createRemoteExecutorThread"))
                Outcome.Unauthorized -> ApiOutcome.Failed(EngineError.Unauthorized).also { rejected() }
                is Outcome.Failed -> ApiOutcome.Failed(errorOf(result))
            }

        override suspend fun respawn(thread: ThreadId, executor: ExecutorChoice): ApiOutcome<Unit> {
            if ((executor as? ExecutorChoice.Orb)?.provider is OrbProvider.SelfHosted) {
                return ApiOutcome.Refused("A self-hosted orb starts with a new thread. Discard this one and send again.")
            }
            val engine = threads.computeIfAbsent(thread) { ThreadEngine(it, deps, sessionScope) }
            return when (val spawned = engine.spawn(executor)) {
                Spawned.Ok -> ApiOutcome.Ok(Unit)
                is Spawned.Failed -> ApiOutcome.Failed(EngineError.Server("spawn", spawned.reason))
            }
        }

        /** The debug `seed-outbox` event's way in. */
        suspend fun seed(thread: ThreadId, commands: List<Command.Durable>, inFlight: Boolean): Int =
            threads.computeIfAbsent(thread) { ThreadEngine(it, deps, sessionScope) }.seed(commands, inFlight)

        fun update(next: Account) {
            mutableAccount.value = next
        }

        fun dropSockets(): Int = threads.values.onEach(ThreadEngine::dropSocket).size

        fun close() {
            sessionScope.cancel()
            if (imageLoader.isInitialized()) imageLoader.value.coil.shutdown()
        }
    }

    private companion object {
        fun errorOf(failed: Outcome.Failed): EngineError = when (failed.status) {
            null -> EngineError.Offline
            402 -> EngineError.PaymentRequired
            else -> EngineError.Server(failed.status.toString(), failed.reason)
        }

        val REVOCATION_WINDOW = 7.days
    }
}

private const val LOCAL_RIVET_TOKEN = "pk-local-server"
