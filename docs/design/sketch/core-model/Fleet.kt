// :core:model  package amp.model
// Fleet: all threads for one account. The sidebar is a pure function of summaries + local ledgers;
// the web-remote sidebar groups (listThreadListSidebar, listSidebar*) are never called.
package amp.model

import kotlinx.collections.immutable.PersistentList
import kotlin.time.Instant

/**
 * Merged from listThreads (S2) and user-actor getRecentThreads / threadStatusUpdated (S2, S14).
 * Both sources write disjoint fields into per-source records; Summary is merged at read.
 */
data class ThreadSummary(
    val id: ThreadId,
    val title: ThreadTitle,
    val status: FleetStatus,
    val unread: Unread,
    val pinned: Boolean,
    val archived: Boolean,
    val snooze: SnoozeState,
    val color: ThreadColor,
    val labels: PersistentList<LabelName>,
    val project: ProjectRef,
    val executor: ExecutorBadge,
    val creator: UserId,
    val lastActivity: Instant,
    val parent: ThreadId?,              // agent-to-agent children; null for roots
    val isPuck: Boolean,
)

/** From threadStatusUpdated.state + indicator (S14, S15c) or agent_state when attached. */
sealed interface FleetStatus {
    data object Idle : FleetStatus
    data object Working : FleetStatus
    /**
     * threadStatusUpdated.indicator = {kind:"action-required", description, observedAt}; null again once
     * answered. Measured for a pending plugin dialog, with and without an observer connected
     * (S15b :65, S15c :66). That tool approvals set the same indicator is [INFERENCE]: S15 queued none.
     */
    data class NeedsHuman(val description: String, val since: kotlin.time.Instant) : FleetStatus
    data class Errored(val message: String) : FleetStatus
    data object Asleep : FleetStatus
}
sealed interface Unread {
    data object None : Unread
    data class Replies(val count: Int) : Unread
    data class Mention(val count: Int) : Unread
}
sealed interface ExecutorBadge {
    data object None : ExecutorBadge
    data class Orb(val size: OrbSize, val connected: Boolean) : ExecutorBadge
    data class Runner(val hostname: String, val connected: Boolean) : ExecutorBadge
}

/** Sidebar order from core.md:191-194. Derived, never stored. */
data class Sidebar(
    val pinned: PersistentList<ThreadSummary>,
    val active: PersistentList<ProjectGroup>,
    val inactive72h: Collapsible,
    val snoozed: Collapsible,
    val archived: Collapsible,
    val freshness: Freshness,
)
data class ProjectGroup(val project: ProjectRef, val threads: PersistentList<ThreadSummary>)
data class Collapsible(val count: Int, val threads: PersistentList<ThreadSummary>, val expanded: Boolean)
sealed interface Freshness {
    data object Live : Freshness
    data class Cached(val asOf: Instant) : Freshness
}

/** Thread query syntax (core.md:186). Parsed natively; sent to /api/threads/find?q= (cli). */
data class ThreadQuery(val text: String, val filters: PersistentList<QueryFilter>, val window: TimeWindow) {
    companion object { fun parse(raw: String): ThreadQuery = TODO("id: label: parent: project: repo: author: visibility: archived: snoozed: pinned: commit: pr: after: before: updated_after: updated_before:") }
    fun render(): String = TODO()
}
sealed interface QueryFilter {
    data class Key(val key: String, val value: String, val negated: Boolean) : QueryFilter
}
enum class TimeWindow { H24, H72, D7, All }

/** Activity feed row: listFeedItems + feedItemUpdated (user actor, cli). */
sealed interface ActivityItem {
    val at: Instant
    val thread: ThreadId
    data class TurnCompleted(override val at: Instant, override val thread: ThreadId, val unreadReplies: Int) : ActivityItem
    data class Mentioned(override val at: Instant, override val thread: ThreadId, val by: UserId) : ActivityItem
    data class Followed(override val at: Instant, override val thread: ThreadId, val summary: String) : ActivityItem
    data class CommentReply(override val at: Instant, override val thread: ThreadId, val count: Int) : ActivityItem
    data class SpaceInvite(override val at: Instant, override val thread: ThreadId, val from: UserId) : ActivityItem
}

/** New-thread spec. Executor is a sum type so an orb spec can't carry a runner directory. */
data class NewThreadSpec(
    val firstMessage: Draft,
    val mode: AgentMode,
    val executor: ExecutorChoice,
    val labels: PersistentList<LabelName>,
    val visibility: Visibility,
)
sealed interface ExecutorChoice {
    data object NoMachine : ExecutorChoice
    data class Orb(val project: ProjectId, val size: OrbSize, val provider: OrbProvider) : ExecutorChoice
    data class Runner(val runner: RunnerId, val directory: String, val worktree: WorktreeChoice, val project: ProjectId?) : ExecutorChoice
}
sealed interface WorktreeChoice { data object InPlace : WorktreeChoice; data class NewWorktree(val branch: String) : WorktreeChoice }

data class RunnerInfo(val id: RunnerId, val hostname: String, val directories: PersistentList<String>, val shared: Boolean, val online: Boolean, val modes: PersistentList<AgentMode>, val canCreateWorktree: Boolean)
data class ProjectInfo(val id: ProjectId, val name: String, val repos: PersistentList<RepoRef>, val shipBehavior: ShipBehavior, val gitHost: GitHost)
enum class GitHost { GitHub, GitLab, Bitbucket, OtherHttps, AmpHosted }
