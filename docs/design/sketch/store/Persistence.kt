// :store  package amp.store
// Persistence model. One Room DB per account (`amp-<accountId>.db`) so signing out deletes one
// file and accounts can never read each other's rows. One DataStore file per proto type. Secrets
// never touch Room or DataStore: tokens and push keys live in :sync's TokenVault, and Ephemeral
// commands never reach the outbox table.
//
// Principle: the server is the source of truth; the cache is disposable. Transcript rows store the
// raw wire event JSON (opaque String here; only :sync parses it), so parser fixes apply to cached
// threads without a migration, and schema changes use destructive migration for cache tables.
// User-authored tables (outbox, drafts, ledgers) have real migrations: losing them loses user work.
package amp.store

import kotlinx.coroutines.flow.Flow

// ── Cache tables (destructive migration allowed) ─────────────────────────────

/** @Entity thread_summary_list / thread_summary_live: one row per source, merged in :sync. */
data class SummaryRow(val threadId: String, val source: String /* "list" | "live" */, val json: String, val updatedAtMs: Long)

// Transcript cache, one table per fact class (sync/internal/Facts.kt). Volatile facts have no table (I4).

/**
 * @Entity message_row, PK (threadId, messageId). Sequenced message facts (message_added, and
 * message_updated [inferred]) and paged history rows upsert the same key (I6). index (threadId, ordinal)
 * for windowed reads; ordinal is the paged messageIndex when known, else max + 1.
 */
data class MessageRow(val threadId: String, val messageId: String, val ordinal: Long, val seq: Long?, val json: String)

/**
 * @Entity sequenced_row, PK (threadId, seq). Non-message Sequenced facts, keyed by the seq the frame
 * actually carries: queued_message_added|dequeued|removed (aspect "Queue"), error_set|error_cleared
 * (aspect "Error"; only the newest matters, older ones are pruned on commit), cancelled and
 * thread_truncated (aspect "Transcript"). A Snapshot of an aspect deletes that aspect's rows (I3).
 */
data class SequencedRow(val threadId: String, val seq: Long, val aspect: String, val json: String)

/** @Entity snapshot_row, PK (threadId, aspect). The latest Snapshot frame per aspect; replace on write (I3). */
data class SnapshotRow(val threadId: String, val aspect: String, val json: String)

/** @Entity resume_cursor. Written only inside commit(), in the same transaction as the rows it covers (I2). */
data class CursorRow(val threadId: String, val seq: Long, val firstLoadedOrdinal: Long, val hasMoreBefore: Boolean)

/** @Entity label_cache, project_cache, runner_cache, account_resource: snapshots for instant pickers and settings. */
data class CatalogRow(val kind: String, val json: String, val fetchedAtMs: Long)

/** Everything one ApplyPlan writes. commit() applies it in one transaction; replaying it is a no-op. */
data class CacheWrite(
    val messages: List<MessageRow>,
    val sequenced: List<SequencedRow>,
    val snapshots: List<SnapshotRow>,
    val cursorSeq: Long,
)

interface ThreadCache {
    suspend fun load(threadId: String, lastN: Int): CachedThread?
    /** Upserts rows, replaces snapshot aspects and prunes their sequenced rows, advances the cursor. One transaction. */
    suspend fun commit(threadId: String, write: CacheWrite)
    /** Older history pages (S9). Touches message_row and the cursor's paging fields, never cursor.seq. */
    suspend fun prepend(threadId: String, older: List<MessageRow>, firstLoadedOrdinal: Long, hasMoreBefore: Boolean)
    suspend fun evictOlderThan(days: Int)
}
data class CachedThread(val messages: List<MessageRow>, val sequenced: List<SequencedRow>, val snapshots: List<SnapshotRow>, val cursor: CursorRow)

// Changes cache (git-and-diffs.md "Local cache"): the first paint, never the final answer.

/** @Entity change_snapshot, PK (threadId, scope, diffHash). The last change set seen, with when it was captured. */
data class ChangeSnapshotRow(val threadId: String, val scope: String, val diffHash: String, val json: String, val capturedAtMs: Long)
/** @Entity blob, PK sha. File contents for client-side diffs, 64 MiB per account, least recently used out first. */
data class BlobRow(val sha: String, val bytes: ByteArray, val lastUsedMs: Long)
/** @Entity file_order, PK (threadId, sectionId, diffHash). The intelligent order, summary and code tour for one section. */
data class FileOrderRow(val threadId: String, val sectionId: String, val diffHash: String, val json: String)

// ── User-authored tables (real migrations) ───────────────────────────────────

/**
 * @Entity outbox. Every dispatched Command.Durable (thread intents and account commands) until it
 * settles. commandJson is written and read only by :sync's OutboxCodec, versioned; the delivery class
 * is part of the decoded type, so no column repeats it.
 * state: queued | sending | waiting_network | rejected | unknown | dropped.
 */
data class OutboxRow(val intentId: String, val threadId: String?, val commandJson: String, val state: String, val attempts: Int, val createdAtMs: Long)
interface Outbox {
    fun observe(threadId: String): Flow<List<OutboxRow>>
    suspend fun enqueue(row: OutboxRow)
    suspend fun mark(intentId: String, state: String)
    /** Called after commit when message_added or queued_message_added echoes a client-minted messageId (I7). */
    suspend fun acknowledge(messageIds: List<String>)
    suspend fun pendingAcrossThreads(): List<OutboxRow>   // WorkManager drain at app start and on connectivity
}

/** @Entity watch_list: threads the user chose to Watch (notifications.md). Read by KeepAlivePolicy. */
data class WatchRow(val threadId: String, val since: Long)

/** @Entity draft. Also mirrored to user actor setDraft (cli) so drafts follow the user across clients. */
data class DraftRow(val threadId: String, val text: String, val attachmentsJson: String, val updatedAtMs: Long)
interface Drafts { fun observe(threadId: String): Flow<DraftRow?>; suspend fun save(row: DraftRow) }

/** @Entity comment_draft, PK (threadId, anchor). An unsent diff or message comment survives process death. */
data class CommentDraftRow(val threadId: String, val anchor: String, val text: String, val updatedAtMs: Long)

/** @Entity snooze_ledger: snoozes this device set, plus reconciled server results. */
data class SnoozeRow(val threadId: String, val untilKind: String, val untilMs: Long?, val source: String, val setAtMs: Long)

/** @Entity push_subscription: which threads have a live Web Push upsert for which endpoint. */
data class PushSubRow(val threadId: String, val endpointHash: String, val upsertedAtMs: Long)

/**
 * @Entity notification_ledger, PK tag. Tag = server tag from the push payload
 * (`amp-thread-<id>-idle-<messageId>`, spike S11) or the same shape synthesized by watch/poll
 * paths, so any two delivery paths post a given event once. The approval_required tag shape is
 * unrecorded (S15 INCONCLUSIVE); until a capture shows it, approvals claim `amp-thread-<id>-approval-<toolCallId>`
 * and plugin dialogs `amp-thread-<id>-plugin-<requestId>`
 * on every path, and the Android notification tag is per thread, so a mismatch replaces, never stacks.
 */
data class LedgerRow(val tag: String, val threadId: String, val kind: String, val postedAtMs: Long, val state: String /* posted|answered|dismissed */)
interface NotificationLedger {
    /** Returns true exactly once per tag (INSERT OR IGNORE). */
    suspend fun claim(row: LedgerRow): Boolean
    suspend fun resolve(tag: String, state: String)
}

// ── DataStore, one file per proto type ───────────────────────────────────────

/** `roster.pb`. Roster without secrets: id, email, baseUrl, workspace, last-used account, push instance name. */
data class RosterProto(val accounts: List<AccountEntry>, val lastUsedId: String?)
data class AccountEntry(val id: String, val email: String, val baseUrl: String, val workspaceJson: String, val pushInstance: String)

/**
 * `workspaces.pb`. Saved workspace per window, keyed by `windowKey` = taskId for multi-instance +
 * display kind, so the cover, the inner display and each DeX window restore their own arrangement.
 * Each entry carries the window's account.
 */
data class WorkspacesProto(val windows: List<WorkspaceEntry>)
data class WorkspaceEntry(val windowKey: String, val accountId: String, val arrangement: String, val stacksJson: String, val dividerOffsetsDp: List<Float>)

// ── Hilt providers (SingletonComponent, StoreModule) ─────────────────────────

/** One Room instance per account file, shared by :sync and :notify, so both see the same invalidations. */
interface AccountDatabases {
    fun forAccount(accountId: String): AccountDatabase
    suspend fun delete(accountId: String)
}
interface AccountDatabase {
    val threadCache: ThreadCache
    val outbox: Outbox
    val drafts: Drafts
    val ledger: NotificationLedger
}
/** One DataStore instance per file. Two instances on one file would throw, so only this provider opens them. */
interface AppPreferences {
    val roster: androidx.datastore.core.DataStore<RosterProto>
    val workspaces: androidx.datastore.core.DataStore<WorkspacesProto>
}
