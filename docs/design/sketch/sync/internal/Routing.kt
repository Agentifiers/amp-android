// :sync  package amp.sync.internal.outbox   (internal)
// Every write goes one way: dispatch → (Durable: outbox row | Ephemeral: memory) → drainer → routeOf →
// channel → settle by delivery class. One exhaustive `when` decides the channel for every Command
// leaf, so adding a command without a route fails to compile, and the /request/rpc path cannot spread
// by accident.
package amp.sync.internal.outbox

import amp.model.*
import amp.sync.api.Outcome

internal sealed interface Route {
    /**
     * The thread actor socket. Each thread has at most one socket, owned by its engine: the lease's
     * socket when one is open, else a short socket opened for this send and closed after it. A lease
     * that opens meanwhile joins the short socket instead of opening a second one. A short socket is
     * refused for an Asleep orb, and the entry stays Queued until the thread is next opened, so the
     * outbox never wakes an orb.
     */
    data object ThreadSocket : Route
    /**
     * POST …/threadActor/request/rpc with x-rivet-conn-params. The gateway accepts only
     * client_append_user_msg: spike S18 probed 18 methods and the other 17 (client_tool_approval_response,
     * client_cancel, client_set_thread_title, client_upsert_notification_subscription, client_filesystem_*,
     * …) return -32601 Method not found (s18b-rpc-allowlist.jsonl, measured). Taken only when no live
     * socket exists, mainly by notification quick reply. StatelessThreadRpc exposes appendUserMessage
     * and nothing else, so the type system keeps every other command off this path.
     */
    data object StatelessRpc : Route
    /** POST /api/internal?<method>. Methods marked ✓ appear in the CLI bundle (private-protocol.md). */
    data class Internal(val method: String) : Route
    data class UserAction(val name: String) : Route
}

internal fun routeOf(command: Command, liveSocket: Boolean): Route = when (command) {
    is ThreadIntent.Send, is ThreadIntent.RunChangesAction ->
        if (liveSocket) Route.ThreadSocket else Route.StatelessRpc
    // /request/rpc rejects every method it was probed with except append (S18), so the rest use the socket.
    is ThreadIntent.EditMessage, is ThreadIntent.SteerQueued, is ThreadIntent.RemoveQueued,
    is ThreadIntent.ReorderQueue, is ThreadIntent.Interrupt, is ThreadIntent.Retry,
    is ThreadIntent.DismissError, is ThreadIntent.React, is ThreadIntent.Answer, is ThreadIntent.AnswerSecret,
    is ThreadIntent.Rename, is ThreadIntent.MarkRead, is ThreadIntent.MarkUnread,
    is ThreadIntent.DismissRecap, is ThreadIntent.SetFeature, is ThreadIntent.OpenToCollaborators,
    is ThreadIntent.Comment, is ThreadIntent.WakeExecutor, is ThreadIntent.RetryFailedSetup,
    is ThreadIntent.RunPluginCommand,
    -> Route.ThreadSocket
    is ThreadIntent.Pin -> if (command.pinned) Route.Internal("updateThread") else Route.UserAction("setThreadPinned")  // as S10 verified
    is ThreadIntent.Color, is ThreadIntent.Snooze, is ThreadIntent.SetVisibility -> Route.Internal("updateThread")
    is ThreadIntent.SetLabels -> Route.Internal("setThreadLabels")
    is ThreadIntent.Archive -> Route.Internal("archiveThread")
    is ThreadIntent.Delete -> Route.Internal("deleteThread")
    is ThreadIntent.SetNetRoute -> Route.Internal("setThreadNetRoute")
    is ThreadIntent.SetPortalHostname -> Route.Internal("setThreadPortalHostname")
    is ThreadIntent.RestartOrbProcesses -> Route.Internal("restartOrbProcesses")
    is ThreadIntent.ReportBug -> Route.Internal("openCustomBugReport")
    is AccountCommand.ShareRunner -> Route.Internal(if (command.share) "shareRunner" else "revokeRunnerShare")
    is AccountCommand.SetSecret -> Route.Internal("setEnvVarSecret")
    is AccountCommand.SetAutomationEnabled -> Route.Internal("updateAutomation")
    is AccountCommand.DeleteAutomation -> Route.Internal("deleteAutomation")
    is AccountCommand.SetShipBehavior -> Route.Internal("updateProject")
    is AccountCommand.EditVocabulary -> Route.Internal("updateDictationVocabulary")   // [guess] name; getDictationVocabulary✓ is the read
    is AccountCommand.CreateOAuthClient -> Route.Internal("createExternalAPIOAuthClient")
    is AccountCommand.ClearOrbSnapshots -> Route.Internal("deleteProjectSandboxSnapshots")
}

/** One cli-tier listing per settings key, exhaustive like routeOf. */
internal fun sourceOf(resource: AccountResource<*>): Route.Internal = when (resource) {
    AccountResource.RunnerShares -> Route.Internal("listRunnerShares")
    is AccountResource.Secrets -> Route.Internal("listEnvVarSecrets")
    AccountResource.Automations -> Route.Internal("listAutomations")
    AccountResource.DictationVocabulary -> Route.Internal("getDictationVocabulary")
    AccountResource.McpApprovals -> Route.Internal("listMCPServerApprovals")
    AccountResource.OAuthClients -> Route.Internal("listExternalAPIOAuthClients")
    AccountResource.Extensions -> Route.Internal("mcpListAccountServers")   // plus the two repository listings, fetched together
    AccountResource.CustomDomains -> Route.Internal("listCustomDomains")
}

/**
 * The versioned serializer for outbox rows. It takes Command.Durable only, so an Ephemeral command
 * cannot be encoded and never reaches Room. Each leaf has a stable type name and an explicit field
 * list, written by hand because :core:model types carry no serialization annotations. decode returns
 * null for an unknown version or type name, and the drainer marks that row Rejected("Sent by an older
 * version of the app") so the user can resend it. OutboxCodecTest round-trips one value of every leaf.
 */
internal object OutboxCodec {
    const val VERSION: Int = 1
    fun encode(command: Command.Durable): String = TODO()
    fun decode(json: String): Command.Durable? = TODO()
}

/**
 * Drains Queued rows, FIFO per thread, threads in parallel. Per attempt:
 *  1. routeOf(command, liveSocket).
 *  2. ThreadSocket: take the thread's one socket (the lease's, or a short socket) and wait until its
 *     client_resume replay and snapshot burst have folded. Plugin dialogs are never cached, so a guard
 *     read before the burst would miss them.
 *  3. Guarded: evaluate the guard against the caught-up DurableThread. False → Dropped(guard), done.
 *  4. Send.
 *  5. Settle by outcome and class:
 *       Ok                    → Sending until the echo (Sends) or the result, then the row is deleted
 *       Failed(Server)        → Rejected(reason); the user edits or discards
 *       Unknown / link lost   → Replayable: Queued, resend with Backoff
 *                               AtMostOnce: OutboxState.Unknown, never resent on its own
 *                               Guarded:    Queued; steps 2 and 3 run again first
 *       Refused (Asleep orb)  → stays Queued until the thread is next leased
 * Runs on connectivity, on every new Epoch after the replay (Effect.DrainOutbox), and from an
 * expedited WorkManager job at app start when rows are Queued. The job is a @HiltWorker inside :sync.
 */
internal interface OutboxDrainer {
    fun enqueue(thread: ThreadId?, command: Command.Durable): IntentId
    fun observe(thread: ThreadId): kotlinx.coroutines.flow.Flow<List<OutboxEntry>>
    /** Echoes from message_added and queued_message_added (Facts.kt I7). */
    suspend fun acknowledge(echoes: List<MessageId>)
    suspend fun drainOnce(): Outcome<Int>
}

/**
 * Ephemeral commands. Held in memory only, sent once by the same steps 1 to 4, settled in memory.
 * A lost ack shows OutboxState.Unknown until the user discards it; process death drops it.
 */
internal interface EphemeralSender {
    fun send(thread: ThreadId?, command: Command.Ephemeral): IntentId
    fun observe(thread: ThreadId): kotlinx.coroutines.flow.Flow<List<OutboxEntry>>
}
