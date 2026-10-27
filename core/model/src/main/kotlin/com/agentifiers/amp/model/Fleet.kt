package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.time.Instant

/**
 * One Sidebar row, merged from `listThreads`, the user actor's `getRecentThreads`, and this device's organize
 * ledger (snooze and color have no read path, S10). The organize fields default so slice-1 callers still compile.
 */
data class ThreadSummary(
    val id: ThreadId,
    val title: String?,
    val status: FleetStatus,
    val executor: String?,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val snooze: SnoozeState = SnoozeState.NotSnoozed,
    val color: ThreadColor = ThreadColor.None,
    val labels: PersistentList<LabelName> = persistentListOf(),
    val project: ThreadProject = ThreadProject.None,
    val visibility: Visibility? = null,
    /** `userLastInteractedAt`, else `created`, from `listThreads`. */
    val lastActivity: Instant? = null,
    val parent: ThreadId? = null,
    /** `getRecentThreads.hasUnreadMessages`. */
    val unread: Boolean = false,
    val isPuck: Boolean = false,
    val creator: UserId? = null,
    /** `latestAssistantMessageID` from the user actor: the id Amp's `thread_idle` push tag names for the last turn (S11, S14). */
    val lastReply: MessageId? = null,
    /** `lastTurnEndedAt` from the user actor. */
    val lastTurnEndedAt: Instant? = null,
)

/** The project a thread belongs to, from `getThreadProject`. Threads without one group under No Project. */
sealed interface ThreadProject {
    data object None : ThreadProject
    data class Linked(val id: ProjectId, val name: String) : ThreadProject
}

/** The status word a Sidebar row shows. Status is always text, never color alone. */
sealed interface FleetStatus {
    val word: String

    /** A gate waits on the user, so the thread belongs in Needs you whatever the agent is doing. */
    sealed interface Waiting : FleetStatus {
        /** What waits, such as "Bash: rm -rf build" or a dialog's title. */
        val summary: String
    }

    /** A tool approval is pending in the open thread; the row answers it with [gate]. */
    data class NeedsApproval(val gate: ToolApprovalGate) : Waiting {
        override val word = "needs approval"
        override val summary get() = gate.waitingOn
    }

    /**
     * Any other gate: from the open thread's gate list, or from `getRecentThreads.indicator{kind:"action-required",
     * description}` (S15b, S15c) when no socket is open.
     */
    data class NeedsInput(val description: String) : Waiting {
        override val word = "needs input"
        override val summary get() = description
    }

    data object Working : FleetStatus { override val word = "working" }
    data object Compacting : FleetStatus { override val word = "compacting" }
    data object Done : FleetStatus { override val word = "done" }
    data object Idle : FleetStatus { override val word = "idle" }
    data object Error : FleetStatus { override val word = "error" }
    /** Not in the user actor's recent threads, so no live state is known. */
    data object Inactive : FleetStatus { override val word = "inactive" }
    data class Reported(val state: String) : FleetStatus { override val word = state }
}

sealed interface FleetView {
    data object Loading : FleetView
    data class Failed(val reason: String) : FleetView
    data class Ready(val threads: PersistentList<ThreadSummary>, val refreshError: String?) : FleetView
}

/**
 * The status of a thread whose state this device holds, gates first (screens.md "Status text"): a pending tool
 * approval, then any other pending gate, then the agent's activity. [unread] makes idle "done". Null when no gate
 * waits and the activity is unknown.
 */
fun ThreadState.fleetStatus(unread: Boolean): FleetStatus? {
    gates.firstNotNullOfOrNull { it as? ToolApprovalGate }?.let { return FleetStatus.NeedsApproval(it) }
    gates.firstOrNull()?.let { return FleetStatus.NeedsInput(it.waitingOn) }
    return when (activity) {
        AgentActivity.Unknown -> null
        AgentActivity.Idle -> if (unread) FleetStatus.Done else FleetStatus.Idle
        AgentActivity.Failed -> FleetStatus.Error
        is AgentActivity.Reported -> FleetStatus.Reported(activity.state)
        AgentActivity.Compacting -> FleetStatus.Compacting
        AgentActivity.Working, AgentActivity.Streaming, AgentActivity.ComposingToolCall, AgentActivity.RunningTools -> FleetStatus.Working
    }
}

private val HumanGate.waitingOn: String
    get() = when (this) {
        is ToolApprovalGate -> "${tool.displayName()}: $summary"
        is ChoiceGate -> questions.firstOrNull()?.prompt ?: "Pick an answer"
        is PluginGate.Confirm -> title
        is PluginGate.Input -> title
        is PluginGate.SecretInput -> title
        is PluginGate.Select -> title
        is ElicitationGate.Form -> message
        is ElicitationGate.OpenUrl -> message
        is McpServerApprovalGate -> "Allow MCP server $serverName"
        is UnsupportedGate -> kindLabel
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

/**
 * Sidebar order from screens.md "Sections and order". Derived from summaries and the organize ledger, never
 * stored. Each thread sits in exactly one section, so `thread-row-<id>` stays unique on screen.
 */
data class Sidebar(
    /** Waiting on you, then stopped, then finished (screens.md "Needs you"). */
    val needsYou: PersistentList<ThreadSummary>,
    val pinned: PersistentList<ThreadSummary>,
    val active: PersistentList<ProjectGroup>,
    val inactive72h: PersistentList<ThreadSummary>,
    val snoozed: PersistentList<ThreadSummary>,
    val archived: PersistentList<ThreadSummary>,
    val freshness: Freshness,
)

/**
 * Loaded, with no thread in any section. The Sidebar and the empty Thread pane both say "No threads yet" then (UX-08).
 * It reads only this Sidebar, so the two panes never disagree for a frame while the fleet's flows catch up.
 */
val Sidebar.noThreadsYet: Boolean
    get() = freshness != Freshness.NotLoaded && needsYou.isEmpty() && pinned.isEmpty() && active.all { it.threads.isEmpty() } &&
        inactive72h.isEmpty() && snoozed.isEmpty() && archived.isEmpty()

data class ProjectGroup(val project: ThreadProject, val threads: PersistentList<ThreadSummary>)

sealed interface Freshness {
    /** No read has answered yet, so there is no time to show the rows as of. */
    data object NotLoaded : Freshness
    data object Live : Freshness
    data class Cached(val asOf: Instant) : Freshness
}

/**
 * Thread query syntax (core.md:186): free text plus `key:value` filters, `-key:value` negated. Parsed
 * natively and sent to /api/threads/find?q=. [window] comes from the Activity time chips, not the text.
 */
data class ThreadQuery(val text: String, val filters: PersistentList<QueryFilter>, val window: TimeWindow) {
    fun render(): String = (filters.map { it.render() } + listOfNotNull(text.ifBlank { null })).joinToString(" ")

    companion object {
        val KEYS = setOf(
            "id", "label", "parent", "project", "repo", "author", "visibility", "archived", "snoozed", "pinned",
            "commit", "pr", "after", "before", "updated_after", "updated_before", "puck",
        )

        /** Tokens whose key is not in [KEYS] stay in the free text, so `http://x` is never a filter. */
        fun parse(raw: String, window: TimeWindow = TimeWindow.All): ThreadQuery {
            val filters = mutableListOf<QueryFilter>()
            val text = mutableListOf<String>()
            raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { token ->
                val negated = token.startsWith("-")
                val body = token.removePrefix("-")
                val colon = body.indexOf(':')
                val key = if (colon > 0) body.substring(0, colon) else null
                val value = if (colon > 0) body.substring(colon + 1) else ""
                if (key in KEYS && value.isNotEmpty()) filters += QueryFilter.Key(key!!, value, negated) else text += token
            }
            return ThreadQuery(text.joinToString(" "), filters.toPersistentList(), window)
        }
    }
}

sealed interface QueryFilter {
    fun render(): String

    data class Key(val key: String, val value: String, val negated: Boolean) : QueryFilter {
        override fun render(): String = (if (negated) "-" else "") + "$key:$value"
    }
}

enum class TimeWindow { H24, H72, D7, All }

/** Activity feed row: listFeedItems + feedItemUpdated (user actor). */
sealed interface ActivityItem {
    val at: Instant
    val thread: ThreadId

    data class TurnCompleted(override val at: Instant, override val thread: ThreadId, val unreadReplies: Int) : ActivityItem
    data class Mentioned(override val at: Instant, override val thread: ThreadId, val by: UserId) : ActivityItem
    data class Followed(override val at: Instant, override val thread: ThreadId, val summary: String) : ActivityItem
    data class CommentReply(override val at: Instant, override val thread: ThreadId, val count: Int) : ActivityItem
    data class SpaceInvite(override val at: Instant, override val thread: ThreadId, val from: UserId) : ActivityItem
    /** Local mode: the thread changed at [at], and the device cannot know why (screens.md, "Activity and search"). */
    data class Updated(override val at: Instant, override val thread: ThreadId) : ActivityItem
}

/** New-thread spec. The executor is a sum type so an orb spec can't carry a runner directory. */
data class NewThreadSpec(
    val firstMessage: Draft,
    val mode: AgentMode,
    val executor: ExecutorChoice,
    val labels: PersistentList<LabelName>,
    val visibility: Visibility,
)

sealed interface ExecutorChoice {
    data object NoMachine : ExecutorChoice
    /** [project] null is **No Project**: a clean orb with no repo. */
    data class Orb(val project: ProjectId?, val size: OrbSize, val provider: OrbProvider) : ExecutorChoice
    data class Runner(val runner: RunnerId, val directory: String, val worktree: WorktreeChoice, val project: ProjectId?) : ExecutorChoice
}

sealed interface WorktreeChoice {
    data object InPlace : WorktreeChoice
    data class NewWorktree(val branch: String) : WorktreeChoice
}

data class RunnerInfo(
    val id: RunnerId,
    val hostname: String,
    val directories: PersistentList<String>,
    val shared: Boolean,
    val online: Boolean,
    val modes: PersistentList<AgentMode>,
    val canCreateWorktree: Boolean,
)

data class ProjectInfo(val id: ProjectId, val name: String, val repos: PersistentList<RepoRef>, val shipBehavior: ShipBehavior, val gitHost: GitHost)

enum class GitHost { GitHub, GitLab, Bitbucket, OtherHttps, AmpHosted }
