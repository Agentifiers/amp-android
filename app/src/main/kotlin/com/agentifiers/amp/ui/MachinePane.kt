package com.agentifiers.amp.ui

import com.agentifiers.amp.design.components.AmpDropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.changes.ChangesTab
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LayoutTags
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpHeaderAction
import com.agentifiers.amp.design.components.AmpHeaderRow
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.components.AmpTab
import com.agentifiers.amp.design.components.AmpTabRow
import com.agentifiers.amp.design.components.HeaderFit
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.desktop.DesktopTab
import com.agentifiers.amp.files.FilesTab
import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.ExecutorStatus
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.space.SpaceTab
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.terminal.TerminalTab
import com.agentifiers.amp.web.PortalTab

/** One machine tab in the strip: its MachineTab key, label, icon, test tag, the tab it opens, and when the strip shows it. */
internal class MachineTabEntry(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val tag: String,
    val shown: (ExecutorStatus) -> Boolean = { true },
    val open: () -> MachineTab,
)

/** The tab strip, in order, one line per tab. */
internal val MachineTabs: List<MachineTabEntry> = listOf(
    MachineTabEntry("changes", "Changes", AmpIcons.GitCompare, ShellTags.tabChanges) { MachineTab.Changes(focusPath = null) },
    MachineTabEntry("files", "Files", AmpIcons.Folder, ShellTags.tabFiles) { MachineTab.Files(path = null) },
    MachineTabEntry("terminal", "Terminal", AmpIcons.Terminal, ShellTags.tabTerminal) { MachineTab.Terminal(session = null) },
    MachineTabEntry("portal", "Portal", AmpIcons.Globe, ShellTags.tabPortal) { MachineTab.Portal(port = null) },
    MachineTabEntry("space", "Space", AmpIcons.Users, ShellTags.tabSpace) { MachineTab.Space },
    MachineTabEntry("desktop", "Desktop", AmpIcons.Monitor, ShellTags.tabDesktop, shown = { DesktopTarget.Orb in (it as? ExecutorStatus.Attached)?.desktops.orEmpty() }) {
        MachineTab.Desktop(DesktopTarget.Orb)
    },
)

internal fun machineTabFor(key: String): MachineTab = (MachineTabs.firstOrNull { it.key == key } ?: MachineTabs.first()).open()

/** Whether the strip draws [icons] instead of labels, and how many window controls stay beside it. */
internal data class MachineHeaderFit(val icons: Boolean, val controls: HeaderFit)

/**
 * The machine header of a [width] pane. The strip shows text tabs when every label fits whole ([labels] is the
 * labeled strip's full width) with room for one control; otherwise [tabs] icon tabs of [touch] each, after [inset].
 * Of [controls] window controls, those that fit beside the strip stay and the rest move into More. [menu] says the
 * selected tab has items of its own, so More shows and takes a slot whatever fits.
 */
internal fun fitMachineHeader(width: Dp, labels: Dp, tabs: Int, controls: Int, menu: Boolean, touch: Dp, inset: Dp): MachineHeaderFit {
    val icons = labels + touch + inset > width
    val strip = if (icons) inset + touch * tabs else labels
    val slots = ((width - strip - inset) / touch).toInt().coerceAtLeast(0)
    val fit = if (controls + (if (menu) 1 else 0) <= slots) HeaderFit(visible = controls, more = menu) else HeaderFit(visible = (slots - 1).coerceAtLeast(0), more = true)
    return MachineHeaderFit(icons, fit)
}

/**
 * The machine pane: one header row with the tab strip and the window [controls], then the selected tab's feature
 * entry. A thread with no executor has no machine tabs (layout.md "Panes"), so it shows what an executor adds instead;
 * a thread whose orb is asleep shows the wake until the orb connects and the tabs load.
 * The header's More Actions is the pane's only menu: a tab publishes its own items into it. With [terminalInNav] the
 * strip leaves Terminal to the window's switcher, unless the Terminal is the tab showing.
 */
@Composable
internal fun MachinePane(
    dest: PaneDestination.Machine,
    scope: AccountScope,
    navigate: (PaneDestination) -> Unit,
    controls: List<AmpHeaderAction>,
    terminalInNav: Boolean,
    modifier: Modifier,
) {
    val handle = scope.thread(dest.thread)
    val state by handle.state.collectAsStateWithLifecycle()
    val executor = state.executor
    if (executor == ExecutorStatus.None) {
        NoExecutor(navigate, modifier)
        return
    }
    if (executor is ExecutorStatus.OrbOffline) {
        val openWeb = { navigate(PaneDestination.Web(WebSurface.AccountPage(AccountWebPage.ThreadOnWeb, scope.account.value.id, "/${dest.thread.raw}"))) }
        OrbOffline(executor, retry = handle::connectLive, openWeb = openWeb, modifier = modifier)
        return
    }
    val tabMenu = remember(dest.tab.key) { mutableStateOf(emptyList<AmpMenuItem>()) }
    Column(modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane("machine"))) {
        val strip = MachineTabs.filter { it.key == dest.tab.key || it.shown(executor) && !(terminalInNav && it.key == "terminal") }
        val selected = strip.indexOfFirst { it.key == dest.tab.key }.coerceAtLeast(0)
        MachineHeader(strip, selected, { navigate(PaneDestination.Machine(dest.thread, it.open())) }, controls, { tabMenu.value })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            MachineTabContent(dest, scope, navigate) { tabMenu.value = it }
        }
    }
}

@Composable
private fun MachineHeader(strip: List<MachineTabEntry>, selected: Int, onSelect: (MachineTabEntry) -> Unit, controls: List<AmpHeaderAction>, tabMenu: () -> List<AmpMenuItem>) {
    val inset = AmpTheme.spacing.xs
    val labels = rememberLabeledStripWidth(strip)
    AmpHeaderRow(Modifier, testTag = null, kind = "machine") {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val items = tabMenu()
            val fit = fitMachineHeader(maxWidth, labels, strip.size, controls.size, items.isNotEmpty(), AmpTheme.metrics.touch, inset)
            Row(Modifier.fillMaxWidth().padding(end = inset), verticalAlignment = Alignment.CenterVertically) {
                if (fit.icons) {
                    IconTabStrip(strip, selected, onSelect, Modifier.weight(1f).padding(start = inset))
                } else {
                    AmpTabRow(
                        strip.map { AmpTab(it.label, it.tag) },
                        selected,
                        onSelect = { onSelect(strip[it]) },
                        modifier = Modifier.weight(1f),
                        height = AmpTheme.metrics.paneHeader,
                        labelBaseline = AmpTheme.metrics.headerBaseline,
                    )
                }
                controls.take(fit.controls.visible).forEach { AmpIconButton(it.icon, it.label, it.onClick, it.testTag, enabled = it.enabled) }
                if (fit.controls.more) PaneMenu(items, controls.drop(fit.controls.visible))
            }
        }
    }
}

/** The width AmpTabRow takes to show every label in [strip] whole: its edges, each label with its padding, and the gaps. */
@Composable
private fun rememberLabeledStripWidth(strip: List<MachineTabEntry>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = AmpTheme.type.tab
    val spacing = AmpTheme.spacing
    return remember(strip, style, density) {
        val text = strip.sumOf { measurer.measure(it.label, style, maxLines = 1).size.width }
        with(density) { text.toDp() } + (spacing.paneX - spacing.tabPadX) * 2 + spacing.tabPadX * 2 * strip.size + spacing.tabGap * (strip.size - 1)
    }
}

/**
 * One 48dp icon tab per entry, for a pane too narrow for the labels: the selected icon in the accent over a 2dp accent
 * bar, the rest in inkMuted. The label is the tab's content description and its long-press tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconTabStrip(strip: List<MachineTabEntry>, selected: Int, onSelect: (MachineTabEntry) -> Unit, modifier: Modifier) {
    val metrics = AmpTheme.metrics
    val accent = AmpTheme.colors.accents.accent
    Row(modifier.selectableGroup()) {
        strip.forEachIndexed { index, tab ->
            val chosen = index == selected
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(tab.label, style = AmpTheme.type.label) } },
                state = rememberTooltipState(),
            ) {
                Box(
                    Modifier
                        .size(metrics.touch, metrics.paneHeader)
                        .testTag(tab.tag)
                        .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(tab) })
                        .semantics { contentDescription = tab.label }
                        .drawBehind {
                            if (chosen) {
                                val bar = metrics.tabIndicator.toPx()
                                val width = metrics.iconButton.toPx()
                                drawRoundRect(accent, Offset((size.width - width) / 2, size.height - bar), Size(width, bar), CornerRadius(bar, bar))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = if (chosen) accent else AmpTheme.colors.text.inkMuted, modifier = Modifier.size(metrics.icon))
                }
            }
        }
    }
}

/** The tab's own items, then the window controls that did not fit, each control under its own tag plus `-menu`. */
@Composable
private fun PaneMenu(tabItems: List<AmpMenuItem>, moved: List<AmpHeaderAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        AmpIconButton(AmpIcons.EllipsisVertical, "More actions", { open = true }, LayoutTags.paneMore)
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tabItems.forEach { PaneMenuItem(it.label, it.testTag, it.icon, it.enabled) { open = false; it.onClick() } }
            if (tabItems.isNotEmpty() && moved.isNotEmpty()) HorizontalDivider(color = AmpTheme.colors.surfaces.line)
            moved.forEach { PaneMenuItem(it.label, it.menuTestTag, it.icon, it.enabled) { open = false; it.onClick() } }
        }
    }
}

@Composable
private fun PaneMenuItem(label: String, testTag: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit) {
    val colors = AmpTheme.colors
    DropdownMenuItem(
        text = { Text(label, style = AmpTheme.type.body) },
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, Modifier.size(AmpTheme.metrics.icon)) } },
        colors = MenuDefaults.itemColors(
            textColor = colors.text.ink,
            leadingIconColor = colors.text.inkMuted,
            disabledTextColor = colors.text.inkFaint,
            disabledLeadingIconColor = colors.text.inkFaint,
        ),
    )
}

/** Each machine tab's feature entry, one exhaustive `when`. [publishMenu] takes the items a tab adds to the header's More Actions. */
@Composable
private fun MachineTabContent(dest: PaneDestination.Machine, scope: AccountScope, navigate: (PaneDestination) -> Unit, publishMenu: (List<AmpMenuItem>) -> Unit) {
    when (val tab = dest.tab) {
        is MachineTab.Changes -> ChangesTab(dest, tab, scope, navigate, publishMenu = publishMenu)
        is MachineTab.Files -> FilesTab(dest, tab, scope, openWeb = { navigate(PaneDestination.Web(it)) })
        is MachineTab.Terminal -> TerminalTab(dest, tab, scope, publishMenu = publishMenu)
        is MachineTab.Portal -> PortalTab(dest, tab, scope)
        is MachineTab.Desktop -> DesktopTab(dest, tab, scope)
        is MachineTab.Space -> SpaceTab(dest, tab, scope)
    }
}

@Composable
private fun NoExecutor(navigate: (PaneDestination) -> Unit, modifier: Modifier) {
    Box(modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane("machine")), contentAlignment = Alignment.Center) {
        AmpEmptyState(
            icon = AmpIcons.Server,
            title = "No executor on this thread",
            guidance = "Changes, Files, Terminal, and Portal run on an executor: a runner on your machine or an Orb. " +
                "This thread has none. Start a new thread and choose an executor to get them.",
            testTag = ShellTags.machineNoExecutor,
        ) {
            AmpButton("New Thread", { navigate(PaneDestination.NewThread(prefill = null)) }, ShellTags.machineNewThread, rank = AmpButtonRank.Secondary)
        }
    }
}

/** The web's wake guidance while the orb comes back, and the failure with Retry when it does not. */
@Composable
private fun OrbOffline(executor: ExecutorStatus.OrbOffline, retry: () -> Unit, openWeb: () -> Unit, modifier: Modifier) {
    Box(modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane("machine")), contentAlignment = Alignment.Center) {
        when (executor) {
            ExecutorStatus.OrbAsleep, ExecutorStatus.OrbWaking -> AmpEmptyState(
                icon = AmpIcons.Server,
                title = "Waking up the orb",
                guidance = "This thread's orb went to sleep after being idle and is being woken up now. " +
                    "Changes, Files, Terminal, and Portal open once it is back online.",
                testTag = ShellTags.machineOrbWaking,
            ) {
                CircularProgressIndicator(Modifier.size(AmpTheme.metrics.icon), color = AmpTheme.colors.text.inkMuted)
                AmpButton("Open on Web", openWeb, ShellTags.machineOrbOpenWeb, rank = AmpButtonRank.Link)
            }
            is ExecutorStatus.OrbWakeFailed -> AmpEmptyState(
                icon = AmpIcons.CircleAlert,
                title = "The orb didn't wake up",
                guidance = "${executor.reason} Try again, or open the thread on the web.",
                testTag = ShellTags.machineOrbWakeFailed,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                    AmpButton("Retry", retry, ShellTags.machineOrbRetry, rank = AmpButtonRank.Primary)
                    AmpButton("Open on Web", openWeb, ShellTags.machineOrbOpenWeb, rank = AmpButtonRank.Link)
                }
            }
        }
    }
}

@Composable
internal fun EmptyMachinePane(modifier: Modifier) {
    Box(modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane("machine")), contentAlignment = Alignment.Center) {
        AmpEmptyState(
            icon = AmpIcons.Monitor,
            title = "No thread open",
            guidance = "Open a thread to see its changes, files, and terminal here.",
            testTag = ShellTags.machineEmpty,
        )
    }
}
