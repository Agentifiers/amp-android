package com.agentifiers.amp.window

import com.agentifiers.amp.design.AmpTheme
import android.app.Activity
import android.content.ClipDescription
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.agentifiers.amp.BuildConfig
import com.agentifiers.amp.fleet.SidebarRail
import com.agentifiers.amp.design.LayoutTags
import com.agentifiers.amp.design.LocalPaneCreases
import com.agentifiers.amp.design.LocalPaneSpan
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.design.components.AmpTab
import com.agentifiers.amp.design.components.AmpTabRow
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.layout.Arrangement
import com.agentifiers.amp.layout.Column as LayoutColumn
import com.agentifiers.amp.layout.Divider
import com.agentifiers.amp.layout.LayoutPlan
import com.agentifiers.amp.layout.NavItem
import com.agentifiers.amp.layout.SlotRole
import com.agentifiers.amp.layout.Switcher
import com.agentifiers.amp.layout.WindowGeometry
import com.agentifiers.amp.layout.Workspace
import com.agentifiers.amp.layout.arrange
import com.agentifiers.amp.layout.back
import com.agentifiers.amp.layout.closeSettings
import com.agentifiers.amp.layout.expand
import com.agentifiers.amp.layout.PaneControl
import com.agentifiers.amp.layout.paneControls
import com.agentifiers.amp.layout.sidebarCollapses
import com.agentifiers.amp.layout.sidebarExpands
import com.agentifiers.amp.layout.cycleFocus
import com.agentifiers.amp.layout.drop
import com.agentifiers.amp.layout.focusShown
import com.agentifiers.amp.layout.goForward
import com.agentifiers.amp.layout.place
import com.agentifiers.amp.layout.planLayout
import com.agentifiers.amp.layout.readingOffsetDp
import com.agentifiers.amp.layout.restored
import com.agentifiers.amp.layout.select
import com.agentifiers.amp.layout.selectTab
import com.agentifiers.amp.layout.selected
import com.agentifiers.amp.layout.threadIn
import com.agentifiers.amp.layout.toggleExpand
import com.agentifiers.amp.layout.togglePuck
import androidx.compose.ui.graphics.vector.ImageVector
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.components.AmpHeaderAction
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.SettingsPage
import com.agentifiers.amp.model.ThreadQuery
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.store.saveWorkspace
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.thread.EmptyThreadPane
import com.agentifiers.amp.thread.ThreadMenuItem
import com.agentifiers.amp.ui.CreaseEditor
import com.agentifiers.amp.ui.CreaseOverlay
import com.agentifiers.amp.ui.EmptyMachinePane
import com.agentifiers.amp.ui.PaneHost
import com.agentifiers.amp.ui.PaneSlot
import com.agentifiers.amp.ui.WindowServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MenuDefaults
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.agentifiers.amp.design.components.popupArea
import com.agentifiers.amp.design.tagsAsResourceIds
import com.agentifiers.amp.settings.AccountSwitcher
import com.agentifiers.amp.settings.AccountsHost
import com.agentifiers.amp.ui.WindowAccount
import com.agentifiers.amp.ui.WindowOverlay
import com.agentifiers.amp.ui.WindowSettingsHost

private const val FLEET_REFRESH_MS = 30_000L
private const val TEXT_SCALE_STEP = 0.1f
private val TEXT_SCALES = 0.8f..1.6f

/**
 * The signed-in window: the window bar, the plan's columns with their dividers, and the Compact switcher. It owns
 * the window's workspace, applies every command and placement to it, and keeps the viewer leases on the threads
 * the visible columns show.
 */
@Composable
internal fun WindowShell(
    account: AccountScope,
    accountWindow: WindowAccount,
    services: WindowServices,
    window: WindowState,
    geometry: WindowGeometry,
    editor: CreaseEditor?,
) {
    val form = WindowForm.of(geometry.widthClass)
    val accountId = account.account.collectAsStateWithLifecycle().value.id
    val spaces = remember(window, accountId) { window.of(accountId.raw) }
    LaunchedEffect(spaces, form) { spaces.enter(form) }
    val workspace = spaces.workspace(form)
    val plan = planLayout(geometry, workspace)
    val currentPlan by rememberUpdatedState(plan)
    val currentGeometry by rememberUpdatedState(geometry)
    val update: ((Workspace) -> Workspace) -> Unit = { change ->
        spaces.update(form) { ws -> change(ws).let { next -> next.focusShown(planLayout(currentGeometry, next)) } }
    }
    val activity = LocalContext.current.findActivity()
    var showCreases by rememberSaveable { mutableStateOf(false) }

    if (BuildConfig.DEBUG) {
        LaunchedEffect(plan) {
            Log.i("AmpLayout", "${window.windowKey} window ${geometry.widthDp.toInt()}x${geometry.heightDp.toInt()}dp creases ${geometry.creases.source} -> ${plan.describe()}")
        }
        LaunchedEffect(workspace, window.textScale) {
            val pushed = workspace.pushed.filterValues { it.isNotEmpty() }.map { (role, stack) -> "$role=${stack.joinToString(",") { it.route }}" }
            Log.i(
                "AmpWorkspace",
                "${window.windowKey} ${form.key} ${workspace.arrangement} focus=${workspace.focus} thread=${workspace.thread} " +
                    "tab=${workspace.machineTab.key} pushed=$pushed text=${window.textScale}",
            )
        }
    }

    val placeDest: (PaneDestination, SlotRole?) -> Unit = { dest, from -> update { it.place(dest, currentPlan, from) } }

    val run: (WindowAction) -> Unit = { action ->
        val p = currentPlan
        when (action) {
            is WindowAction.Arrange -> update { it.arrange(action.arrangement, p.onePane) }
            is WindowAction.CycleFocus -> update { it.cycleFocus(p, action.step) }
            WindowAction.ToggleExpand -> if (!p.onePane) update { it.toggleExpand() }
            WindowAction.ExitFocus -> update { if (it.arrangement == Arrangement.Focus) it.restored() else it }
            WindowAction.NewWindow -> activity.openNewWindow(null)
            WindowAction.Palette -> window.paletteOpen = true
            WindowAction.NewThread -> placeDest(PaneDestination.NewThread(prefill = null), null)
            WindowAction.Settings -> placeDest(PaneDestination.Settings(SettingsPage.Accounts), null)
            WindowAction.WorkspaceSettings -> placeDest(PaneDestination.Web(WebSurface.AccountPage(AccountWebPage.WorkspaceAdmin, accountId, suffix = null)), null)
            WindowAction.Activity -> placeDest(PaneDestination.Activity(ThreadQuery.parse("")), null)
            WindowAction.TogglePuck -> update { it.togglePuck(p) }
            WindowAction.Back -> update { it.back(compact = p.onePane) ?: it }
            WindowAction.Forward -> update { it.goForward() ?: it }
            is WindowAction.Tab -> update { it.selectTab(action.tab, p) }
            is WindowAction.TextSize -> window.textScale =
                action.step?.let { (window.textScale + it * TEXT_SCALE_STEP).coerceIn(TEXT_SCALES) } ?: 1f
        }
    }
    val latestRun by rememberUpdatedState(run)
    val latestPlace by rememberUpdatedState(placeDest)
    val latestUpdate by rememberUpdatedState(update)

    DisposableEffect(window, spaces, form) {
        window.keys = { event ->
            val ws = spaces.workspace(form)
            val focused = ws.top(ws.focus)
            val terminalFocused = focused is PaneDestination.Machine && focused.tab is MachineTab.Terminal
            val command = if (event.action == KeyEvent.ACTION_DOWN) commandFor(event, terminalFocused) else null
            command?.let { latestRun(it.action) } != null
        }
        onDispose { window.keys = null }
    }

    LaunchedEffect(spaces) {
        spaces.restore(services.prefs.workspaces.data.first().windows)
        window.opening?.let { opening ->
            window.opening = null
            update { ws ->
                val placed = ws.place(opening.dest, currentPlan)
                if (opening.alone) placed.arrange(Arrangement.Focus, currentPlan.onePane) else placed
            }
        }
        snapshotFlow { spaces.wide to spaces.compact }.collect {
            services.prefs.saveWorkspace(spaces.entry(WindowForm.Wide))
            services.prefs.saveWorkspace(spaces.entry(WindowForm.Compact))
        }
    }

    LaunchedEffect(account) {
        services.navigator.pending.filterNotNull().filter { it.account == null || it.account == accountId }.collect { navigation ->
            latestPlace(navigation.dest, null)
            services.navigator.consumed(navigation)
        }
    }

    LaunchedEffect(Unit) {
        services.overrides.arrangement.filterNotNull().collect { arrangement ->
            latestRun(WindowAction.Arrange(arrangement))
            services.overrides.arrangement.compareAndSet(arrangement, null)
        }
    }

    val visibleThreads = workspace.threadsIn(plan.panes)
    DisposableEffect(account, visibleThreads) {
        services.leases.show(account, visibleThreads)
        onDispose { services.leases.show(account, emptySet()) }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(account) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                account.fleet.refresh()
                delay(FLEET_REFRESH_MS)
            }
        }
    }

    BackHandler(enabled = workspace.back(compact = plan.onePane) != null) { run(WindowAction.Back) }

    val threadMenu = buildList {
        if (!plan.onePane) add(ThreadMenuItem("Full Width", ThreadTags.menuFullWidth, checked = workspace.arrangement == Arrangement.Read) { run(WindowAction.Arrange(Arrangement.Read)) })
        workspace.thread?.let { thread ->
            add(ThreadMenuItem("Open in New Window", ThreadTags.menuNewWindow, checked = null) { activity.openNewWindow(WindowOpening(PaneDestination.Thread(thread), alone = true)) })
        }
        add(ThreadMenuItem("Command Palette", ThreadTags.menuPalette, checked = null) { window.paletteOpen = true })
        editor?.let { e ->
            add(ThreadMenuItem("Show creases", ThreadTags.menuShowCreases, checked = showCreases) { showCreases = !showCreases })
            add(ThreadMenuItem("Reset creases", ThreadTags.menuResetCreases, checked = null, onClick = e.reset))
        }
    }
    val context = LocalContext.current
    val settingsHost = remember(account, accountWindow) {
        WindowSettingsHost(
            context, accountId, accountWindow, services.engines.roster, services.signOuts, services.lock,
            navigate = { latestPlace(it, null) },
            scopes = services.engines::scope,
            close = { latestUpdate { it.closeSettings() } },
            windowNotifications = services.notifications,
        )
    }

    var draggingDp by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * window.textScale)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                plan.windowBar?.let { bar ->
                    // Divider grips live in the window bar, above the creases, so no grip covers the edge of a pane's controls.
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                        WindowBarRow(
                            bar,
                            onArrange = { run(WindowAction.Arrange(it)) },
                            onExitFocus = { run(WindowAction.ExitFocus) },
                            onPalette = { window.paletteOpen = true },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                        )
                        plan.dividers.forEach { divider ->
                            DividerGrip(divider, onDrag = { draggingDp = it }) { dropDp ->
                                update { it.copy(dividersDp = currentPlan.release(divider.index, dropDp)) }
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val last = plan.columns.lastIndex
                    plan.columns.forEachIndexed { index, column ->
                        val insets = insetSides(top = plan.windowBar == null, bottom = plan.switcher !is Switcher.BottomBar, start = index == 0, end = index == last)
                        Box(Modifier.offset(x = column.startDp.dp).width(column.widthDp.dp).fillMaxHeight().then(insets)) {
                            val slot = PaneSlot(
                                workspace.thread, { placeDest(it, column.role) }, accountWindow::openSwitcher, threadMenu, settingsHost,
                                windowControls = windowControls(plan, column.role, workspace, update) { activity.openNewWindow(WindowOpening(it, alone = true)) },
                                terminalInNav = plan.switcher?.items?.contains(NavItem.Terminal) == true,
                                collapseSidebar = if (plan.sidebarCollapses) ({ update { it.expand() } }) else null,
                                notifications = services.notifications,
                                back = { run(WindowAction.Back) },
                            )
                            ColumnContent(column, plan, workspace, account, slot, update)
                        }
                        if (index > 0) PaneDivider(column.startDp.dp, sidebarEdge = plan.columns[index - 1].role == SlotRole.Nav)
                    }
                    draggingDp?.let { x ->
                        Box(Modifier.offset(x = (x - 1.5f).dp).width(3.dp).fillMaxHeight().background(AmpTheme.colors.accents.focus))
                    }
                }
                (plan.switcher as? Switcher.BottomBar)?.let { bar ->
                    BottomNav(bar.items, workspace.selected()) { item -> update { it.select(item) } }
                }
            }
            if (showCreases && editor != null) CreaseOverlay(geometry.creases, editor)
            if (accountWindow.overlay == WindowOverlay.Switcher) {
                AccountSwitcherPopup(settingsHost, accountWindow.switcherAnchor, switcherSpan(plan, AmpTheme.spacing.sm.value))
            }
        }
        if (window.paletteOpen) {
            CommandPalette(
                WindowCommands,
                onRun = { command ->
                    window.paletteOpen = false
                    run(command.action)
                },
                onDismiss = { window.paletteOpen = false },
            )
        }
    }
}

/** Safe-drawing sides a column pads, so only outer columns move off the system bars and inner edges stay on the creases. */
@Composable
private fun insetSides(top: Boolean, bottom: Boolean, start: Boolean, end: Boolean): Modifier {
    val sides = listOfNotNull(
        WindowInsetsSides.Top.takeIf { top },
        WindowInsetsSides.Bottom.takeIf { bottom },
        WindowInsetsSides.Start.takeIf { start },
        WindowInsetsSides.End.takeIf { end },
    ).reduceOrNull { a, b -> a + b } ?: return Modifier
    return Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(sides))
}

private val NavItem.label: String
    get() = when (this) {
        NavItem.Threads -> "Threads"
        NavItem.Thread -> "Thread"
        NavItem.Machine -> "Machine"
        NavItem.Terminal -> "Terminal"
    }

private val NavItem.icon: ImageVector
    get() = when (this) {
        NavItem.Threads -> AmpIcons.MessagesSquare
        NavItem.Thread -> AmpIcons.MessageSquare
        NavItem.Machine -> AmpIcons.Server
        NavItem.Terminal -> AmpIcons.Terminal
    }

@Composable
private fun ColumnContent(
    column: LayoutColumn,
    plan: LayoutPlan,
    workspace: Workspace,
    account: AccountScope,
    slot: PaneSlot,
    update: ((Workspace) -> Workspace) -> Unit,
) {
    if (column.rail) {
        // Where no panel can take the Sidebar back (no creases), Expand opens it over the panes instead.
        var sheet by rememberSaveable { mutableStateOf(false) }
        var railHeight by remember { mutableIntStateOf(0) }
        val close = { sheet = false }
        Column(Modifier.fillMaxSize().background(AmpTheme.surfaceRoles.sidebar).onSizeChanged { railHeight = it.height }, horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                plan.sidebarExpands -> AmpIconButton(AmpIcons.PanelLeftOpen, "Expand", { update { it.restored() } }, FleetTags.collapse)
                !sheet -> AmpIconButton(AmpIcons.PanelLeftOpen, "Expand", { sheet = true }, FleetTags.collapse)
                else -> Box(Modifier.size(AmpTheme.metrics.touch))
            }
            SidebarRail(account, workspace.thread, slot.navigate, slot.openAccounts, Modifier.fillMaxWidth().weight(1f))
        }
        if (sheet && !plan.sidebarExpands) {
            val sheetSlot = PaneSlot(
                selected = slot.selected,
                navigate = { dest -> close(); slot.navigate(dest) },
                openAccounts = { anchor -> close(); slot.openAccounts(anchor) },
                threadMenu = slot.threadMenu,
                settings = slot.settings,
                windowControls = slot.windowControls,
                terminalInNav = slot.terminalInNav,
                collapseSidebar = close,
                notifications = slot.notifications,
                back = { close(); slot.back() },
            )
            Popup(alignment = Alignment.TopStart, onDismissRequest = close, properties = PopupProperties(focusable = true)) {
                val height = with(LocalDensity.current) { railHeight.toDp() }
                Row(Modifier.fillMaxWidth().height(height).tagsAsResourceIds()) {
                    CompositionLocalProvider(LocalPaneSpan provides column.startDp.dp..column.startDp.dp + AmpTheme.metrics.sidebarSheet, LocalPaneSurface provides AmpTheme.surfaceRoles.sidebar) {
                        PaneHost(PaneDestination.Sidebar, account, sheetSlot, Modifier.width(AmpTheme.metrics.sidebarSheet).fillMaxHeight())
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().background(AmpTheme.colors.surfaces.scrim)
                            .clickable(interactionSource = null, indication = null, onClickLabel = "Close the Sidebar", onClick = close),
                    )
                }
            }
        }
        return
    }
    val role = column.role
    val creases = plan.creasesDp.filter { it > column.startDp + 1f && it < column.endDp - 1f }.map { (it - column.startDp).dp }
    val surface = when (role) {
        SlotRole.Nav -> AmpTheme.surfaceRoles.sidebar
        SlotRole.Machine, SlotRole.SecondMachine -> AmpTheme.surfaceRoles.machine
        SlotRole.Thread, SlotRole.Puck -> AmpTheme.surfaceRoles.thread
    }
    val latestUpdate by rememberUpdatedState(update)
    val latestPlan by rememberUpdatedState(plan)
    val drops = remember(role) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                val thread = (0 until clip.itemCount).firstNotNullOfOrNull { threadIn(clip.getItemAt(it).text ?: "") } ?: return false
                latestUpdate { it.drop(thread, role, latestPlan) }
                return true
            }
        }
    }
    CompositionLocalProvider(LocalPaneCreases provides creases, LocalPaneSpan provides column.startDp.dp..column.endDp.dp, LocalPaneSurface provides surface) {
        Column(
            Modifier.fillMaxSize()
                .testTag(LayoutTags.column(role.name.lowercase()))
                .semantics { selected = role == workspace.focus }
                .pointerInput(role) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        latestUpdate { if (it.focus == role) it else it.copy(focus = role) }
                    }
                }
                .dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) }, target = drops),
        ) {
            (plan.switcher as? Switcher.TitleTabs)?.let { tabs ->
                val selectedIndex = tabs.items.indexOf(workspace.selected()).coerceAtLeast(0)
                AmpTabRow(
                    tabs.items.map { AmpTab(it.label, ShellTags.nav(it.name.lowercase())) },
                    selectedIndex,
                    onSelect = { index -> update { it.select(tabs.items[index]) } },
                    modifier = Modifier.fillMaxWidth().background(surface),
                )
            }
            val dest = workspace.top(role)
            val measure = AmpTheme.metrics.readingMeasure
            val readingOffset = if (dest is PaneDestination.Thread) column.readingOffsetDp(plan.creasesDp, measure.value) else null
            Box(Modifier.weight(1f).fillMaxWidth().background(surface)) {
                val paneModifier = readingOffset?.let { Modifier.padding(start = it.dp).width(measure).fillMaxHeight() } ?: Modifier.fillMaxSize()
                if (dest == null) EmptyColumn(role, account, slot) else PaneHost(dest, account, slot, paneModifier)
            }
        }
    }
}

@Composable
private fun EmptyColumn(role: SlotRole, account: AccountScope, slot: PaneSlot) {
    when (role) {
        SlotRole.Thread -> EmptyThreadPane(account, slot.threadMenu, Modifier.fillMaxSize())
        SlotRole.Machine, SlotRole.SecondMachine -> EmptyMachinePane(Modifier.fillMaxSize())
        SlotRole.Nav, SlotRole.Puck -> error("$role always has a destination")
    }
}

/** The window controls a machine pane's header offers for [role]'s column, in [paneControls] order. */
private fun windowControls(
    plan: LayoutPlan,
    role: SlotRole,
    workspace: Workspace,
    update: ((Workspace) -> Workspace) -> Unit,
    newWindow: (PaneDestination) -> Unit,
): List<AmpHeaderAction> {
    val dest = workspace.top(role) as? PaneDestination.Machine ?: return emptyList()
    fun action(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) = AmpHeaderAction(icon, label, tag, LayoutTags.paneMenu(tag), onClick = onClick)
    return plan.paneControls.map { control ->
        when (control) {
            PaneControl.Expand -> action(AmpIcons.Maximize2, "Expand", LayoutTags.paneExpand) { update { it.toggleExpand() } }
            PaneControl.Collapse -> action(AmpIcons.Minimize2, "Collapse", LayoutTags.paneCollapse) { update { it.toggleExpand() } }
            PaneControl.Focus -> action(AmpIcons.Focus, "Focus", LayoutTags.paneFocus) { update { it.copy(focus = role).arrange(Arrangement.Focus, onePane = false) } }
            PaneControl.NewWindow -> action(AmpIcons.AppWindow, "New Window", LayoutTags.paneNewWindow) { newWindow(dest) }
        }
    }
}

/**
 * A divider's grip in the window bar. Dragging it moves the divider, shown as a line down the panes through
 * [onDrag], and the release snaps through [LayoutPlan.release].
 */
@Composable
private fun DividerGrip(divider: Divider, onDrag: (Float?) -> Unit, onRelease: (Float) -> Unit) {
    val density = LocalDensity.current.density
    var dragX by remember(divider.xDp) { mutableStateOf<Float?>(null) }
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    fun moveTo(x: Float?) {
        dragX = x
        drag(x)
    }
    val metrics = AmpTheme.metrics
    Box(
        Modifier.offset(x = (dragX ?: divider.xDp).dp - metrics.touch / 2).width(metrics.touch).height(metrics.windowBar)
            .testTag(LayoutTags.divider(divider.index))
            .semantics { contentDescription = "Divider at ${divider.xDp.roundToInt()}dp" }
            .pointerInput(divider) {
                detectHorizontalDragGestures(
                    onDragStart = { moveTo(divider.xDp) },
                    onDragEnd = {
                        dragX?.let(release)
                        moveTo(null)
                    },
                    onDragCancel = { moveTo(null) },
                ) { change, amount ->
                    change.consume()
                    moveTo((dragX ?: divider.xDp) + amount / density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val color = if (dragX != null) AmpTheme.colors.accents.focus else AmpTheme.colors.text.inkFaint
        Box(Modifier.width(metrics.gripThickness).height(metrics.gripLength).background(color, CircleShape))
    }
}

/**
 * Amp's web divider between card panes: a canvas gutter centered on the crease, and on the sidebar's side the sidebar's
 * own 1dp border, which reads as a light edge left of the dark gutter.
 */
@Composable
private fun PaneDivider(x: Dp, sidebarEdge: Boolean) {
    val metrics = AmpTheme.metrics
    val surfaces = AmpTheme.colors.surfaces
    val gutterStart = x - metrics.paneGap / 2
    if (sidebarEdge) Box(Modifier.offset(x = gutterStart - metrics.divider).width(metrics.divider).fillMaxHeight().background(surfaces.line))
    Box(Modifier.offset(x = gutterStart).width(metrics.paneGap).fillMaxHeight().background(surfaces.canvas))
}

/**
 * The cover's bottom navigation, 56dp on the window surface: an 18dp icon over its nav label. The selected item puts its
 * icon on an accentMuted pill and its label in semibold, so it does not rely on the accent color alone.
 */
@Composable
private fun BottomNav(items: List<NavItem>, selected: NavItem?, onSelect: (NavItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(AmpTheme.surfaceRoles.window)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .heightIn(min = AmpTheme.metrics.navBar)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val chosen = item == selected
            val tint = if (chosen) AmpTheme.colors.accents.accent else AmpTheme.colors.text.inkMuted
            Column(
                Modifier.weight(1f).heightIn(min = AmpTheme.metrics.navBar)
                    .testTag(ShellTags.nav(item.name.lowercase()))
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(item) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier.size(AmpTheme.metrics.navIndicatorWidth, AmpTheme.metrics.navIndicatorHeight)
                        .background(if (chosen) AmpTheme.colors.accents.accentMuted else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(AmpTheme.metrics.icon))
                }
                Text(item.label, style = if (chosen) AmpTheme.type.nav.copy(fontWeight = FontWeight.SemiBold) else AmpTheme.type.nav, color = tint)
            }
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> error("the window shell runs inside an Activity")
}

/**
 * The account switcher's span across the window in dp: the sidebar, [marginDp] in from each side, so it never crosses a
 * crease. A rail is too narrow, so the switcher takes the panel the rail sits in, up to the first crease past it. Null when
 * the window shows no sidebar, or a rail with no crease past it; the switcher then starts at the avatar.
 */
internal fun switcherSpan(plan: LayoutPlan, marginDp: Float): ClosedFloatingPointRange<Float>? {
    val nav = plan.columns.firstOrNull { it.role == SlotRole.Nav } ?: return null
    val end = if (nav.rail) plan.creasesDp.firstOrNull { it > nav.endDp } ?: return null else nav.endDp
    return (nav.startDp + marginDp)..(end - marginDp)
}

/**
 * Puts the switcher's top [gap] under the avatar at [anchor], or its bottom [gap] over the avatar when it does not fit
 * below, as with the rail's avatar at the window's foot; failing both, as near the avatar as [area] allows. All values are
 * window pixels, [area] being [popupArea]'s, which the switcher keeps [gap] inside. [start] is the span's left edge; with
 * none the switcher starts at the avatar. With no [anchor], a switcher restored after a configuration change, it sits at
 * the area's top.
 */
internal data class SwitcherPosition(val anchor: IntRect?, val start: Int?, val area: IntRect, val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val height = popupContentSize.height
        val top = area.top + gap
        val bottom = area.bottom - gap
        val left = area.left + gap
        val x = start ?: (anchor?.left ?: left).coerceIn(left, maxOf(left, area.right - gap - popupContentSize.width))
        val y = when {
            anchor == null -> top
            anchor.bottom + gap + height <= bottom -> anchor.bottom + gap
            anchor.top - gap - height >= top -> anchor.top - gap - height
            else -> (anchor.bottom + gap).coerceIn(top, maxOf(top, bottom - height))
        }
        return IntOffset(x, y)
    }
}

/**
 * A focusable Popup, like a menu: its own window, so a tap outside it or Back dismisses and it carries
 * testTagsAsResourceId. Popup offsets are window coordinates, the same as the avatar's bounds.
 */
@Composable
private fun AccountSwitcherPopup(host: AccountsHost, anchor: Rect?, span: ClosedFloatingPointRange<Float>?) {
    val density = LocalDensity.current
    val area = popupArea()
    val gap = AmpTheme.spacing.sm
    val position = remember(anchor, span, area, density) {
        with(density) { SwitcherPosition(anchor?.roundToIntRect(), span?.start?.dp?.roundToPx(), area, gap.roundToPx()) }
    }
    val width = with(density) { span?.let { (it.endInclusive - it.start).dp } ?: minOf(area.width.toDp() - gap * 2, AmpTheme.metrics.accountSwitcher) }
    val maxHeight = with(density) { area.height.toDp() - gap * 2 }
    Popup(popupPositionProvider = position, onDismissRequest = host::dismiss, properties = PopupProperties(focusable = true)) {
        Surface(
            Modifier.tagsAsResourceIds().width(width).heightIn(max = maxHeight),
            shape = RoundedCornerShape(16.dp),
            color = AmpTheme.colors.surfaces.pane,
            shadowElevation = MenuDefaults.ShadowElevation,
            border = BorderStroke(AmpTheme.metrics.divider, AmpTheme.colors.surfaces.line),
        ) {
            AccountSwitcher(host)
        }
    }
}
