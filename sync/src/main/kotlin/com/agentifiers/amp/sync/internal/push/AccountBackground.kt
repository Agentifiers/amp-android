package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.store.Outbox
import com.agentifiers.amp.store.OutboxRow
import com.agentifiers.amp.store.OutboxRowState
import com.agentifiers.amp.store.PushSubRow
import com.agentifiers.amp.store.PushSubscriptions
import com.agentifiers.amp.sync.api.BackgroundAccess
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.FleetSignal
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.PushChannel
import com.agentifiers.amp.sync.api.PushEvent
import com.agentifiers.amp.sync.api.SettledGate
import com.agentifiers.amp.sync.internal.net.Outcome as NetOutcome
import com.agentifiers.amp.sync.internal.net.UserGrant
import com.agentifiers.amp.sync.internal.outbox.OutboxCodec
import com.agentifiers.amp.sync.internal.thread.ThreadDeps
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.RivetNumber
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.TimeZone
import kotlin.time.Instant
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [BackgroundAccess] for one signed-in account. Every call is one-shot: a stateless HTTP call, or a short thread
 * socket that resumes from the grant's `threadVersion`, does one job and closes. Nothing here holds a lasting socket
 * except [watchFleet], whose collector (the Watch service) bounds it. A non-null [ownPushUrl] (`Endpoint.ownPushUrl`)
 * is where the account's own server takes its Web Push, whatever the device's channel.
 */
internal class AccountBackground(
    private val deps: ThreadDeps,
    private val outbox: Outbox,
    private val subscriptions: PushSubscriptions,
    private val vault: PushSecrets,
    private val relay: PushRelayClient?,
    private val ownPushUrl: String?,
    private val watchedThreads: WatchedThreads,
    override val leasedThreads: StateFlow<PersistentSet<ThreadId>>,
) : BackgroundAccess {
    private val settled = MutableSharedFlow<SettledGate>(extraBufferCapacity = 16)
    internal val keys: PushKeys by lazy { vault.keys() }

    override val watched: StateFlow<PersistentSet<ThreadId>> get() = watchedThreads.threads
    override val settledGates: Flow<SettledGate> get() = settled

    override fun setWatched(thread: ThreadId, watching: Boolean) = watchedThreads.set(thread, watching)

    override suspend fun recentStatuses(sinceSeen: Instant): Outcome<PersistentList<ThreadSummary>> {
        val grant = when (val result = deps.http.userGrant()) {
            is NetOutcome.Ok -> result.value
            NetOutcome.Unauthorized -> return unauthorized()
            is NetOutcome.Failed -> return Outcome.Failed(EngineError.Server("user-actor-credentials", result.reason))
        }
        val args = buildJsonArray {
            addJsonObject {
                put("limit", RECENT_LIMIT)
                put("sinceMs", RivetNumber.encode(sinceSeen.toEpochMilliseconds()))
            }
        }
        return when (val output = deps.http.userAction(grant, "getRecentThreads", args)) {
            is NetOutcome.Ok -> (output.value as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(UserActorStatus::summaryOf) }
                ?.let { Outcome.Ok(it.toPersistentList()) }
                ?: Outcome.Failed(EngineError.Drift("getRecentThreads"))
            NetOutcome.Unauthorized -> unauthorized()
            is NetOutcome.Failed -> Outcome.Failed(EngineError.Server("getRecentThreads", output.reason))
        }
    }

    override suspend fun peekGates(thread: ThreadId): Outcome<PersistentList<HumanGate>> = withSession(thread) { session ->
        Outcome.Ok(PushGates.pending(session.events).toPersistentList())
    }

    /**
     * Guarded: the gate must still be pending after `client_resume` on this socket, so an answer already given on
     * another client is refused instead of sent twice. Waits for the settle event before reporting success.
     */
    override suspend fun answer(thread: ThreadId, response: GateResponse): Outcome<Unit> {
        val wire = PushGates.request(response) ?: return Outcome.Refused("Answer this one in Amp on the web.")
        return withSession(thread) { session ->
            val gate = PushGates.pending(session.events).firstOrNull { it.id == response.gate }
                ?: return@withSession Outcome.Refused(ANSWERED_ELSEWHERE)
            val reply = session.request(wire.method, wire.params) ?: return@withSession Outcome.Unknown
            if (reply.error != null) return@withSession Outcome.Failed(EngineError.Server("${reply.error.code}", reply.error.message))
            val settle = session.events.firstOrNull { PushGates.settles(it, gate) } ?: session.awaitEvent { PushGates.settles(it, gate) }
            if (settle == null) return@withSession Outcome.Unknown
            settled.tryEmit(SettledGate(thread, gate.id))
            Outcome.Ok(Unit)
        }
    }

    /**
     * Replayable through the outbox: the row is written before the call, so a send that dies with the process is
     * delivered by the drainer later under the same message id, which the server dedupes (S16).
     */
    override suspend fun quickReply(thread: ThreadId, text: String): Outcome<MessageId> {
        val messageId = MessageId.mint()
        val intentId = IntentId.mint()
        outbox.enqueue(OutboxRow(intentId.raw, thread.raw, messageId.raw, OutboxCodec.encode(ThreadIntent.Send(Draft.text(text), SendMode.Auto, messageId)), OutboxRowState.Sending, 1, System.currentTimeMillis()))
        val grant = when (val result = deps.http.threadGrant(thread)) {
            is NetOutcome.Ok -> result.value
            NetOutcome.Unauthorized -> return unauthorized()
            is NetOutcome.Failed -> {
                outbox.mark(intentId.raw, OutboxRowState.WaitingNetwork, null)
                return Outcome.Failed(EngineError.Server("thread-actors", result.reason))
            }
        }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "thread-one-shot-client-${System.currentTimeMillis()}-0")
            put("method", "client_append_user_msg")
            putJsonObject("params") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
                put("messageId", messageId.raw)
                grant.agentMode?.let { put("agentMode", it) }
                putJsonObject("userState") {
                    putJsonArray("currentlyVisibleFiles") {}
                    put("timeZone", TimeZone.getDefault().id)
                }
            }
        }
        val request = Request.Builder()
            .url(deps.endpoint.gateway("threadActor", "request/rpc", thread.raw))
            .header("x-rivet-conn-params", buildJsonObject { put("wsToken", grant.wsToken); put("transport", "json-rpc") }.toString())
            .post(frame.toString().toRequestBody(JSON))
            .build()
        deps.log.frame('>', "client_append_user_msg (rpc)")
        val answer = try {
            withContext(Dispatchers.IO) {
                deps.client.newCall(request).execute().use { response ->
                    if (response.code == 401) return@use null
                    try {
                        AmpJson.parseToJsonElement(response.body.string()) as? JsonObject
                    } catch (_: SerializationException) {
                        JsonObject(emptyMap())
                    }
                }
            }
        } catch (e: IOException) {
            outbox.mark(intentId.raw, OutboxRowState.WaitingNetwork, null)
            return Outcome.Failed(EngineError.Offline)
        } ?: return unauthorized()
        val error = answer.obj("error")
        if (error != null || answer["result"] == null) {
            val reason = error?.string("message") ?: "Amp did not accept the reply."
            outbox.mark(intentId.raw, OutboxRowState.Rejected, reason)
            return Outcome.Failed(EngineError.Server(error?.get("code")?.toString() ?: "rpc", reason))
        }
        outbox.acknowledge(listOf(messageId.raw))
        return Outcome.Ok(messageId)
    }

    override suspend fun markRead(thread: ThreadId, upTo: MessageId): Outcome<Unit> = withSession(thread) { session ->
        val reply = session.request("client_mark_message_read", buildJsonObject { put("messageId", upTo.raw) })
        when {
            reply == null -> Outcome.Unknown
            reply.error != null -> Outcome.Failed(EngineError.Server("${reply.error.code}", reply.error.message))
            else -> Outcome.Ok(Unit)
        }
    }

    /**
     * Converges the account onto [channel]. A relay channel registers or updates the FCM token at the relay, and is
     * refused when the build names no relay; [PushChannel.None] is refused; a new push URL drops the record of every subscription the old URL had,
     * so reconciliation subscribes again. An account with [ownPushUrl] makes no relay call at all and leaves any old
     * relay row in place.
     */
    override suspend fun ensurePushRegistration(channel: PushChannel): Outcome<String> = withContext(Dispatchers.IO) {
        val current = vault.registration()
        val next = if (ownPushUrl != null) PushRegistration(ownPushUrl, null) else when (channel) {
            is PushChannel.Relay -> when (val answer = relay?.ensure(current?.relay, channel.fcmToken) ?: return@withContext Outcome.Refused(NO_RELAY)) {
                is RelayAnswer.Ok -> PushRegistration(answer.value.pushUrl, answer.value)
                RelayAnswer.Gone -> return@withContext Outcome.Failed(EngineError.NotFound("relay registration"))
                RelayAnswer.Unauthorized -> return@withContext Outcome.Failed(EngineError.Unauthorized)
                is RelayAnswer.Failed -> return@withContext Outcome.Failed(EngineError.Server("relay", answer.reason))
            }
            is PushChannel.UnifiedPush -> {
                current?.relay?.let { relay?.delete(it) }
                PushRegistration(channel.endpointUrl, null)
            }
            PushChannel.None -> return@withContext Outcome.Refused(NO_CHANNEL)
        }
        if (current != null && current.endpoint != next.endpoint) subscriptions.forgetEndpoint(hash(current.endpoint))
        vault.saveRegistration(next)
        Outcome.Ok(next.endpoint)
    }

    /** Subscribes every recently active thread that has no subscription from this device's current endpoint. */
    override suspend fun reconcilePushSubscriptions(): Outcome<Int> {
        val endpoint = withContext(Dispatchers.IO) { vault.registration()?.endpoint } ?: return Outcome.Refused("This device has no push registration.")
        val recent = when (val statuses = recentStatuses(Instant.fromEpochMilliseconds(System.currentTimeMillis() - RECENT_WINDOW_MS))) {
            is Outcome.Ok -> statuses.value
            is Outcome.Failed -> return statuses
            Outcome.Unknown -> return Outcome.Unknown
            is Outcome.Refused -> return statuses
        }
        var subscribed = 0
        for (summary in recent) {
            if (subscriptions.isSubscribed(summary.id.raw, hash(endpoint))) continue
            if (subscribe(summary.id, endpoint) is Outcome.Ok) subscribed++
        }
        return Outcome.Ok(subscribed)
    }

    /** One upsert over a short socket; recorded only once the server acknowledges it. Idempotent server-side (S11). */
    suspend fun subscribe(thread: ThreadId): Outcome<Unit> {
        val endpoint = withContext(Dispatchers.IO) { vault.registration()?.endpoint } ?: return Outcome.Refused("This device has no push registration.")
        if (subscriptions.isSubscribed(thread.raw, hash(endpoint))) return Outcome.Ok(Unit)
        return subscribe(thread, endpoint)
    }

    private suspend fun subscribe(thread: ThreadId, endpoint: String): Outcome<Unit> {
        val keys = withContext(Dispatchers.IO) { keys }
        val params = buildJsonObject {
            putJsonObject("subscription") {
                put("endpoint", endpoint)
                putJsonObject("keys") {
                    put("auth", base64(keys.auth))
                    put("p256dh", base64(keys.publicKey))
                }
            }
            put("threadURL", deps.endpoint.base.newBuilder().addPathSegments("threads/${thread.raw}").build().toString())
        }
        return withSession(thread) { session ->
            val reply = session.request("client_upsert_notification_subscription", params)
            when {
                reply == null -> Outcome.Unknown
                reply.error != null -> Outcome.Failed(EngineError.Server("${reply.error.code}", reply.error.message))
                else -> {
                    subscriptions.record(PushSubRow(thread.raw, hash(endpoint), System.currentTimeMillis()))
                    Outcome.Ok(Unit)
                }
            }
        }
    }

    override fun decodePush(body: ByteArray): PushEvent? = WebPushDecryptor.decrypt(body, keys)?.let(PushPayload::parse)

    override fun watchFleet(): Flow<FleetSignal> = flow {
        val grant: UserGrant = (deps.http.userGrant() as? NetOutcome.Ok)?.value ?: return@flow
        val known = (recentStatuses(Instant.fromEpochMilliseconds(System.currentTimeMillis() - RECENT_WINDOW_MS)) as? Outcome.Ok)?.value.orEmpty()
        emitAll(UserActorWatch(deps.client, deps.endpoint).signals(grant, known.associateBy { it.id }))
    }

    /** Sign-out: the relay forgets this account's push URL, so Amp's next push to it gets 410. Idempotent. */
    suspend fun forget() = withContext(Dispatchers.IO) {
        vault.registration()?.relay?.let { relay?.delete(it) }
        vault.wipe()
    }

    /** The relay id of this account's registration, matched against `data.rid` of a forwarded FCM message. */
    fun relayId(): String? = vault.registration()?.relay?.rid

    private suspend fun <T> withSession(thread: ThreadId, work: suspend (ShortSession) -> Outcome<T>): Outcome<T> =
        when (val opened = ShortSession.open(deps, thread)) {
            is ShortOpen.Ready -> opened.session.use { work(it) }
            ShortOpen.Unauthorized -> unauthorized()
            is ShortOpen.Failed -> Outcome.Failed(EngineError.Server("thread socket", opened.reason))
        }

    private fun unauthorized(): Outcome<Nothing> {
        deps.onUnauthorized()
        return Outcome.Failed(EngineError.Unauthorized)
    }

    /** The send's outbox row, in the shape the outbox drainer decodes (`{"v":1,"type":"Send",...}`). */
    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val RECENT_LIMIT = 50
        const val RECENT_WINDOW_MS = 24L * 60 * 60 * 1000
        const val ANSWERED_ELSEWHERE = "Answered elsewhere"
        const val NO_RELAY = "This build has no push relay."
        const val NO_CHANNEL = "This device has no push channel."
        val JSON = "application/json".toMediaType()

        fun hash(endpoint: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(endpoint.encodeToByteArray()))
    }
}
