// :sync  package amp.sync.internal.fleet   (internal)
// Fleet state from two cli-tier sources with disjoint fields, merged at read:
//   ListSource  : internal listThreads✓{limit, offset, includeArchived} (S2): labels, archived, pinned, env, creator
//   LiveSource  : user actor getRecentThreads + threadStatusUpdated/feedItemUpdated (S2, S14): state, unread, executor
//   SnoozeLedger: local record + periodic `find?q=snoozed:true` reconciliation (listThreads lacks snooze, S10)
// None of them writes another's fields, so concurrent updates cannot clobber each other.
package amp.sync.internal.fleet

import amp.model.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlin.time.Instant

internal data class ListRecord(val id: ThreadId, val title: ThreadTitle, val pinned: Boolean, val archived: Boolean, val labels: PersistentList<LabelName>, val color: ThreadColor, val project: ProjectRef, val creator: UserId, val created: Instant, val lastInteracted: Instant, val parent: ThreadId?, val agentMode: String)
/** status = NeedsHuman while threadStatusUpdated.indicator.kind == "action-required" (S15c :66), else from state. */
internal data class LiveRecord(val id: ThreadId, val status: FleetStatus, val unread: Unread, val executor: ExecutorBadge, val lastTurnEndedAt: Instant?, val updatedAt: Instant)
internal data class SnoozeRecord(val id: ThreadId, val state: SnoozeState, val setAt: Instant)

/** Pure merge; the newest field owner wins only within its own source. */
internal fun mergeSummaries(
    list: PersistentMap<ThreadId, ListRecord>,
    live: PersistentMap<ThreadId, LiveRecord>,
    snoozes: PersistentMap<ThreadId, SnoozeRecord>,
): PersistentList<ThreadSummary> = TODO()

/**
 * Sidebar grouping (core.md:191-194), pure and snapshot-tested:
 * pinned (not archived) → active grouped by project, ordered by lastActivity → inactive if no
 * activity in 72 h → snoozed → archived. Puck conversations and agent children are excluded from
 * the root list (children render under their parent row).
 */
internal fun groupSidebar(summaries: PersistentList<ThreadSummary>, now: Instant, expanded: Set<String>): Sidebar = TODO()

/**
 * Activity: user-actor listFeedItems (cli, NOT VERIFIED) + feedItemUpdated (S14). Query filters go
 * to GET /api/threads/find?q= (cli, NOT VERIFIED). If either fails with Drift/NotFound, fall back to
 * evaluating supported filter keys locally over cached summaries and mark serverFiltered=false.
 */
internal interface ActivitySource { suspend fun page(query: ThreadQuery, cursor: String?): Pair<PersistentList<ActivityItem>, String?> }

/**
 * Push subscription upkeep. Desired set = threads not archived, not snoozed (approvals still notify:
 * the server wakes snooze for approvals, core.md:26), active in the last 72 h or pinned or working.
 * Upsert through LinkSupervisor.onSocket: the live socket when leased, else a one-shot socket that
 * resumes from threadVersion for a thread with no cache, so it replays nothing. /request/rpc returns
 * -32601 for client_upsert_notification_subscription (S18). The cost of a socket per thread is unmeasured. Ledger row per thread stores
 * endpoint hash + lastUpsertAt so reconciliation is a diff and every call is an idempotent upsert.
 * No unsubscribe exists (S11): stale subscriptions are dropped client-side by the ledger.
 */
internal interface PushUpkeep { suspend fun reconcile(endpointHash: String): Int }
