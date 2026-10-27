package com.agentifiers.amp.fleet

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.AmpWordmark
import com.agentifiers.amp.design.tagsAsResourceIds
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.FleetTags.NeedsYouAction
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpHeaderRow
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpSearchField
import com.agentifiers.amp.design.components.AmpSectionLabel
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.organize.OrganizeDialog
import com.agentifiers.amp.design.organize.OrganizeDialogHost
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.FleetStatus
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.model.Freshness
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.QueryFilter
import com.agentifiers.amp.model.Sidebar
import com.agentifiers.amp.model.StepKey
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadProject
import com.agentifiers.amp.model.ThreadQuery
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.needsServer
import com.agentifiers.amp.model.noThreadsYet
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.FleetNotice
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** What the Sidebar's slot shows. Activity replaces the sections until Back. */
private enum class SidebarView { Sections, Activity }

/**
 * The Sidebar: a one-row header, then one list that holds the search field, the arranged sections with row menus and
 * Needs you actions, or the search results. Activity opens in the same slot. [onCollapse] is null where the window
 * has no room to gain from the rail, which hides Collapse.
 * Organize writes go through the thread handle; the account avatar asks :app for the account switcher, and
 * [openWindow] asks :app for a new window. [notificationsOff] is non-null while Android is not letting Amp post.
 */
@Composable
fun SidebarPane(
    dest: PaneDestination.Sidebar,
    scope: AccountScope,
    selected: ThreadId?,
    navigate: (PaneDestination) -> Unit,
    onAccountSwitcher: (anchor: Rect) -> Unit,
    openWindow: (PaneDestination) -> Unit,
    onCollapse: (() -> Unit)?,
    notificationsOff: NotificationsOffNotice?,
    modifier: Modifier = Modifier,
) {
    var view by rememberSaveable { mutableStateOf(SidebarView.Sections) }
    var dialog by remember { mutableStateOf<OrganizeDialog?>(null) }
    val knownLabels by remember(scope) { scope.fleet.labels() }.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val dispatch: (ThreadId, ThreadIntent) -> Unit = { id, intent -> scope.thread(id).dispatch(intent) }
    val actions = remember(scope, navigate, openWindow) {
        RowActions(
            open = { navigate(PaneDestination.Thread(it)) },
            intent = dispatch,
            dialog = { dialog = it },
            openWindow = { openWindow(PaneDestination.Thread(it)) },
        )
    }

    LaunchedEffect(scope) {
        scope.fleet.notices.collect { notice ->
            launch {
                when (notice) {
                    is FleetNotice.Archived ->
                        if (snackbar.showSnackbar("Archived", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                            notice.ids.forEach { dispatch(it, ThreadIntent.Archive(false)) }
                        }
                    is FleetNotice.Failed -> snackbar.showSnackbar("${notice.action} failed: ${notice.reason}")
                }
            }
        }
    }

    Box(modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane(dest.route))) {
        when (view) {
            SidebarView.Activity -> ActivityContent(
                scope = scope,
                navigate = navigate,
                initialText = "",
                onBack = { view = SidebarView.Sections },
                modifier = Modifier.fillMaxSize().padding(horizontal = AmpTheme.spacing.listInset),
            )
            SidebarView.Sections -> Column(Modifier.fillMaxSize().padding(horizontal = AmpTheme.spacing.listInset)) {
                SidebarHeader(scope, navigate, onAccountSwitcher, onActivity = { view = SidebarView.Activity }, onCollapse = onCollapse)
                SidebarBody(scope, selected, navigate, actions, notificationsOff)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).testTag(FleetTags.snackbar)) { data ->
            Snackbar(
                modifier = Modifier.padding(AmpTheme.spacing.md),
                action = data.visuals.actionLabel?.let { label ->
                    { AmpButton(label, data::performAction, FleetTags.snackbarUndo, rank = AmpButtonRank.Tertiary) }
                },
            ) { Text(data.visuals.message) }
        }
    }

    OrganizeDialogHost(dialog, knownLabels, { Clock.System.now() }, dispatch) { dialog = null }
}

/** One header row: the account avatar, the wordmark when the pane has room for it, then the Sidebar's icon actions. */
@Composable
private fun SidebarHeader(
    scope: AccountScope,
    navigate: (PaneDestination) -> Unit,
    onAccountSwitcher: (anchor: Rect) -> Unit,
    onActivity: () -> Unit,
    onCollapse: (() -> Unit)?,
) {
    val account by scope.account.collectAsStateWithLifecycle()
    AmpHeaderRow(Modifier, testTag = null, kind = "sidebar") {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val roomForWordmark = maxWidth >= AmpTheme.metrics.touch * 6 + WORDMARK_WIDTH
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(account.email, onAccountSwitcher)
                if (roomForWordmark) AmpWordmark(height = 20.dp, modifier = Modifier.padding(start = AmpTheme.spacing.xs))
                Spacer(Modifier.weight(1f))
                AmpIconButton(AmpIcons.SquarePen, "New Thread", onClick = { navigate(PaneDestination.NewThread(null)) }, testTag = FleetTags.newThread)
                AmpIconButton(AmpIcons.Activity, "Activity", onClick = onActivity, testTag = ShellTags.nav("activity"))
                AmpIconButton(AmpIcons.Sparkles, "Puck", onClick = { navigate(PaneDestination.Puck(null)) }, testTag = ShellTags.nav("puck"))
                AmpIconButton(AmpIcons.RefreshCw, "Refresh", onClick = scope.fleet::refresh, testTag = FleetTags.refresh)
                onCollapse?.let { AmpIconButton(AmpIcons.PanelLeftClose, "Collapse", onClick = it, testTag = FleetTags.collapse) }
            }
        }
    }
}

private val WORDMARK_WIDTH = 56.dp

/**
 * The account's initial in a circle; the email is its label, and a tap opens the account switcher with the circle's
 * window bounds, so the switcher can sit against the circle rather than the larger touch target around it.
 */
@Composable
internal fun AccountAvatar(email: String, onClick: (anchor: Rect) -> Unit) {
    var circle by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.size(AmpTheme.metrics.touch).testTag(FleetTags.accountSwitcher)
            .clickable(onClickLabel = "Switch account", role = Role.Button) { onClick(circle) }
            .semantics { contentDescription = email },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(AmpTheme.metrics.avatar).onGloballyPositioned { circle = it.boundsInWindow() }.clip(CircleShape).background(AmpTheme.colors.surfaces.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(email.firstOrNull()?.uppercase() ?: "?", color = AmpTheme.colors.accents.brandMark, style = AmpTheme.type.button, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Search text split into committed filter chips and the token still being typed. */
internal data class SearchInput(val chips: List<String>, val draft: String) {
    val query: ThreadQuery get() = ThreadQuery.parse((chips + draft).joinToString(" "))
    val isBlank: Boolean get() = chips.isEmpty() && draft.isBlank()

    /** A recognized filter becomes a chip once a space ends it; other words stay in the field. */
    fun typed(text: String): SearchInput {
        if (text.isEmpty() || !text.last().isWhitespace()) return copy(draft = text)
        val (filters, words) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .partition { ThreadQuery.parse(it).filters.isNotEmpty() }
        return SearchInput(chips + filters, if (words.isEmpty()) "" else words.joinToString(" ", postfix = " "))
    }

    /** Puts chip [index] back at the end of the field for editing. */
    fun edit(index: Int): SearchInput =
        SearchInput(chips.filterIndexed { i, _ -> i != index }, listOf(draft.trim(), chips[index]).filter { it.isNotEmpty() }.joinToString(" "))
}

/** The search field scrolls with the list it filters, so the sections start right under the header (UX-02). */
@Composable
private fun SidebarBody(scope: AccountScope, selected: ThreadId?, navigate: (PaneDestination) -> Unit, actions: RowActions, notificationsOff: NotificationsOffNotice?) {
    var chips by rememberSaveable { mutableStateOf(listOf<String>()) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val input = SearchInput(chips, field.text)
    val apply: (SearchInput) -> Unit = { next ->
        chips = next.chips
        field = if (next.draft == field.text) field else TextFieldValue(next.draft, TextRange(next.draft.length))
    }
    val sidebar by scope.fleet.sidebar.collectAsStateWithLifecycle()
    val fleet by scope.fleet.threads.collectAsStateWithLifecycle()
    val usual = remember(fleet) { (fleet as? FleetView.Ready)?.threads?.let(::usualExecutor) }
    val now = rememberNow()
    var open by rememberSaveable { mutableStateOf(setOf<SectionKind>()) }
    var confirmArchive by remember { mutableStateOf<PersistentList<ThreadId>?>(null) }
    val results = if (input.isBlank) null else {
        val found by remember(scope, input.query) { scope.fleet.search(input.query) }.collectAsStateWithLifecycle<PersistentList<ThreadSummary>?>(null)
        SearchView(found, input.query)
    }
    val rows = RowContext(selected, usual, now, scope, actions)
    val list = rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize().testTag(FleetTags.list), state = list) {
        item(key = "search") {
            Column {
                AmpSearchField(
                    value = field,
                    onValueChange = { value ->
                        val next = input.typed(value.text)
                        chips = next.chips
                        field = if (next.draft == value.text) value else TextFieldValue(next.draft, TextRange(next.draft.length))
                    },
                    placeholder = "Search threads",
                    testTag = FleetTags.search,
                    modifier = Modifier.padding(horizontal = AmpTheme.spacing.paneX - AmpTheme.spacing.listInset, vertical = AmpTheme.spacing.searchMarginY),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { apply(input.typed(field.text + " ")) }),
                    trailing = if (input.isBlank) null else {
                        { AmpIconButton(AmpIcons.X, "Clear", onClick = { apply(SearchInput(emptyList(), "")) }, testTag = FleetTags.searchClear) }
                    },
                )
                if (input.chips.isNotEmpty()) {
                    FlowRow(Modifier.padding(horizontal = AmpTheme.spacing.paneX - AmpTheme.spacing.listInset), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs)) {
                        input.chips.forEachIndexed { index, token ->
                            SearchChip(index, ThreadQuery.parse(token).filters.filterIsInstance<QueryFilter.Key>().first()) { apply(input.edit(index)) }
                        }
                    }
                }
            }
        }
        if (results == null) {
            notificationsOff?.let { item(key = "notifications-off") { NotificationsOffRow(it) } }
            item(key = "status") { FleetStatusLine(fleet, sidebar.freshness, now, scope) }
            sections(sidebar, open, rows, onToggle = { kind -> open = if (kind in open) open - kind else open + kind }, onArchiveAll = { confirmArchive = it }, onNewThread = { navigate(PaneDestination.NewThread(null)) }, list = list)
        } else {
            searchResults(results, rows)
        }
    }

    confirmArchive?.let { ids ->
        AlertDialog(
            modifier = Modifier.tagsAsResourceIds(),
            onDismissRequest = { confirmArchive = null },
            title = { Text("Archive All") },
            text = { Text("Archive ${ids.size} inactive ${if (ids.size == 1) "thread" else "threads"}?") },
            confirmButton = {
                AmpButton("Archive All", { scope.fleet.archiveAll(ids); confirmArchive = null }, FleetTags.archiveInactiveConfirm, rank = AmpButtonRank.Primary)
            },
            dismissButton = {
                AmpButton("Cancel", { confirmArchive = null }, FleetTags.organizeCancel)
            },
        )
    }
}

/** What every row in one list shares. */
private class RowContext(val selected: ThreadId?, val usualExecutor: String?, val now: Instant, val scope: AccountScope, val actions: RowActions)

private class SearchView(val found: PersistentList<ThreadSummary>?, val query: ThreadQuery)

@Composable
private fun SearchChip(index: Int, filter: QueryFilter.Key, onEdit: () -> Unit) {
    AmpChip(
        label = if (filter.needsServer) "${filter.render()} · Needs server search" else filter.render(),
        selected = true,
        onClick = onEdit,
        testTag = FleetTags.searchChip(index),
        leadingIcon = if (filter.needsServer) AmpIcons.TriangleAlert else null,
    )
}

private fun LazyListScope.searchResults(results: SearchView, rows: RowContext) {
    val found = results.found
    when {
        found == null -> item(key = "searching") {
            Text("Searching…", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.body, modifier = Modifier.padding(horizontal = AmpTheme.spacing.paneX - AmpTheme.spacing.listInset, vertical = AmpTheme.spacing.sm))
        }
        found.isEmpty() -> item(key = "no-results") {
            val filters = results.query.filters.joinToString(" ") { it.render() }
            AmpEmptyState(
                icon = AmpIcons.Search,
                title = "No threads match",
                guidance = (if (filters.isEmpty()) "" else "Filters: $filters. ") + "Try label:, project:, or archived:true.",
                testTag = FleetTags.searchEmpty,
                modifier = Modifier.fillParentMaxSize(),
            )
        }
        else -> items(found, key = { it.id.raw }) { summary ->
            ThreadRow(summary, summary.id == rows.selected, showProject = true, rows.usualExecutor, rows.now, rows.actions)
        }
    }
}

/** A section's kind decides whether it folds (and its toggle tag), whether rows name their project, and the Needs you extras. */
internal enum class SectionKind(val toggleTag: String?) {
    NeedsYou(null),
    Pinned(null),
    Project(null),
    Inactive(FleetTags.sectionInactiveToggle),
    Snoozed(FleetTags.sectionSnoozedToggle),
    Archived(FleetTags.sectionArchivedToggle),
}

internal class SidebarSection(
    val key: String,
    val kind: SectionKind,
    val title: String,
    val tag: String,
    val rows: List<ThreadSummary>,
) {
    val heading: String get() = "$title (${rows.size})"
}

/** The sections that have rows, in screens.md order. An empty section is a header that leads nowhere, so it is left out. */
internal fun Sidebar.sections(): List<SidebarSection> = buildList {
    add(SidebarSection("needs-you", SectionKind.NeedsYou, "Needs you", FleetTags.sectionNeedsYou, needsYou))
    add(SidebarSection("pinned", SectionKind.Pinned, "Pinned", FleetTags.sectionPinned, pinned))
    active.forEach { group ->
        val (key, tag) = when (val project = group.project) {
            ThreadProject.None -> "project-none" to FleetTags.sectionProjectNone
            is ThreadProject.Linked -> "project-${project.id.raw}" to FleetTags.sectionProject(project.id)
        }
        add(SidebarSection(key, SectionKind.Project, group.project.displayName, tag, group.threads))
    }
    add(SidebarSection("inactive", SectionKind.Inactive, "Inactive Last 72h", FleetTags.sectionInactive, inactive72h))
    add(SidebarSection("snoozed", SectionKind.Snoozed, "Snoozed", FleetTags.sectionSnoozed, snoozed))
    add(SidebarSection("archived", SectionKind.Archived, "Archived", FleetTags.sectionArchived, archived))
}.filter { it.rows.isNotEmpty() }

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.sections(
    sidebar: Sidebar,
    open: Set<SectionKind>,
    rows: RowContext,
    onToggle: (SectionKind) -> Unit,
    onArchiveAll: (PersistentList<ThreadId>) -> Unit,
    onNewThread: () -> Unit,
    list: LazyListState,
) {
    if (sidebar.noThreadsYet) {
        item(key = "empty") {
            AmpEmptyState(
                icon = AmpIcons.MessageSquare,
                title = "No threads yet",
                guidance = "Start one, and it shows here while it works and when it needs you.",
                testTag = FleetTags.empty,
                modifier = Modifier.fillParentMaxSize(),
            ) { AmpButton("New Thread", onClick = onNewThread, testTag = FleetTags.emptyNewThread, rank = AmpButtonRank.Primary, leadingIcon = AmpIcons.Plus) }
        }
        return
    }
    sidebar.sections().forEach { section ->
        val expanded = section.kind.toggleTag == null || section.kind in open
        // Sticky, so a row scrolled far down still shows which section it is in (UX-07).
        stickyHeader(key = "header-${section.key}") { index ->
            val stuck by remember(index) {
                derivedStateOf { list.firstVisibleItemIndex > index || (list.firstVisibleItemIndex == index && list.firstVisibleItemScrollOffset > 0) }
            }
            SectionHeader(
                section = section,
                expanded = expanded,
                stuck = stuck,
                onToggle = { onToggle(section.kind) },
                onArchiveAll = { onArchiveAll(section.rows.map { it.id }.toPersistentList()) },
            )
        }
        if (expanded) {
            items(section.rows, key = { it.id.raw }) { summary ->
                ThreadTree(
                    summary = summary,
                    scope = rows.scope,
                    selected = rows.selected,
                    showProject = section.kind != SectionKind.Project,
                    usualExecutor = rows.usualExecutor,
                    now = rows.now,
                    actions = rows.actions,
                    trailing = if (section.kind != SectionKind.NeedsYou) null else { fits ->
                        Row { NeedsYouAction(summary, fits, rows.scope, rows.actions) }
                    },
                )
            }
        }
    }
}

@Composable
private fun FleetStatusLine(fleet: FleetView, freshness: Freshness, now: Instant, scope: AccountScope) {
    Column(Modifier.padding(horizontal = AmpTheme.spacing.paneX - AmpTheme.spacing.listInset)) {
        // Loading follows the Sidebar's own freshness, so it leaves in the frame the rows arrive.
        when {
            fleet is FleetView.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load threads: ${fleet.reason}", color = AmpTheme.colors.status.danger, style = AmpTheme.type.secondary, modifier = Modifier.weight(1f))
                AmpButton("Retry", onClick = scope.fleet::refresh, testTag = FleetTags.retry, rank = AmpButtonRank.Tertiary)
            }
            freshness == Freshness.NotLoaded ->
                Text("Loading threads…", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.body, modifier = Modifier.testTag(FleetTags.loading))
            fleet is FleetView.Ready -> fleet.refreshError?.let { Text("Refresh failed: $it", color = AmpTheme.colors.status.danger, style = AmpTheme.type.secondary) }
        }
        if (freshness is Freshness.Cached) {
            Text("Showing threads as of ${relativeTime(now, freshness.asOf)} ago.", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary)
        }
    }
}

/**
 * A section's sticky heading. While [stuck] over the rows it draws a line on its bottom edge, so the half of a row
 * scrolled under it reads as list content rather than as part of the heading.
 */
@Composable
private fun SectionHeader(section: SidebarSection, expanded: Boolean, stuck: Boolean, onToggle: () -> Unit, onArchiveAll: () -> Unit) {
    val line = AmpTheme.colors.surfaces.line
    val divider = AmpTheme.metrics.divider
    val toggleTag = section.kind.toggleTag
    AmpSectionLabel(
        text = section.title,
        testTag = section.tag,
        modifier = Modifier.background(LocalPaneSurface.current).drawWithContent {
            drawContent()
            if (stuck) {
                val y = size.height - divider.toPx() / 2
                drawLine(line, Offset(0f, y), Offset(size.width, y), divider.toPx())
            }
        },
        count = section.rows.size,
        trailing = if (toggleTag == null) null else {
            {
                if (section.kind == SectionKind.Inactive) {
                    AmpButton("Archive All", onClick = onArchiveAll, testTag = FleetTags.archiveInactive, rank = AmpButtonRank.Link, trailingIcon = null)
                }
                AmpIconButton(
                    if (expanded) AmpIcons.ChevronUp else AmpIcons.ChevronDown,
                    if (expanded) "Hide ${section.title}" else "Show ${section.title}",
                    onClick = onToggle,
                    testTag = toggleTag,
                    glyph = AmpTheme.metrics.iconSmall,
                )
            }
        },
    )
}

/**
 * The one action a Needs you row offers on its title line: Allow and Deny for an approval whose whole input shows
 * in the row ([summaryFits]), else Review; Answer for any other gate; Open for an error; Mark Read for a finished thread.
 */
@Composable
private fun NeedsYouAction(summary: ThreadSummary, summaryFits: Boolean, scope: AccountScope, actions: RowActions) {
    val id = summary.id
    when (val status = summary.status) {
        is FleetStatus.NeedsApproval -> {
            val gate = status.gate
            if (gate.answerability == Answerability.Answerable) {
                AmpIconButton(AmpIcons.X, "Deny", onClick = { actions.intent(id, ThreadIntent.Answer(gate.deny(null))) }, testTag = FleetTags.needsYou(id, NeedsYouAction.Deny))
            }
            if (gate.answerability == Answerability.Answerable && summaryFits) {
                AmpIconButton(AmpIcons.Check, "Allow", onClick = { actions.intent(id, ThreadIntent.Answer(gate.approve())) }, testTag = FleetTags.needsYou(id, NeedsYouAction.Allow), tint = AmpTheme.colors.accents.accent)
            } else {
                AmpIconButton(AmpIcons.Eye, "Review", onClick = { actions.open(id) }, testTag = FleetTags.needsYou(id, NeedsYouAction.Review))
            }
        }
        is FleetStatus.NeedsInput ->
            AmpButton(ANSWER, onClick = { actions.open(id) }, testTag = FleetTags.needsYou(id, NeedsYouAction.Answer), rank = AmpButtonRank.Tertiary)
        FleetStatus.Error ->
            AmpButton(OPEN, onClick = { actions.open(id) }, testTag = FleetTags.needsYou(id, NeedsYouAction.Open), rank = AmpButtonRank.Tertiary)
        FleetStatus.Done -> {
            // Marking read needs a message id, which only a cached transcript has; without one, opening the thread reads it.
            val state by remember(scope, id) { scope.thread(id).state }.collectAsStateWithLifecycle()
            val latest = (state.transcript as? TranscriptView.Ready)?.turns?.lastOrNull()?.steps?.lastOrNull()?.key?.message
            AmpIconButton(
                AmpIcons.Check,
                "Mark Read",
                onClick = { if (latest != null) actions.intent(id, ThreadIntent.MarkRead(latest)) else actions.open(id) },
                testTag = FleetTags.needsYou(id, NeedsYouAction.Read),
            )
        }
        FleetStatus.Working, FleetStatus.Compacting, FleetStatus.Idle, FleetStatus.Inactive, is FleetStatus.Reported -> Unit
    }
}

/** [StepKey.of] joins the message id and block index with the last colon. */
private val StepKey.message: MessageId get() = MessageId(raw.substringBeforeLast(':'))

private const val ANSWER = "Answer"
private const val OPEN = "Open"

