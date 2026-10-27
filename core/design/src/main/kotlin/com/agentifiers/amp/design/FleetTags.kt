package com.agentifiers.amp.design

import com.agentifiers.amp.model.LabelName
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadProject

/** The Sidebar, its Needs you section, Activity, Running, and the shared organize dialogs. */
object FleetTags {
    const val accountSwitcher = "account-switcher"
    const val newThread = "new-thread"
    const val search = "thread-search"
    const val searchClear = "thread-search-clear"
    const val palette = "sidebar-palette"
    const val collapse = "sidebar-collapse"
    const val refresh = "sidebar-refresh"
    const val notificationsOff = "sidebar-notifications-off"
    const val notificationsTurnOn = "sidebar-notifications-turn-on"
    const val notificationsDismiss = "sidebar-notifications-dismiss"
    const val retry = "sidebar-retry"
    const val loading = "sidebar-loading"
    const val empty = "sidebar-empty"
    const val emptyNewThread = "sidebar-empty-new-thread"
    const val searchEmpty = "thread-search-empty"

    /** The new-thread composer's project picker; the executor and mode pickers keep their ThreadTags composer tags. */
    const val newThreadProject = "composer-project"
    fun newThreadProjectOption(id: ProjectId?) = "composer-project-${id?.raw ?: "none"}"

    const val sectionNeedsYou = "sidebar-section-needs-you"
    const val sectionPinned = "sidebar-section-pinned"
    const val sectionInactive = "sidebar-section-inactive"
    const val sectionSnoozed = "sidebar-section-snoozed"
    const val sectionArchived = "sidebar-section-archived"
    fun sectionProject(id: ProjectId) = "sidebar-section-project-${id.raw}"
    const val sectionProjectNone = "sidebar-section-project-none"
    const val sectionInactiveToggle = "sidebar-section-inactive-toggle"
    const val sectionSnoozedToggle = "sidebar-section-snoozed-toggle"
    const val sectionArchivedToggle = "sidebar-section-archived-toggle"
    const val archiveInactive = "inactive-archive-all"
    const val archiveInactiveConfirm = "inactive-archive-all-confirm"

    fun searchChip(index: Int) = "thread-search-chip-$index"
    const val snackbar = "sidebar-snackbar"
    const val snackbarUndo = "sidebar-snackbar-undo"

    const val rail = "sidebar-rail"
    fun railThread(id: ThreadId) = "rail-thread-${id.raw}"

    const val list = "thread-list"
    fun row(id: ThreadId) = "thread-row-${id.raw}"
    fun rowMore(id: ThreadId) = "thread-row-${id.raw}-more"
    fun rowChildren(id: ThreadId) = "thread-row-${id.raw}-children"
    fun rowProject(id: ThreadId) = "thread-row-${id.raw}-project"

    /** The row's color stripe, present only when the thread has a color, so a flow can assert the color by id. */
    fun rowStripe(id: ThreadId, color: String) = "thread-row-${id.raw}-stripe-$color"

    /** The row's status word, so a flow can assert the status by id. */
    fun rowStatus(id: ThreadId, word: String) = "thread-row-${id.raw}-status-${word.replace(' ', '-')}"

    /** What a waiting row waits on: the tool and its input, or the gate's title. */
    fun rowSummary(id: ThreadId) = "thread-row-${id.raw}-summary"
    fun rowAction(id: ThreadId, action: RowAction) = "thread-row-${id.raw}-${action.slug}"

    enum class RowAction(val slug: String) {
        Pin("pin"),
        Color("color"),
        Labels("labels"),
        Rename("rename"),
        Snooze("snooze"),
        Archive("archive"),
        ArchiveRemoveWorktree("archive-remove-worktree"),
        Delete("delete"),
        MarkUnread("mark-unread"),
        NewWindow("new-window"),
    }

    fun needsYou(id: ThreadId, action: NeedsYouAction) = "needs-you-${id.raw}-${action.slug}"

    enum class NeedsYouAction(val slug: String) {
        Allow("allow"),
        Deny("deny"),
        Review("review"),
        Answer("answer"),
        Open("open"),
        Read("read"),
    }

    /** The heading of a picker section, from the section's own tag. */
    fun sectionHeading(sectionTag: String) = "$sectionTag-heading"
    const val newThreadHeader = "new-thread-header"
    const val runningHeader = "running-header"
    const val activityHeader = "activity-header"
    const val activityBack = "activity-back"
    const val activityTime24h = "activity-time-24h"
    const val activityTime72h = "activity-time-72h"
    const val activityTime7d = "activity-time-7d"
    const val activityTimeAll = "activity-time-all"
    const val activityStatus = "activity-status"
    const val activityUsers = "activity-users"
    const val activityProjects = "activity-projects"
    const val activityProjectsAll = "activity-projects-all"
    const val activityQuery = "activity-query"
    const val activitySearchWeb = "activity-search-web"
    const val activityTimeMore = "activity-time-more"
    const val activityStatusMore = "activity-status-more"
    const val activityUsersMore = "activity-users-more"
    const val activityProjectsMore = "activity-projects-more"
    fun activityRow(id: ThreadId) = "activity-row-${id.raw}"
    fun activityStatusOption(option: ActivityStatus) = "activity-status-${option.slug}"
    fun activityUsersOption(option: ActivityUsers) = "activity-users-${option.slug}"
    fun activityProjectOption(project: ThreadProject) = when (project) {
        ThreadProject.None -> "activity-project-none"
        is ThreadProject.Linked -> "activity-project-${project.id.raw}"
    }
    fun activityUseDefault(chip: ActivityChip) = "activity-${chip.slug}-use-default"
    fun activityResetDefault(chip: ActivityChip) = "activity-${chip.slug}-reset-default"

    enum class ActivityChip(val slug: String) { Time("time"), Status("status"), Users("users"), Projects("projects") }

    enum class ActivityStatus(val slug: String) {
        All("all"),
        Working("working"),
        NeedsYou("needs-you"),
        Done("done"),
        Archived("archived"),
    }

    enum class ActivityUsers(val slug: String) { All("all"), Me("me") }

    fun runningRow(id: ThreadId) = "running-row-${id.raw}"

    const val renameField = "organize-rename-field"
    const val renameSave = "organize-rename-save"
    fun color(color: ThreadColor) = "organize-color-${color.name.lowercase()}"
    const val labelsField = "organize-labels-field"
    const val labelsAdd = "organize-labels-add"
    const val labelsSave = "organize-labels-save"
    fun labelRemove(label: LabelName) = "organize-label-${label.raw}-remove"
    fun labelSuggestion(label: LabelName) = "organize-label-${label.raw}-suggestion"
    fun snooze(option: SnoozeOption) = "organize-snooze-${option.slug}"
    const val deleteConfirm = "organize-delete-confirm"
    const val organizeCancel = "organize-cancel"

    enum class SnoozeOption(val slug: String) {
        OneHour("one-hour"),
        NextReply("next-reply"),
        EightAm("8am"),
        Monday("monday"),
        Indefinitely("indefinitely"),
        Off("off"),
    }
}
