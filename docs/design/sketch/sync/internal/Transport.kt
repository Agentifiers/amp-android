// :sync  package amp.sync.internal.transport   (everything `internal`)
// The transport boundary. Four channels, one framing layer, one place where drift is absorbed.
// Wire DTOs are @Serializable and internal; they never leave this module.
package amp.sync.internal.transport

import amp.model.*
import amp.sync.api.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

// ── Endpoint and credentials ─────────────────────────────────────────────────

/** Per account. Custom domains change baseUrl; Rivet endpoint is `<baseUrl>/actors`. */
internal data class Endpoint(val baseUrl: String, val authApiUrl: String, val rivetNamespace: String, val rivetPublishableToken: String)

/**
 * Bearer apiKey (OAuth JWT) + refresh token. Refresh uses compare-and-set under a per-account Mutex,
 * 2 min before expiry and once on 401 (private-protocol.md:37, :69). Every HTTP client of the account,
 * including AuthenticatedImageLoader's, refreshes through this one object.
 */
internal interface Credentials {
    suspend fun bearer(): String
    suspend fun onUnauthorized(): RefreshResult
    /** POST /api/thread-actors {threadId, usesThreadActors:true} → wsToken, capability, poolName, threadVersion. */
    suspend fun threadToken(thread: ThreadId): Outcome<WsGrant>
    /** POST /api/user-actor-credentials {capability} → read token for the socket, write token for actions. */
    suspend fun userToken(write: Boolean): Outcome<WsGrant>
}
/** threadVersion equals the thread's thread.data.v (s17-resume-cursor.jsonl:4 gives 110, :108 gives 9, measured); null for user tokens. */
internal data class WsGrant(val token: String, val capability: Viewer, val expiresAtEpochMs: Long, val poolName: String?, val threadVersion: Seq?)
internal enum class RefreshResult { Refreshed, ReauthRequired }

/**
 * :sync is the only owner of credentials. Tink AEAD keyset wrapped by an Android Keystore key
 * (StrongBox when available), file per account `vault-<accountId>.bin`, excluded from backup.
 * Tokens and push keys are separate entries with separate compare-and-set, so a refresh can never
 * write back push keys that register() replaced. The push private key is a non-exportable P-256 key in
 * Keystore alias `amp_push_<accountId>` (ECDH from API 31, auth-and-security.md A6); the vault holds
 * only its public half and the auth secret.
 */
internal interface TokenVault {
    suspend fun tokens(account: AccountId): TokenEntry?
    /** Writes only if the stored refresh token still equals [expectedRefresh]. */
    suspend fun compareAndSetTokens(account: AccountId, expectedRefresh: String?, next: TokenEntry): Boolean
    suspend fun pushKeys(account: AccountId): PushKeys?
    /** Writes only if the stored public key still equals [expectedPublic]; null means "none stored yet". */
    suspend fun compareAndSetPushKeys(account: AccountId, expectedPublic: ByteArray?, next: PushKeys): Boolean
    suspend fun pushRegistration(account: AccountId): PushRegistration?
    /** Same compare-and-set rule as push keys, keyed on the stored registration. */
    suspend fun compareAndSetPushRegistration(account: AccountId, expected: PushRegistration?, next: PushRegistration?): Boolean
    suspend fun wipe(account: AccountId)
}
internal data class TokenEntry(val accessToken: String, val refreshToken: String?, val accessExpiresAtEpochMs: Long)
internal data class PushKeys(val keystoreAlias: String, val publicKey: ByteArray, val authSecret: ByteArray)

/** The account's push endpoint. Lives in the vault next to PushKeys, written with the same compare-and-set. */
internal sealed interface PushRegistration {
    /** rid = base64url(SHA-256(capability)); secret authorizes PUT and DELETE (docs/design/push-relay.md). */
    data class Relay(val rid: String, val secret: String, val pushUrl: String) : PushRegistration
    data class UnifiedPush(val endpointUrl: String) : PushRegistration
}

/** The push relay API (docs/design/push-relay.md). Never logs the secret or the push URL path. */
internal interface PushRelayClient {
    suspend fun register(fcmToken: String): Outcome<PushRegistration.Relay>
    suspend fun updateToken(registration: PushRegistration.Relay, fcmToken: String): Outcome<Unit>
    /** 404 counts as success: the registration is already gone. */
    suspend fun delete(registration: PushRegistration.Relay): Outcome<Unit>
}

// ── Channel 1: internal RPC ──────────────────────────────────────────────────

/**
 * POST <base>/api/internal?<method> {method, params}; gzip > 25,600 bytes; {ok, result|error}.
 * Method names come only from routeOf and sourceOf (Routing.kt) and the fleet sources, so there is no
 * separate method table. Integers follow [RivetNumber] on every channel.
 */
internal interface InternalRpc {
    suspend fun <P, R> call(method: RpcMethod<P, R>, params: P): Outcome<R>
}
internal class RpcMethod<P, R>(val name: String, val decode: (JsonObject) -> R, val encode: (P) -> JsonObject)

/**
 * One integer rule for every channel (internal RPC, thread actor JSON-RPC, user actor actions and
 * frames): an integer at or above 2^31 goes out as a `.0` literal such as `2147483648.0`. The encoder
 * writes the digits itself, never Double.toString, which switches to exponent notation (transport.md).
 */
internal object RivetNumber {
    fun encode(value: Long): String = TODO("value < 2^31 → digits; else digits + \".0\"")
}

// ── Channel 2: thread actor socket ───────────────────────────────────────────

/**
 * wss <base>/actors/gateway/threadActor/websocket/?rvt-namespace&rvt-method=get&rvt-key=<threadId>
 * &rvt-token=<pk>&rvt-skip-ready-wait=true, subprotocols rivet, rivet_encoding.bare,
 * rivet_skip_ready_wait, rivet_conn_params.<json{wsToken, clientApplication:{type:"cli", name, version},
 * interactiveClient:true, transport:"json-rpc"}> (spike S4, measured with the object form; whether the
 * server accepts a non-"cli" type is an open question in docs/design/transport.md).
 */
internal interface ThreadSocket {
    val frames: Flow<InboundFrame>
    val state: StateFlow<SocketState>
    suspend fun connect(grant: WsGrant)
    /** JSON-RPC request; id "thread-client-<ms>-<n>"; settled by [RequestCorrelator]. */
    suspend fun request(method: String, params: JsonObject, requestId: String?): Outcome<JsonObject>
    fun sendBinary(frame: RelayFrame)
    fun close(code: Int)
}
internal sealed interface SocketState {
    data object Idle : SocketState
    data object Opening : SocketState
    data object Open : SocketState
    data class Closed(val code: Int, val reason: String) : SocketState
}

/**
 * Tolerant frame parse (spike S9: replay arrives as bare {"type":…} frames, live as JSON-RPC
 * notifications; a bare text "pong" arrives once after connect). Every text frame becomes exactly
 * one of these; parse failures become Unparseable and are counted, never thrown.
 */
internal sealed interface InboundFrame {
    /** A thread actor event. `raw` is the verbatim frame, cached by the fold for Sequenced and Snapshot facts. */
    data class Event(val type: String, val seq: Seq?, val raw: String, val body: JsonObject) : InboundFrame
    data class Response(val id: String, val result: JsonObject?, val error: RpcError?) : InboundFrame
    /** A client_*_result notification carrying the requestId of an earlier request. Never becomes a fact. */
    data class Result(val requestId: String, val type: String, val body: JsonObject) : InboundFrame
    data class Relay(val frame: RelayFrame) : InboundFrame
    data object Pong : InboundFrame
    data class Unparseable(val preview: String) : InboundFrame
}
internal data class RpcError(val code: Int, val message: String, val data: JsonObject?)

/**
 * Two kinds of request:
 *  - It carries a requestId (client_git_command, client_git_diff_snapshot, client_filesystem_*, …). The
 *    JSON-RPC response {ok:true} only acknowledges receipt. The data arrives later in a client_*_result
 *    notification with the same requestId (s07-changes.jsonl:63-65: response at +75 ms, result at
 *    +233 ms; s19-files.jsonl:50-52, measured). It settles on the Result, on an error Response, or as
 *    Outcome.Unknown when [TIMEOUT_MS] passes. An ok Response never settles it.
 *  - It carries no requestId (client_append_user_msg, client_resume, client_cancel, …). It settles on the
 *    Response, or as Outcome.Unknown at the same timeout.
 * Anything for an id that is already settled is dropped.
 */
internal interface RequestCorrelator {
    fun register(rpcId: String, requestId: String?): kotlinx.coroutines.Deferred<Outcome<JsonObject>>
    fun onResponse(frame: InboundFrame.Response)
    fun onResult(frame: InboundFrame.Result)
    companion object { const val TIMEOUT_MS = 120_000L }
}

/**
 * Relay binary frame: [0xAE, 0x01, kind(1 to executor, 2 from executor), flags(bit0 binary,
 * bit1 fin), idLen] + channelId + payload; 1,000,000-byte chunks, 16 MiB reassembly cap
 * (private-protocol.md:108). Text control goes as client_executor_relay_data{dataBase64} until the
 * first binary frame arrives, then binary (spike S8).
 */
internal data class RelayFrame(val toExecutor: Boolean, val binary: Boolean, val fin: Boolean, val channelId: String, val payload: ByteArray) {
    fun encode(): ByteArray = TODO()
    companion object { fun decode(bytes: ByteArray): RelayFrame? = TODO("null when magic mismatch") }
}

/** Multiplexes relay channels over one ThreadSocket. Terminal and desktop sit on top. */
internal interface RelayMux {
    suspend fun open(service: String, binaryFrames: Boolean): RelayStream
}
internal interface RelayStream : AutoCloseable {
    val channelId: String
    val incoming: Flow<ByteArrayOrText>
    fun send(data: ByteArrayOrText)
}
internal sealed interface ByteArrayOrText { class Bytes(val b: ByteArray) : ByteArrayOrText; class Text(val s: String) : ByteArrayOrText }

// ── Channel 3: stateless thread RPC ──────────────────────────────────────────

/**
 * POST <base>/actors/gateway/threadActor/request/rpc?rvt-namespace=default&rvt-method=get&rvt-key=<id>
 * &rvt-token=<pk>&rvt-skip-ready-wait=true, header x-rivet-conn-params {wsToken, transport:"json-rpc"}
 * (spike S6, measured). The gateway accepts client_append_user_msg and nothing else (S18: 17 other
 * methods return -32601), so this interface has exactly one method.
 */
internal interface StatelessThreadRpc {
    suspend fun appendUserMessage(thread: ThreadId, params: JsonObject): Outcome<JsonObject>
}

// ── Channel 4: user actor ────────────────────────────────────────────────────

/**
 * Socket: <base>/actors/gateway/userActor/connect?…&rvt-key=<userId>, subprotocols rivet,
 * rivet_encoding.json, rivet_skip_ready_wait, rivet_conn_params.{type:"user", wsToken}; one
 * SubscriptionRequest per event name; request ids come back as ["$BigInt","n"] (spike S14).
 * Stateless actions: POST <base>/actors/gateway/userActor/action/<name> with write token.
 */
internal interface UserActor {
    val events: Flow<UserActorEvent>
    suspend fun connect(subscribe: Set<String>)
    suspend fun action(name: String, args: JsonObject, write: Boolean): Outcome<JsonObject>
    fun close()
}
internal data class UserActorEvent(val name: String, val body: JsonObject)

// ── Plain HTTP ───────────────────────────────────────────────────────────────

/**
 * Attachments (S12 verified), diff captures (cli, NOT VERIFIED for orbs), threads/find.
 * spaceToken and transcribe are web-plain tier, the only two web-plain calls the app makes. Both serve
 * features outside the core loop and both have fallbacks: Space opens on the web, and dictation falls
 * back to the on-device SpeechRecognizer when transcribe fails.
 */
internal interface AmpHttp {
    suspend fun uploadAttachment(thread: ThreadId?, mediaType: String, filename: String, bytes: Flow<ByteArray>, size: Long): Outcome<String>
    suspend fun latestCapture(thread: ThreadId, etag: String?): Outcome<CaptureResult>
    suspend fun blob(thread: ThreadId, sha: String): Outcome<ByteArray>
    suspend fun find(q: String, limit: Int): Outcome<List<JsonObject>>
    suspend fun spaceToken(thread: ThreadId): Outcome<SpaceGrant>
    suspend fun transcribe(audio: Flow<ByteArray>, mime: String): Outcome<String>
}
internal sealed interface CaptureResult { data object NotModified : CaptureResult; data object None : CaptureResult; data class Body(val etag: String, val json: JsonObject) : CaptureResult }
internal data class SpaceGrant(val url: String, val token: String)

// ── Reconnect policy ─────────────────────────────────────────────────────────

/**
 * Base 3 s, max 30 s, jitter ×0.8–1.2; never stops while a viewer is present (a lease or a terminal
 * viewer) and the network is up (the web caps at 5; we show Link.Reconnecting instead of giving up). ACTOR_STOPPING and -32603
 * → reconnect; the outbox then settles the in-flight command by its delivery class
 * (private-protocol.md:107, :119).
 * Token near expiry → client_reauthorize{wsToken}; on failure replace the socket.
 */
internal object Backoff {
    fun next(attempt: Int): Long = TODO()
}

// ── Network log for agent-device `network dump` ──────────────────────────────

/**
 * Debug builds write one logcat line per finished HTTP exchange, tag `AgentNet`, which is the shape
 * agent-device's `network dump` parser reads (spike S20, parser run on sample lines, no device):
 *     I/AgentNet(<pid>): {"method":…,"url":…,"status":…,"headers":{…},"requestBody":…,"responseBody":…} duration=<ms>
 * Rules from S20: one physical line per exchange (no request/response pairing; a line missing status
 * or duration gets them filled from neighbours, which leaks values); `duration=` as text outside the
 * JSON; no other braces on the line; request and response headers merged into one `headers` field.
 * agent-device returns the whole raw line at every include level, so tokens, wsToken and
 * x-rivet-conn-params are redacted before logging (lib.ts redact() set), and bodies are omitted unless
 * debug body logging is on (verification.md). Socket frames log as `WS> method` / `WS< type seq` with
 * no braces and no `duration`, so the parser skips them and they cannot lend values to HTTP entries.
 */
internal interface NetworkLog { fun exchange(line: String); fun frame(line: String) }
