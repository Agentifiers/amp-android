package com.agentifiers.amp.changes

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ChangesTags
import com.agentifiers.amp.design.LocalPaneCreases
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpSheet
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpHeaderAction
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.components.AmpSectionLabel
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffComment
import com.agentifiers.amp.model.FileChange
import com.agentifiers.amp.model.FileDiffView
import com.agentifiers.amp.model.FileOrder
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.SectionKind
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Changes
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Review (one continuous multi-file diff) from this width; narrower panes show the list, then one file. */
private val ReviewWidth = 600.dp

/** Split by default from this width even without a crease. */
private val SplitWidth = 700.dp

/** Sheets, dialogs and menus are their own windows, so they repeat the root's tag-to-resource-id mapping for agent-device. */
internal val PopupTags: Modifier = Modifier.semantics { testTagsAsResourceId = true }

/**
 * The Changes machine tab: sections, intelligent order, diffs, comments, staging, Ship. Its menu items and the actions
 * that do not fit go to the machine header's More Actions through [publishMenu], so the pane has one menu.
 */
@Composable
fun ChangesTab(
    dest: PaneDestination.Machine,
    tab: MachineTab.Changes,
    scope: AccountScope,
    navigate: (PaneDestination) -> Unit,
    modifier: Modifier = Modifier,
    publishMenu: (List<AmpMenuItem>) -> Unit,
) {
    val thread = remember(scope, dest.thread) { scope.thread(dest.thread) }
    val changes = thread.machine.changes
    val view by changes.view.collectAsStateWithLifecycle()
    val comments by changes.comments.collectAsStateWithLifecycle()
    val coroutines = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val currentNavigate by rememberUpdatedState(navigate)
    val controller = remember(changes) {
        PaneController(changes, thread, dest.thread, coroutines, clipboard) { currentNavigate(it) }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(LocalPaneSurface.current).testTag(ShellTags.pane("changes"))) {
        val current = view
        if (current !is ChangesView.Ready) SideEffect { publishMenu(emptyList()) }
        when (current) {
            ChangesView.Loading -> Text(
                "Loading changes…", style = AmpTheme.type.body, color = AmpTheme.colors.text.inkMuted,
                modifier = Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.md),
            )
            is ChangesView.Unavailable -> UnavailableMessage(current.explanation, changes)
            ChangesView.NoRepository -> NoRepository(changes)
            is ChangesView.Failed -> LoadFailed(current.message, changes)
            is ChangesView.Ready -> ReadyPane(current, comments, controller, tab.focusPath, maxWidth, publishMenu)
        }
    }
}

@Composable
private fun UnavailableMessage(explanation: String, changes: Changes) {
    AmpEmptyState(
        icon = AmpIcons.GitCompare,
        title = "Changes unavailable",
        guidance = explanation,
        testTag = ChangesTags.unavailable,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AmpButton("Refresh", { changes.refresh() }, ChangesTags.refresh)
    }
}

/** Neutral, not an error: the executor answered, and its folder simply has no repository. Refresh checks again after `git init`. */
@Composable
private fun NoRepository(changes: Changes) {
    AmpEmptyState(
        icon = AmpIcons.GitBranch,
        title = "No git repository",
        guidance = "This thread's workspace isn't a git repository, so there are no changes to show. Files and Terminal still work.",
        testTag = ChangesTags.noRepository,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AmpButton("Refresh", { changes.refresh() }, ChangesTags.refresh, rank = AmpButtonRank.Link)
    }
}

/** The server's own words sit behind Details, because they name the call that failed and not what the user can do. */
@Composable
private fun LoadFailed(message: String, changes: Changes) {
    var details by rememberSaveable { mutableStateOf(false) }
    AmpEmptyState(
        icon = AmpIcons.CircleAlert,
        title = "Couldn't load changes",
        guidance = "The executor didn't send this thread's changes. Retry, or check that the runner is online.",
        testTag = ChangesTags.error,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            AmpButton("Retry", { changes.refresh() }, ChangesTags.refresh, rank = AmpButtonRank.Primary)
            AmpButton(if (details) "Hide Details" else "Details", { details = !details }, ChangesTags.errorDetails, rank = AmpButtonRank.Link)
        }
        if (details) {
            Text(message, style = AmpTheme.type.mono, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(ChangesTags.errorDetailsText))
        }
    }
}

@Composable
private fun ReadyPane(view: ChangesView.Ready, comments: List<DiffComment>, c: PaneController, focusPath: String?, width: Dp, publishMenu: (List<AmpMenuItem>) -> Unit) {
    val review = width >= ReviewWidth
    val creases = LocalPaneCreases.current
    var splitChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // A narrow pane's split sides would each get under 300dp and wrap every line, so only Review splits.
    val split = review && (splitChoice ?: (creases.isNotEmpty() || width >= SplitWidth))
    var wrap by rememberSaveable { mutableStateOf(true) }
    var tree by rememberSaveable { mutableStateOf(false) }
    var barOverflow by remember { mutableStateOf(emptyList<AmpMenuItem>()) }
    val toolbar: ToolbarSlot = { mode, nav ->
        val overflow = barOverflow
        ChangesToolbar(view, mode, nav, split, wrap, tree, c, onSplit = { splitChoice = it }, onWrap = { wrap = it }, onTree = { tree = it }) { items ->
            publishMenu(items + overflow)
        }
    }
    val listTree = tree && view.order !is FileOrder.Intelligent
    val splitAt = creases.firstOrNull()?.takeIf { it > 0.dp && it < width } ?: (width / 2)
    val ui = DiffUi(c, wrap, rememberCharWidth(), splitAt, view.canStage, null)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                view.files.isEmpty() -> Column {
                    toolbar(PaneMode.List, null)
                    Text(
                        emptyText(view), style = AmpTheme.type.body, color = AmpTheme.colors.text.inkMuted,
                        modifier = Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.sm).testTag(ChangesTags.empty),
                    )
                }
                review -> ReviewDiff(view, listTree, split, ui, focusPath, toolbar)
                else -> TriageBody(view, listTree, split, ui, focusPath, toolbar)
            }
        }
        ReviewTray(comments, c)
        ActionBar(view, comments, c) { barOverflow = it }
    }

    c.confirm?.let { ConfirmSheet(it, c) }
    val sync = view.facts.syncCommand
    if (c.syncOpen && sync != null) SyncSheet(sync, c)
    c.fullLine?.let { FullLineDialog(it) { c.fullLine = null } }
}

/** One diff body's list, and the rows its Previous and Next actions jump between. */
internal data class ListNav(val state: LazyListState, val hunks: List<Int>, val files: List<Int> = emptyList())

/** ReadyPane's toolbar, which each body places above its own list so Previous and Next can scroll it. */
private typealias ToolbarSlot = @Composable (PaneMode, ListNav?) -> Unit

/**
 * The scope row under the machine header: Back while a Triage file is open, the sole repository's heading outside a
 * single file, the source ("Live") in the section role, and the icon actions in [toolbarActions] order that fit at
 * full 48dp targets. The rest, and the [toolbarMenu] items, go to [publishMenu] for the machine header's More Actions.
 */
@Composable
private fun ChangesToolbar(
    view: ChangesView.Ready,
    mode: PaneMode,
    nav: ListNav?,
    split: Boolean,
    wrap: Boolean,
    tree: Boolean,
    c: PaneController,
    onSplit: (Boolean) -> Unit,
    onWrap: (Boolean) -> Unit,
    onTree: (Boolean) -> Unit,
    publishMenu: (List<AmpMenuItem>) -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()) }
    val coroutines = rememberCoroutineScope()
    val targets by remember(nav) {
        derivedStateOf { nav?.let { jumps(it.state.firstVisibleItemIndex, it.hunks, it.files) }.orEmpty() }
    }
    val actions = toolbarActions(mode).map { action ->
        AmpHeaderAction(action.icon(), action.label(split, wrap), action.tag, action.menuTag, enabled = !action.jump || action in targets) {
            when (action) {
                ToolbarAction.FileList -> c.fileListOpen = true
                ToolbarAction.Layout -> onSplit(!split)
                ToolbarAction.Wrap -> onWrap(!wrap)
                ToolbarAction.Refresh -> c.changes.refresh()
                else -> targets[action]?.let { row -> nav?.let { coroutines.launch { it.state.animateScrollToItem(row) } } }
            }
        }
    }
    val anyExpanded = mode == PaneMode.Review && view.files.any(c::isExpanded)
    val menu = toolbarMenu(view.order, tree, mode, anyExpanded).map { item ->
        AmpMenuItem(item.label, item.tag, enabled = item.enabled) {
            when (item.action) {
                MenuAction.Order -> c.setOrder(view.order)
                MenuAction.Tree -> onTree(!tree)
                MenuAction.FoldAll -> c.setAllExpanded(view.files, !anyExpanded)
            }
        }
    }
    val source = sourceLabel(view.source, view.stale) { formatter.format(java.time.Instant.ofEpochMilli(it.toEpochMilliseconds())) }
    val back = mode == PaneMode.File
    val title = view.repos.singleOrNull()?.takeIf { !back }?.let(::repoHeading)
    val spacing = AmpTheme.spacing
    val metrics = AmpTheme.metrics
    val sourceWidth = minOf(rememberTextWidth(source.uppercase(), AmpTheme.type.section), metrics.titleMin)
    val titleWidth = minOf(rememberTextWidth(title.orEmpty(), AmpTheme.type.rowTitle), metrics.titleMin)
    Column(Modifier.fillMaxWidth().background(LocalPaneSurface.current)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val start = if (back) spacing.xs + metrics.touch else spacing.paneX
            val kept = if (title == null) sourceWidth else titleWidth + spacing.sm + sourceWidth
            val room = maxWidth - start - spacing.xs - kept
            val shown = actions.size.coerceAtMost((room / metrics.touch).toInt().coerceAtLeast(0))
            val moved = actions.drop(shown).map { AmpMenuItem(it.label, it.menuTestTag, it.icon, it.enabled, it.onClick) }
            SideEffect { publishMenu(moved + menu) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = metrics.subRow).testTag(ChangesTags.toolbar)
                    .padding(start = if (back) spacing.xs else spacing.paneX, end = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (back) AmpIconButton(AmpIcons.ArrowLeft, "Back", { c.openFileId = null }, ChangesTags.back)
                if (title != null) {
                    Text(
                        title, style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Text(
                    source.uppercase(), style = AmpTheme.type.section, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = (if (title == null) Modifier.weight(1f) else Modifier.padding(start = spacing.sm).widthIn(max = metrics.titleMin))
                        .testTag(ChangesTags.source)
                        .clearAndSetSemantics {
                            text = AnnotatedString(source)
                            if (title == null) heading()
                        },
                )
                actions.take(shown).forEach { AmpIconButton(it.icon, it.label, it.onClick, it.testTag, enabled = it.enabled) }
            }
        }
        val note = Modifier.fillMaxWidth().padding(start = spacing.paneX, end = spacing.paneX, bottom = spacing.xs)
        (view.order as? FileOrder.Unavailable)?.let {
            Text(
                it.reason, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = note.testTag(ChangesTags.orderReason),
            )
        }
        view.notice?.let { NoticeLine(it, view.noticeDetail, c, note) }
    }
}

/** A refresh that failed while older changes stay on screen: plain words, Retry, and the server's message behind Details. */
@Composable
private fun NoticeLine(notice: String, detail: String?, c: PaneController, modifier: Modifier) {
    var details by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                notice, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(ChangesTags.notice),
            )
            if (detail != null) {
                AmpButton("Retry", { c.changes.refresh() }, ChangesTags.noticeRetry, rank = AmpButtonRank.Link)
                AmpButton(if (details) "Hide Details" else "Details", { details = !details }, ChangesTags.errorDetails, rank = AmpButtonRank.Link)
            }
        }
        if (details && detail != null) {
            Text(detail, style = AmpTheme.type.mono, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(ChangesTags.errorDetailsText))
        }
    }
}

/** The one-line width of [text] in [style], which the scope row keeps before its actions. */
@Composable
private fun rememberTextWidth(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, density) { with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp() } }
}

private fun ToolbarAction.icon(): ImageVector = when (this) {
    ToolbarAction.PrevHunk -> AmpIcons.ChevronUp
    ToolbarAction.NextHunk -> AmpIcons.ChevronDown
    ToolbarAction.PrevFile -> AmpIcons.ChevronLeft
    ToolbarAction.NextFile -> AmpIcons.ChevronRight
    ToolbarAction.FileList -> AmpIcons.FileText
    ToolbarAction.Layout -> AmpIcons.Columns2
    ToolbarAction.Wrap -> AmpIcons.TextWrap
    ToolbarAction.Refresh -> AmpIcons.RefreshCw
}

/** Review: every file in one LazyColumn, expanded files followed by their lines. */
@Composable
private fun ReviewDiff(view: ChangesView.Ready, tree: Boolean, split: Boolean, ui: DiffUi, focusPath: String?, toolbar: ToolbarSlot) {
    val c = ui.controller
    val expandedIds = view.files.filter(c::isExpanded).map { it.id }
    val diffs = collectDiffs(c.changes, expandedIds)
    SideEffect { c.visibleDiffs = diffs }
    val rows by remember(view, tree, split, diffs) {
        derivedStateOf {
            paneRows(view, tree, c::isExpanded) { file ->
                fileBodyRows(file, diffs[file.id] ?: FileDiffView.Loading, split, c.revealed, c.explanations)
            }
        }
    }
    val listState = rememberLazyListState()
    val files = remember(rows) { rows.indices.filter { rows[it] is FileRow } }
    val hunks = remember(rows) { rows.indices.filter { rows[it] is HunkRow } }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var focusDone by rememberSaveable(focusPath) { mutableStateOf(false) }

    LaunchedEffect(focusPath, view.files) {
        if (focusPath == null || focusDone) return@LaunchedEffect
        val file = view.files.firstOrNull { it.path == focusPath } ?: return@LaunchedEffect
        jumpTo = file.id
        focusDone = true
    }
    LaunchedEffect(jumpTo, rows) {
        val id = jumpTo ?: return@LaunchedEffect
        val file = view.files.firstOrNull { it.id == id }
        if (file == null) {
            jumpTo = null
            return@LaunchedEffect
        }
        if (!c.isExpanded(file)) {
            c.setExpanded(file, true)
            return@LaunchedEffect
        }
        val index = rows.indexOfFirst { it is FileRow && it.file.id == id }
        if (index >= 0) listState.scrollToItem(index)
        jumpTo = null
    }

    Column(Modifier.fillMaxSize()) {
        toolbar(PaneMode.Review, ListNav(listState, hunks, files))
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = AmpTheme.spacing.sm)) {
            paneItems(rows, ui.copy(onFileClick = { c.setExpanded(it, !c.isExpanded(it)) }))
        }
    }

    if (c.fileListOpen) {
        FileListSheet(view, tree, onDismiss = { c.fileListOpen = false }) {
            c.fileListOpen = false
            jumpTo = it.id
        }
    }
}

/** Triage and the cover display: the file list, or one file's diff with Back. */
@Composable
private fun TriageBody(view: ChangesView.Ready, tree: Boolean, split: Boolean, ui: DiffUi, focusPath: String?, toolbar: ToolbarSlot) {
    val c = ui.controller
    var focusDone by rememberSaveable(focusPath) { mutableStateOf(false) }
    LaunchedEffect(focusPath, view.files) {
        if (focusPath == null || focusDone) return@LaunchedEffect
        val file = view.files.firstOrNull { it.path == focusPath } ?: return@LaunchedEffect
        c.openFileId = file.id
        focusDone = true
    }
    val open = view.files.firstOrNull { it.id == c.openFileId }
    if (open == null) {
        val rows = remember(view, tree) { paneRows(view, tree, null) { emptyList() } }
        val listState = rememberLazyListState()
        LaunchedEffect(c.moved, rows) {
            val (path, section) = c.moved ?: return@LaunchedEffect
            val index = rows.indexOfFirst { it is FileRow && it.file.path == path && it.section == section }
            if (index < 0) return@LaunchedEffect
            listState.animateScrollToItem(index)
            c.moved = null
        }
        Column(Modifier.fillMaxSize()) {
            toolbar(PaneMode.List, null)
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                paneItems(rows, ui.copy(onFileClick = { c.openFileId = it.id }))
            }
        }
    } else {
        SingleFile(view, open, split, ui, toolbar)
    }
}

@Composable
private fun SingleFile(view: ChangesView.Ready, file: FileChange, split: Boolean, ui: DiffUi, toolbar: ToolbarSlot) {
    val c = ui.controller
    val diff by remember(file.id) { c.changes.diff(file.id) }.collectAsStateWithLifecycle()
    SideEffect { c.visibleDiffs = mapOf(file.id to diff) }
    val section = remember(view, file.id) {
        view.repos.flatMap { it.sections }.firstOrNull { section -> section.files.any { it.id == file.id } }?.kind ?: SectionKind.Range
    }
    val rows by remember(file, diff, section, split) {
        derivedStateOf { listOf(FileRow(file, section, null)) + fileBodyRows(file, diff, split, c.revealed, c.explanations) }
    }
    val listState = rememberLazyListState()
    val hunks = remember(rows) { rows.indices.filter { rows[it] is HunkRow } }
    Column(Modifier.fillMaxSize()) {
        toolbar(PaneMode.File, ListNav(listState, hunks))
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            paneItems(rows, ui)
        }
    }
}

@Composable
private fun FileListSheet(view: ChangesView.Ready, tree: Boolean, onDismiss: () -> Unit, onPick: (FileChange) -> Unit) {
    val rows = remember(view, tree) { paneRows(view, tree, null) { emptyList() } }
    AmpSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(rows.size, key = { rows[it].key }) { index ->
                when (val row = rows[index]) {
                    is RepoRow -> RepoHeading(row)
                    is SectionRow -> Box(Modifier.padding(horizontal = AmpTheme.spacing.listInset)) {
                        AmpSectionLabel(row.title, ChangesTags.sectionLabel(row.section.id.raw))
                    }
                    is DirRow -> DirHeading(row)
                    is FileRow -> FileListEntry(row, onPick)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun FullLineDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = PopupTags,
        confirmButton = { AmpButton("Close", onClose, ChangesTags.fullLineClose) },
        text = {
            SelectionContainer {
                Text(
                    text, style = Mono,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag(ChangesTags.fullLineText),
                )
            }
        },
    )
}

/** The diffs of the expanded files, keyed by file id. A file whose flow has not been read yet is absent. */
@Composable
private fun collectDiffs(changes: Changes, ids: List<String>): Map<String, FileDiffView> {
    val flow: Flow<Map<String, FileDiffView>> = remember(changes, ids) {
        if (ids.isEmpty()) flowOf(emptyMap()) else combine(ids.map { id -> changes.diff(id).map { id to it } }) { it.toMap() }
    }
    val initial = remember(changes, ids) { ids.associateWith { changes.diff(it).value } }
    return flow.collectAsStateWithLifecycle(initial).value
}

/** The advance of one monospace column, measured over ten digits so rounding does not accumulate. */
@Composable
private fun rememberCharWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val mono = Mono
    return remember(measurer, density) { with(density) { (measurer.measure("0000000000", mono).size.width / 10f).toDp() } }
}

/** A real toggle in a secondary button's shape: agent-device reads its label as text and its state as `checked`. */
@Composable
internal fun ToggleButton(label: String, checked: Boolean, tag: String, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    val colors = AmpTheme.colors
    Box(
        modifier.testTag(tag)
            .minimumInteractiveComponentSize()
            .heightIn(min = AmpTheme.metrics.button)
            .clip(AmpTheme.shapes.control)
            .background(if (checked) colors.accents.accentMuted else AmpTheme.surfaceRoles.buttonSecondary)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = AmpTheme.spacing.buttonPadX),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = AmpTheme.type.button, color = if (checked) colors.accents.accent else colors.text.ink) }
}
