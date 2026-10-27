// :core:model  package amp.model
// Every write a user can ask for. ThreadHandle.dispatch takes ThreadIntents, AccountScope.dispatch
// takes AccountCommands. Both are Commands, and every Command names its delivery class in its type:
//
//   Replayable  resending after an unknown outcome is harmless (a set, or the server dedupes by a key
//               this client minted). The outbox resends it after reconnect.
//   AtMostOnce  a resend could double-apply or is rejected (client_filesystem_write_file_v3 is
//               "reject", private-protocol.md:115). A lost ack becomes OutboxState.Unknown and the
//               user decides. Never resent automatically.
//   Guarded     only meaningful while a predicate over the fold holds. The drainer re-checks the guard
//               after the socket's resume replay and snapshot burst, before every attempt, and drops
//               the command when it no longer holds.
//   Ephemeral   carries a secret value. Memory only: OutboxCodec (sync/internal/Routing.kt) encodes
//               Command.Durable and nothing else, so an Ephemeral command cannot reach a Room row. It is
//               sent once; a lost ack shows OutboxState.Unknown in memory, and process death drops it, so
//               the user enters the value again (auth-and-security.md, secret fields).
//
// Each leaf implements exactly one class (DeliveryClassTest asserts it by reflection). :sync's
// `routeOf` is an exhaustive `when` over the leaves, so a new command without a route fails to compile.
package amp.model

import kotlinx.collections.immutable.PersistentList

sealed interface Command {
    /** The three classes the outbox persists. */
    sealed interface Durable : Command
    sealed interface Replayable : Durable
    sealed interface AtMostOnce : Durable
    sealed interface Guarded : Durable { val guard: Guard }
    /** Never persisted. [guard] is checked like a Guarded command's when present. */
    sealed interface Ephemeral : Command { val guard: Guard? }
}

/** Predicates the drainer evaluates against the thread's caught-up durable fold before each attempt. */
sealed interface Guard {
    data class GatePending(val gate: GateId) : Guard
    data class QueuedStillPresent(val queued: QueuedMessageId) : Guard
    data object AgentFailed : Guard
    data object SetupFailed : Guard
}

sealed interface ThreadIntent : Command {

    // ── Composer ──
    /**
     * client_append_user_msg{content, messageId, steer?}. messageId is minted once and stored in the
     * outbox. Replayable: a resend with the same messageId, over the socket or /request/rpc, returns
     * ok and re-emits message_added with the original seq, leaving one transcript copy
     * (spike S16, s16-dedupe.jsonl:35-46, measured). Acknowledged by the first echo of its messageId:
     * queued_message_added (a steer or queue while busy; queuedMessage.messageId is the client id,
     * s05-steer-interrupt.jsonl:56, measured) or message_added (Facts.kt I7).
     */
    data class Send(val draft: Draft, val mode: SendMode, val messageId: MessageId = MessageId.mint()) : ThreadIntent, Command.Replayable
    /** client_edit_message{…, editId}. Server dedupe by editId is read from the web client, not observed. */
    data class EditMessage(val target: MessageId, val text: String, val editId: EditId = EditId.mint()) : ThreadIntent, Command.Replayable
    data class SteerQueued(val id: QueuedMessageId) : ThreadIntent, Command.Guarded {
        override val guard: Guard get() = Guard.QueuedStillPresent(id)
    }
    data class RemoveQueued(val id: QueuedMessageId) : ThreadIntent, Command.Replayable
    data class ReorderQueue(val order: PersistentList<QueuedMessageId>) : ThreadIntent, Command.Replayable
    /**
     * client_cancel over the socket, never /request/rpc (S18: -32601). Its own command, so a resent
     * Send can never cancel the turn it started. "Interrupt and send" in the composer dispatches
     * Interrupt, then a separate Send (ComposerAction.intents).
     */
    data object Interrupt : ThreadIntent, Command.AtMostOnce
    data object Retry : ThreadIntent, Command.Guarded { override val guard: Guard get() = Guard.AgentFailed }
    data object DismissError : ThreadIntent, Command.Replayable
    data class React(val message: MessageId, val reaction: String, val on: Boolean) : ThreadIntent, Command.Replayable

    // ── Gates ──
    /** Someone else answering first makes the guard false, and the command drops as AnsweredElsewhere. */
    data class Answer(val response: GateResponse) : ThreadIntent, Command.Guarded {
        override val guard: Guard get() = Guard.GatePending(response.gate)
    }
    /** A secret plugin input (PluginGate.SecretInput). Same wire as Answer, never persisted. */
    data class AnswerSecret(val response: SecretPluginAnswer) : ThreadIntent, Command.Ephemeral {
        override val guard: Guard get() = Guard.GatePending(response.gate)
    }

    // ── Organization (internal RPC or socket; all are sets) ──
    data class Rename(val title: String) : ThreadIntent, Command.Replayable           // client_set_thread_title (S10)
    data class Pin(val pinned: Boolean) : ThreadIntent, Command.Replayable
    data class Color(val color: ThreadColor) : ThreadIntent, Command.Replayable
    data class SetLabels(val labels: PersistentList<LabelName>) : ThreadIntent, Command.Replayable
    data class Snooze(val until: SnoozeUntil?) : ThreadIntent, Command.Replayable       // null = wake
    data class Archive(val archived: Boolean) : ThreadIntent, Command.Replayable
    /** deleteThread✓. A second delete answers NotFound, which the drainer counts as done [inferred]. */
    data object Delete : ThreadIntent, Command.Replayable
    data class MarkRead(val upTo: MessageId) : ThreadIntent, Command.Replayable
    data object MarkUnread : ThreadIntent, Command.Replayable
    data object DismissRecap : ThreadIntent, Command.Replayable                         // client_dismiss_recap
    /** client_update_thread_feature (fast mode and other thread features, cli tier). */
    data class SetFeature(val feature: String, val enabled: Boolean) : ThreadIntent, Command.Replayable

    // ── Sharing and multiplayer ──
    data class SetVisibility(val visibility: Visibility) : ThreadIntent, Command.Replayable
    data class OpenToCollaborators(val ttlSeconds: Int?) : ThreadIntent, Command.Replayable  // 300..604800 or null
    /** No client-minted comment id exists, so a resend could post twice. */
    data class Comment(val anchor: MessageId, val selection: TextSelection, val text: String) : ThreadIntent, Command.AtMostOnce

    // ── Changes (prompt sends; ship is a prompt, private-protocol.md:230) ──
    /** Resolves to client_append_user_msg with meta.changesWorkflow, so it is Replayable like Send. */
    data class RunChangesAction(val action: ChangesAction, val messageId: MessageId = MessageId.mint()) : ThreadIntent, Command.Replayable

    // ── Plugin commands ──
    /**
     * executor_plugin_message{message{type:"request", id, method:"command.execute", params{pluginName,
     * commandId, threadID}}} (spike S15b, s15b-plugin-dialog.jsonl:63, measured). Running a command
     * twice runs it twice, so a lost ack is the user's call.
     */
    data class RunPluginCommand(val plugin: String, val commandId: String) : ThreadIntent, Command.AtMostOnce

    // ── Machine (internal RPC, cli tier) ──
    data class SetNetRoute(val exitNode: RunnerId?) : ThreadIntent, Command.Replayable                  // setThreadNetRoute✓
    data class SetPortalHostname(val port: Int, val hostname: String) : ThreadIntent, Command.Replayable // setThreadPortalHostname✓
    data object RestartOrbProcesses : ThreadIntent, Command.AtMostOnce                                  // restartOrbProcesses✓
    /** Explicit, because opening an asleep orb bills (collab-apps.md:388). */
    data object WakeExecutor : ThreadIntent, Command.Replayable
    data object RetryFailedSetup : ThreadIntent, Command.Guarded {                                     // client_spawn_executor{retryFailedSetup}
        override val guard: Guard get() = Guard.SetupFailed
    }

    // ── Support ──
    /** openCustomBugReport✓ + attachBugReportDiagnostics✓. The engine attaches its own logs and the AgentNet log. */
    data class ReportBug(val text: String, val includeDiagnostics: Boolean) : ThreadIntent, Command.AtMostOnce
}

/**
 * Account-level writes that used to be one method each on a settings facade. They go through the same
 * dispatch and the same routeOf as thread intents. Reads are AccountResource keys (Settings.kt).
 */
sealed interface AccountCommand : Command {
    data class ShareRunner(val runner: RunnerId, val share: Boolean) : AccountCommand, Command.Replayable      // shareRunner✓ / revokeRunnerShare✓
    /** setEnvVarSecret✓. Ephemeral: the value never reaches the outbox table. */
    data class SetSecret(val target: SecretTarget, val name: String, val value: String, val secret: Boolean) : AccountCommand, Command.Ephemeral {
        override val guard: Guard? get() = null
    }
    data class SetAutomationEnabled(val id: String, val enabled: Boolean) : AccountCommand, Command.Replayable // updateAutomation✓
    data class DeleteAutomation(val id: String) : AccountCommand, Command.Replayable                           // deleteAutomation✓
    data class SetShipBehavior(val project: ProjectId, val behavior: ShipBehavior) : AccountCommand, Command.Replayable // updateProject✓
    data class EditVocabulary(val add: PersistentList<String>, val remove: PersistentList<String>) : AccountCommand, Command.Replayable
    data class CreateOAuthClient(val name: String, val scopes: PersistentList<String>, val redirectUris: PersistentList<String>) : AccountCommand, Command.AtMostOnce
    /** deleteProjectSandboxSnapshots✓: the "my setup change isn't applying" fix. */
    data class ClearOrbSnapshots(val project: ProjectId) : AccountCommand, Command.Replayable
}

/**
 * How a Send behaves while the agent is busy (core.md:223-226). Steer = client_append_user_msg{steer:true},
 * injected at the next tool boundary (spike S5, measured). Queue = append without steer, delivered
 * after the turn (read from the web client, not observed; the M3 gate adds a queued send).
 * When idle, every mode collapses to a plain send. Interrupting is a separate command (ThreadIntent.Interrupt).
 */
enum class SendMode { Auto, Steer, Queue }

data class TextSelection(val textIndex: Int, val start: Int, val end: Int, val quoted: String)

/** Built-in Changes buttons. The user's custom buttons come from getChangesPrompts, which is web-remote, so the app has none. */
sealed interface ChangesAction {
    data object Ship : ChangesAction
    data object PushBranch : ChangesAction
    data object Review : ChangesAction
    data object Restack : ChangesAction
    data object Rebase : ChangesAction
    data object UpdateBase : ChangesAction
}
