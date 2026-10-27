package com.agentifiers.amp.terminal

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.design.SelectedVisibleRow
import com.agentifiers.amp.design.TerminalTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Machine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Terminal machine tab over the thread's shared TerminalSessions: session chips for tabs 1 to 5 and the selected
 * tab's pane. [tab] picks the tab selected first; the open set is this pane's own. The selected session's actions go to
 * the machine header's More Actions through [publishMenu].
 */
@Composable
fun TerminalTab(
    dest: PaneDestination.Machine,
    tab: MachineTab.Terminal,
    scope: AccountScope,
    modifier: Modifier = Modifier,
    publishMenu: (List<AmpMenuItem>) -> Unit,
) {
    val machine = remember(scope, dest.thread) { scope.thread(dest.thread).machine }
    val initial = TerminalTabNumber.of(tab.session) ?: TerminalTabNumber.First
    var open by rememberSaveable(dest.thread) { mutableStateOf(listOf(TerminalTabNumber.First.value, initial.value).distinct()) }
    var selected by rememberSaveable(dest.thread) { mutableIntStateOf(initial.value) }
    LaunchedEffect(initial) {
        if (initial.value !in open) open = (open + initial.value).sorted()
        selected = initial.value
    }
    val openTabs = open.mapNotNull(TerminalTabNumber::of)
    val current = TerminalTabNumber.of(selected)?.takeIf { it in openTabs }
    val openNew = {
        TerminalTabNumber.all.firstOrNull { it !in openTabs }?.let { next ->
            open = (open + next.value).sorted()
            selected = next.value
        }
        Unit
    }
    val close = { number: TerminalTabNumber ->
        machine.terminal(number.key).close()
        val remaining = openTabs - number
        open = remaining.map { it.value }
        if (selected == number.value) (remaining.lastOrNull { it < number } ?: remaining.firstOrNull())?.let { selected = it.value }
    }
    val chips: @Composable RowScope.() -> Unit = {
        SessionChips(machine, openTabs, current, onSelect = { selected = it.value }, onClose = close, onNew = openNew)
    }
    if (current == null) {
        SideEffect { publishMenu(emptyList()) }
        Column(modifier.fillMaxSize().background(LocalPaneSurface.current)) {
            SessionStrip(chips)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                AmpEmptyState(AmpIcons.Terminal, "No terminal open", "Open a terminal to run commands on this thread's machine.", TerminalTags.empty) {
                    AmpButton("New terminal", openNew, TerminalTags.emptyNew, rank = AmpButtonRank.Primary)
                }
            }
        }
        return
    }
    key(current) {
        TerminalPane(
            thread = dest.thread,
            tab = current,
            session = remember(machine, current) { machine.terminal(current.key) },
            tabs = TabControls(openNew = openNew, closeCurrent = { close(current) }),
            header = chips,
            publishMenu = publishMenu,
            modifier = modifier,
        )
    }
}

/** The pane's one toolbar row: session chips, then [trailing] (status and Copy) when a session is open. */
@Composable
internal fun SessionStrip(chips: @Composable RowScope.() -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(LocalPaneSurface.current).heightIn(min = AmpTheme.metrics.subRow)
            .padding(start = AmpTheme.spacing.paneX - AmpTheme.spacing.chipPadX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = chips)
        trailing()
    }
}

@Composable
private fun SessionChips(
    machine: Machine,
    open: List<TerminalTabNumber>,
    selected: TerminalTabNumber?,
    onSelect: (TerminalTabNumber) -> Unit,
    onClose: (TerminalTabNumber) -> Unit,
    onNew: () -> Unit,
) {
    SelectedVisibleRow(open.indexOf(selected).coerceAtLeast(0), open.size + 1) { index, place ->
        val number = open.getOrNull(index)
        if (number == null) {
            AmpIconButton(AmpIcons.Plus, "New terminal", onNew, TerminalTags.tabNew, place, enabled = open.size < TerminalTabNumber.MAX)
            return@SelectedVisibleRow
        }
        val title by remember(machine, number) {
            machine.terminal(number.key).screen.map { it.title }.distinctUntilChanged()
        }.collectAsState("")
        val label = number.label(title)
        Row(place, verticalAlignment = Alignment.CenterVertically) {
            AmpChip(label, number == selected, onClick = { onSelect(number) }, testTag = TerminalTags.tab(number.value))
            AmpIconButton(
                AmpIcons.X,
                "Close $label",
                { onClose(number) },
                TerminalTags.tabClose(number.value),
                glyph = AmpTheme.metrics.iconSmall,
                tint = AmpTheme.colors.text.inkFaint,
            )
        }
    }
}
