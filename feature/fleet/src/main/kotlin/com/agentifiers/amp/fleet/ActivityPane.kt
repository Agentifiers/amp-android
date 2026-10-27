package com.agentifiers.amp.fleet

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.components.AmpSearchField
import com.agentifiers.amp.design.components.AmpDropdownMenu
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.FleetTags.ActivityChip
import com.agentifiers.amp.design.FleetTags.ActivityStatus
import com.agentifiers.amp.design.FleetTags.ActivityUsers
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpPaneHeader
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.ActivityItem
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadProject
import com.agentifiers.amp.model.ThreadQuery
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.TimeWindow
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.ActivityPage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import java.io.Serializable

/** Activity as its own pane. Inside the Sidebar the same feed renders with a Back button instead. */
@Composable
fun ActivityPane(dest: PaneDestination.Activity, scope: AccountScope, navigate: (PaneDestination) -> Unit, modifier: Modifier = Modifier) {
    ActivityContent(
        scope = scope,
        navigate = navigate,
        initialText = dest.query.render(),
        onBack = null,
        modifier = modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane(dest.route)).padding(horizontal = AmpTheme.spacing.listInset),
    )
}

/**
 * Fades [width] at each edge [scroll] can still move toward, so a chip cut by the pane edge reads as more to scroll to.
 * DstIn keeps only the mask's alpha, so any opaque [opaque] color works.
 */
private fun Modifier.fadeScrolledEdges(scroll: ScrollState, width: Dp, opaque: Color) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx()
        if (scroll.canScrollBackward) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, opaque), startX = 0f, endX = fade), blendMode = BlendMode.DstIn)
        }
        if (scroll.canScrollForward) {
            drawRect(Brush.horizontalGradient(listOf(opaque, Color.Transparent), startX = size.width - fade, endX = size.width), blendMode = BlendMode.DstIn)
        }
    }

/** The chip filters, minus the free text. Serializable so it survives configuration changes. */
private data class ActivityFilters(
    val window: TimeWindow = TimeWindow.H24,
    val status: ActivityStatus = ActivityStatus.All,
    val users: ActivityUsers = ActivityUsers.All,
    /** [projectKey]s; empty means every project. */
    val projects: Set<String> = emptySet(),
) : Serializable {
    fun query(text: String): ThreadQuery = ThreadQuery.parse(
        listOfNotNull(
            text.ifBlank { null },
            "author:me".takeIf { users == ActivityUsers.Me },
            "archived:true".takeIf { status == ActivityStatus.Archived },
        ).joinToString(" "),
        window,
    )

    fun admits(summary: ThreadSummary): Boolean = status.admits(summary) && (projects.isEmpty() || summary.project.key in projects)

    /** This filter with [chip] set back to the built-in default. */
    fun reset(chip: ActivityChip): ActivityFilters {
        val builtIn = ActivityFilters()
        return when (chip) {
            ActivityChip.Time -> copy(window = builtIn.window)
            ActivityChip.Status -> copy(status = builtIn.status)
            ActivityChip.Users -> copy(users = builtIn.users)
            ActivityChip.Projects -> copy(projects = builtIn.projects)
        }
    }
}

private fun ActivityStatus.admits(summary: ThreadSummary): Boolean = when (this) {
    ActivityStatus.All -> true
    ActivityStatus.Working -> summary.status == FleetStatus.Working || summary.status == FleetStatus.Compacting
    ActivityStatus.NeedsYou -> summary.status is FleetStatus.Waiting || summary.status == FleetStatus.Error || summary.status == FleetStatus.Done
    ActivityStatus.Done -> summary.status == FleetStatus.Done
    ActivityStatus.Archived -> summary.archived
}

private val ActivityStatus.label: String
    get() = when (this) {
        ActivityStatus.All -> "All"
        ActivityStatus.Working -> "Working"
        ActivityStatus.NeedsYou -> "Needs you"
        ActivityStatus.Done -> "Done"
        ActivityStatus.Archived -> "Archived"
    }

private val ActivityUsers.label: String
    get() = when (this) {
        ActivityUsers.All -> "All"
        ActivityUsers.Me -> "Me"
    }

private val ThreadProject.key: String
    get() = when (this) {
        ThreadProject.None -> "none"
        is ThreadProject.Linked -> "project:${id.raw}"
    }

/** Chip defaults per account, in the SharedPreferences file `activity-defaults-<accountId>`. */
private class ActivityDefaults(context: Context, account: AccountId) {
    private val prefs = context.getSharedPreferences("activity-defaults-${account.raw}", Context.MODE_PRIVATE)

    fun load(): ActivityFilters {
        val builtIn = ActivityFilters()
        return ActivityFilters(
            window = prefs.getString(ActivityChip.Time.slug, null)?.let { name -> TimeWindow.entries.firstOrNull { it.name == name } } ?: builtIn.window,
            status = prefs.getString(ActivityChip.Status.slug, null)?.let { name -> ActivityStatus.entries.firstOrNull { it.name == name } } ?: builtIn.status,
            users = prefs.getString(ActivityChip.Users.slug, null)?.let { name -> ActivityUsers.entries.firstOrNull { it.name == name } } ?: builtIn.users,
            projects = prefs.getStringSet(ActivityChip.Projects.slug, null)?.toSet() ?: builtIn.projects,
        )
    }

    fun use(chip: ActivityChip, filters: ActivityFilters) {
        val edit = prefs.edit()
        when (chip) {
            ActivityChip.Time -> edit.putString(chip.slug, filters.window.name)
            ActivityChip.Status -> edit.putString(chip.slug, filters.status.name)
            ActivityChip.Users -> edit.putString(chip.slug, filters.users.name)
            ActivityChip.Projects -> edit.putStringSet(chip.slug, filters.projects)
        }
        edit.apply()
    }

    fun reset(chip: ActivityChip) {
        prefs.edit().remove(chip.slug).apply()
    }
}

/** The Activity feed: one scrolling row of filter chips over the local feed, a query field, and Search on web. */
@Composable
internal fun ActivityContent(
    scope: AccountScope,
    navigate: (PaneDestination) -> Unit,
    initialText: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val account by scope.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaults = remember(account.id) { ActivityDefaults(context.applicationContext, account.id) }
    var filters by rememberSaveable(account.id.raw) { mutableStateOf(defaults.load()) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    val query = remember(text, filters) { filters.query(text) }
    val page by remember(scope, query) { scope.fleet.activity(query) }.collectAsStateWithLifecycle()
    val summaries by remember(scope, query) { scope.fleet.search(query) }.collectAsStateWithLifecycle<PersistentList<ThreadSummary>>(persistentListOf())
    val byId = remember(summaries) { summaries.associateBy { it.id } }
    val seenProjects = remember(summaries) { summaries.map { it.project }.distinct() }
    val fleet by scope.fleet.threads.collectAsStateWithLifecycle()
    val usual = remember(fleet) { (fleet as? FleetView.Ready)?.threads?.let(::usualExecutor) }
    val now = rememberNow()
    val colors = AmpTheme.colors
    val spacing = AmpTheme.spacing
    val setFilters: (ActivityFilters) -> Unit = { filters = it }

    // The header and rows span the list inset; the controls between them sit on the pane's text edge, like the Sidebar's.
    val controlsX = spacing.paneX - spacing.listInset
    Column(modifier) {
        AmpPaneHeader(
            title = "Activity",
            testTag = FleetTags.activityHeader,
            kind = "activity",
            navigation = onBack?.let { back -> { AmpIconButton(AmpIcons.ArrowLeft, "Back", back, FleetTags.activityBack) } },
        )
        val chipScroll = rememberScrollState()
        Row(
            Modifier.fillMaxWidth().fadeScrolledEdges(chipScroll, spacing.scrollFade, LocalPaneSurface.current).horizontalScroll(chipScroll).padding(horizontal = controlsX),
            horizontalArrangement = Arrangement.spacedBy(spacing.actionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterMenuChip(ActivityChip.Time, filters.window.label, filters, defaults, setFilters) { close ->
                TimeWindow.entries.forEach { window ->
                    CheckItem(window.label, filters.window == window, window.tag) { filters = filters.copy(window = window); close() }
                }
            }
            FilterMenuChip(ActivityChip.Status, "Status: ${filters.status.label}", filters, defaults, setFilters) { close ->
                ActivityStatus.entries.forEach { option ->
                    CheckItem(option.label, filters.status == option, FleetTags.activityStatusOption(option)) { filters = filters.copy(status = option); close() }
                }
            }
            FilterMenuChip(ActivityChip.Users, "Users: ${filters.users.label}", filters, defaults, setFilters) { close ->
                ActivityUsers.entries.forEach { option ->
                    CheckItem(option.label, filters.users == option, FleetTags.activityUsersOption(option)) { filters = filters.copy(users = option); close() }
                }
            }
            val projectsLabel = if (filters.projects.isEmpty()) "Projects: All" else "Projects: ${filters.projects.size}"
            FilterMenuChip(ActivityChip.Projects, projectsLabel, filters, defaults, setFilters) { close ->
                CheckItem("All", filters.projects.isEmpty(), FleetTags.activityProjectsAll) { filters = filters.copy(projects = emptySet()); close() }
                seenProjects.forEach { project ->
                    val chosen = project.key in filters.projects
                    CheckItem(project.displayName, chosen, FleetTags.activityProjectOption(project)) {
                        filters = filters.copy(projects = if (chosen) filters.projects - project.key else filters.projects + project.key)
                    }
                }
            }
        }
        AmpSearchField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Search or filter, e.g. label:bug",
            testTag = FleetTags.activityQuery,
            modifier = Modifier.padding(horizontal = controlsX, vertical = spacing.searchMarginY),
        )

        when (val current = page) {
            ActivityPage.Loading -> Text("Loading activity…", style = AmpTheme.type.body, color = colors.text.inkMuted, modifier = Modifier.padding(horizontal = controlsX, vertical = spacing.sm))
            is ActivityPage.Ready -> {
                if (!current.serverFiltered) {
                    Row(Modifier.padding(start = controlsX), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Filtered on this device. Results include only threads this device has seen.",
                            style = AmpTheme.type.secondary,
                            color = colors.text.inkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        AmpIconButton(
                            AmpIcons.ExternalLink,
                            "Search on web",
                            onClick = {
                                navigate(PaneDestination.Web(WebSurface.AccountPage(AccountWebPage.ThreadOnWeb, account.id, "?q=" + Uri.encode(query.render()))))
                            },
                            testTag = FleetTags.activitySearchWeb,
                        )
                    }
                }
                val rows = current.items.distinctBy { it.thread }.mapNotNull { item -> byId[item.thread]?.takeIf(filters::admits)?.let { item to it } }
                if (rows.isEmpty()) Text("No activity matches.", style = AmpTheme.type.body, color = colors.text.inkMuted, modifier = Modifier.padding(horizontal = controlsX, vertical = spacing.sm))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { (item, _) -> item.thread.raw }) { (item, summary) ->
                        ThreadListRow(
                            summary = summary,
                            now = now,
                            onClick = { navigate(PaneDestination.Thread(summary.id)) },
                            testTag = FleetTags.activityRow(summary.id),
                            usualExecutor = usual,
                            note = item.reason,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A filter chip whose menu holds its [options], then Use as Default, which stores the chip's current value for this
 * account, and Reset Default, which clears it. [options] gets a callback that closes the menu.
 */
@Composable
private fun FilterMenuChip(
    chip: ActivityChip,
    label: String,
    filters: ActivityFilters,
    defaults: ActivityDefaults,
    onFilters: (ActivityFilters) -> Unit,
    options: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    Box {
        AmpChip(
            label = label,
            selected = filters.reset(chip) != filters,
            onClick = { open = true },
            testTag = chip.tag,
            trailingIcon = AmpIcons.ChevronDown,
        )
        AmpDropdownMenu(expanded = open, onDismissRequest = close) {
            options(close)
            HorizontalDivider(color = AmpTheme.colors.surfaces.line)
            DropdownMenuItem(
                text = { Text("Use as Default") },
                onClick = { defaults.use(chip, filters); close() },
                modifier = Modifier.testTag(FleetTags.activityUseDefault(chip)),
            )
            DropdownMenuItem(
                text = { Text("Reset Default") },
                onClick = { defaults.reset(chip); onFilters(filters.reset(chip)); close() },
                modifier = Modifier.testTag(FleetTags.activityResetDefault(chip)),
            )
        }
    }
}

@Composable
private fun CheckItem(text: String, checked: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text((if (checked) "✓ " else "") + text) }, onClick = onClick, modifier = Modifier.testTag(tag))
}

/** The Time chip has no tag of its own; it reuses the one its retired overflow button had. */
private val ActivityChip.tag: String
    get() = when (this) {
        ActivityChip.Time -> FleetTags.activityTimeMore
        ActivityChip.Status -> FleetTags.activityStatus
        ActivityChip.Users -> FleetTags.activityUsers
        ActivityChip.Projects -> FleetTags.activityProjects
    }

private val TimeWindow.label: String
    get() = when (this) {
        TimeWindow.H24 -> "Last 24h"
        TimeWindow.H72 -> "Last 72h"
        TimeWindow.D7 -> "Last 7d"
        TimeWindow.All -> "All time"
    }

private val TimeWindow.tag: String
    get() = when (this) {
        TimeWindow.H24 -> FleetTags.activityTime24h
        TimeWindow.H72 -> FleetTags.activityTime72h
        TimeWindow.D7 -> FleetTags.activityTime7d
        TimeWindow.All -> FleetTags.activityTimeAll
    }

/** Null for an update with no further detail, which would only repeat what the row's time already says. */
private val ActivityItem.reason: String?
    get() = when (this) {
        is ActivityItem.TurnCompleted -> if (unreadReplies > 0) "Turn completed, $unreadReplies unread" else "Turn completed"
        is ActivityItem.Mentioned -> "Mentioned"
        is ActivityItem.Followed -> "Followed: $summary"
        is ActivityItem.CommentReply -> if (count == 1) "1 comment reply" else "$count comment replies"
        is ActivityItem.SpaceInvite -> "Space invitation"
        is ActivityItem.Updated -> null
    }
