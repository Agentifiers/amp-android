package com.agentifiers.amp.fake

import com.agentifiers.amp.fake.scenarios.Workspace
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One fake thread. [listing] is its `listThreads` row and [status] its user actor summary, both captured shapes;
 * the server keeps their live fields (title, version, state, unread) current as the thread runs. [labels] and
 * [project] seed `getThreadLabels` and `getThreadProject`.
 * A thread with a [workspace] answers `client_filesystem_*` from it instead of from its recordings.
 * [historyPageSize] caps each transcript page the way Amp pages a long thread; null serves the whole history at once.
 * A thread with an [orb] has a sleeping orb that a wake brings back, or fails to.
 */
internal class FakeThread(
    val id: String,
    val listing: JsonObject,
    val status: JsonObject,
    val script: ThreadScript,
    val plugin: PluginScript? = null,
    val labels: List<String> = emptyList(),
    val project: FakeProject? = null,
    val workspace: Workspace? = null,
    val historyPageSize: Int? = null,
    val voice: VoiceScript? = null,
    val orb: OrbScript? = null,
)

/** A sleeping orb: a wake connects it after [wakeMs], or after [wakeMs] fails with [failure]. */
internal class OrbScript(val wakeMs: Long, val failure: String? = null)

/**
 * One self-hosted orb provider in the workspace, in the shape of the web composer's `orbProviderOptions` (`configID`,
 * `name`, `isDefault`, `owner`; web chunk `aa7cdc32.js`). [listed] false leaves it out of the web layout's list.
 */
internal data class FakeOrbProvider(
    val configID: String,
    val name: String,
    val isDefault: Boolean = false,
    val owner: String = "workspace",
    val listed: Boolean = true,
)

/**
 * The account's ampcode.com web app, which the app reads only inside its WebView profile. The first [layoutReads]
 * signed-in layout reads answer; every read after them answers 404, as if Amp moved the route. [providerNames] false makes `createRemoteExecutorThread`
 * resolve `{configID}` only, so a started thread proves the app sent the listed id.
 */
internal class FakeWeb(val layoutReads: Int = Int.MAX_VALUE, val providerNames: Boolean = true)

/**
 * Everything one scenario serves. Internal RPC and user actor answers come from every capture's recordings unless
 * [internal] or [actions] answers the method first. Threads created with `POST /api/thread-actors` play [newThread].
 * A non-null [spawnRefusal] refuses every `client_spawn_executor` with that message. A non-null [frameGapCapMs]
 * replaces the server's cap, for a scenario whose turns must last long enough for a person or a flow to act mid-turn.
 * [attachments] is content served Bearer-only at `/user-content/attachments/<key>` without an upload, keyed by [Attachments.key].
 */
internal class Scenario(
    val threads: List<FakeThread>,
    val attachments: Map<String, StoredAttachment> = emptyMap(),
    val newThread: () -> ThreadScript = { ThreadScript.from(Capture.load("s04-live-thread")) },
    val internal: Map<String, JsonElement> = emptyMap(),
    val actions: Map<String, JsonElement> = emptyMap(),
    val spawnRefusal: String? = null,
    val frameGapCapMs: Long? = null,
    val auth: FakeAuth = CapturedAuth,
    /** Consulted before the thread methods and the recordings; null falls through to them. */
    val methods: InternalMethods? = null,
    /** The workspace's self-hosted providers, which `createRemoteExecutorThread{orbProvider}` resolves against. */
    val orbProviders: List<FakeOrbProvider> = emptyList(),
    /** Null serves no web app pages. */
    val web: FakeWeb? = null,
)

/** Who a Bearer token belongs to. `getUserInfo` and `/api/user-actor-credentials` answer for this identity. */
internal data class FakeIdentity(val userId: String, val email: String, val username: String, val displayName: String)

/** One request to a sign-in route: `/api/auth/...`, `/auth/...` or `/user_management/...`. */
internal class AuthRequest(val method: String, val path: String, val query: (String) -> String?, val body: String, val bearer: String?)

internal class AuthReply(val status: Int, val body: JsonObject, val headers: Map<String, String> = emptyMap())

/** Sign-in and identity for one scenario. Runs on the [World] loop. */
internal interface FakeAuth {
    /** The identity behind [token], or null when `/api` must answer 401. */
    fun identityOf(token: String): FakeIdentity?

    /** Answers a sign-in route; null is a 404. */
    fun route(request: AuthRequest, origin: String): AuthReply?
}

/** Internal RPC methods a scenario scripts. Returns the whole `{ok, result}` or `{ok:false, error}` envelope, or null. */
internal interface InternalMethods {
    fun answer(method: String, params: JsonObject, identity: FakeIdentity): JsonObject?
}

/** The captured account: any non-blank Bearer is the S1 user, and the device poll stays pending as captured. */
internal object CapturedAuth : FakeAuth {
    val identity = FakeIdentity(USER_ID, "fake-amp@example.com", "fake-amp", "Fake Amp")

    override fun identityOf(token: String): FakeIdentity? = identity.takeIf { token.isNotBlank() }

    override fun route(request: AuthRequest, origin: String): AuthReply? = when (request.path) {
        "/api/auth/oauth-config" -> AuthReply(200, capturedAuthBody("oauth-config", origin) + mapOf("apiURL" to element(origin)))
        "/user_management/authorize/device" -> AuthReply(200, capturedAuthBody("device-authorize", origin))
        "/user_management/authenticate" -> AuthReply(400, capturedAuthBody("device-poll-once", origin))
        else -> null
    }
}

/** A body from `captures/s01-auth.jsonl`, with ampcode.com pointed at [origin]. */
internal fun capturedAuthBody(channel: String, origin: String): JsonObject =
    Capture.load("s01-auth").responseBody(channel).mapStrings { it.replace("https://ampcode.com", origin) }

/** A scenario lives in its own file under `scenarios/` and is listed once in [SCENARIOS]. */
internal interface ScenarioSpec {
    val name: String
    fun build(): Scenario
}

/** The account every capture was recorded with. */
internal const val USER_ID = "user_01EXAMPLE00000000000000000"

/** The runner thread most spikes used (`captures/s02-threads.jsonl:2`). */
internal const val RUNNER_THREAD = "T-01a16702-3202-73fb-8f52-e4498ccd3721"

/** The thread S15 to S19 used (`captures/s15b-plugin-dialog.jsonl:17`). */
internal const val GATE_THREAD = "T-01a16738-2ec2-7354-98e9-688bc413f079"

/** The captured `listThreads` row of [RUNNER_THREAD], unarchived so the app lists it. */
internal fun runnerListing(): JsonObject {
    val row = Capture.load("s02-threads").responseBody("internal.listThreads").obj("result")!!.array("threads")!!
        .map { it as JsonObject }.first { it.string("id") == RUNNER_THREAD }
    return row + mapOf("archived" to element(false))
}

/** The captured `getRecentThreads` summary of [RUNNER_THREAD], unarchived. */
internal fun runnerStatus(): JsonObject = recentStatus(RUNNER_THREAD) + mapOf("archived" to element(false))

internal fun recentStatus(threadId: String): JsonObject =
    Capture.load("s02-threads").responseBody("userActor.action.getRecentThreads").array("output")!!
        .map { it as JsonObject }.first { it.string("threadId") == threadId }

/** A `listThreads` row for a thread the captures only saw on its socket, in the captured row's shape. */
internal fun listingFor(id: String, title: String?, version: Long): JsonObject =
    runnerListing() + mapOf("id" to element(id), "title" to element(title), "v" to element(version), "messageCount" to element(0))

/** A user actor summary for a thread the captures only saw on its socket, from the S15b status event. */
internal fun gateStatus(): JsonObject {
    val event = Capture.load("s15b-plugin-dialog").line(75).frame!!.obj("body")!!.obj("val")!!
    return event.array("args")!!.first() as JsonObject
}
