package com.agentifiers.amp.changes

import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.ChangesTags
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpSheet
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.ChangeScope
import com.agentifiers.amp.model.ChangesView
import com.agentifiers.amp.model.DiffComment

private fun ChangesButton.tag(): String = when (this) {
    ChangesButton.Ship -> ChangesTags.ship
    ChangesButton.Review -> ChangesTags.review
    ChangesButton.Restack -> ChangesTags.restack
    ChangesButton.Rebase -> ChangesTags.rebase
}

/** An action bar button, which moves into the machine header's More Actions when the bar runs out of room. */
private class BarAction(val label: String, val tag: String, val rank: AmpButtonRank, val enabled: Boolean, val onClick: () -> Unit)

/**
 * The 48dp bar under Changes: the scope picker, Ship, then Review, Restack, Rebase, and Sync in that order for as many as
 * fit whole. Ship always shows, enabled or not, and the picker ellipsizes to leave it room. The rest go to
 * [publishOverflow] as menu items tagged `<tag>-menu`.
 */
@Composable
internal fun ActionBar(view: ChangesView.Ready, comments: List<DiffComment>, c: PaneController, publishOverflow: (List<AmpMenuItem>) -> Unit) {
    val scope = currentScope(view)
    val states = remember(view.facts, scope, comments) {
        ChangesButton.entries.associateWith { availability(it, view.facts, scope, comments) }
    }
    fun bar(button: ChangesButton): BarAction {
        val available = states.getValue(button) as? ActionAvailability.Available
        val rank = if (button == ChangesButton.Ship && available != null) AmpButtonRank.Primary else AmpButtonRank.Secondary
        return BarAction(buttonLabel(button, view.facts), button.tag(), rank, available != null) { available?.let { c.confirm = it.confirm } }
    }
    val ship = bar(ChangesButton.Ship)
    val optional = ChangesButton.entries.filter { it != ChangesButton.Ship }.map(::bar) +
        listOfNotNull(view.facts.syncCommand?.let { BarAction("Sync", ChangesTags.sync, AmpButtonRank.Secondary, true) { c.syncOpen = true } })
    var fitted by remember { mutableIntStateOf(optional.size) }
    SideEffect {
        publishOverflow(optional.drop(fitted).map { AmpMenuItem(it.label, ChangesTags.actionMenu(it.tag), enabled = it.enabled, onClick = it.onClick) })
    }
    val spacing = AmpTheme.spacing
    Column(Modifier.fillMaxWidth().background(LocalPaneSurface.current).padding(horizontal = spacing.sm)) {
        ActionRow(spacing.actionGap, onFit = { fitted = it }, Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.actionBar)) {
            ScopePicker(view, c)
            (listOf(ship) + optional).forEach { AmpButton(it.label, it.onClick, it.tag, rank = it.rank, enabled = it.enabled) }
        }
        val reasons = states.values.filterIsInstance<ActionAvailability.Blocked>().map { it.reason }.distinct()
        if (reasons.isNotEmpty()) {
            Text(
                reasons.joinToString("\n"), style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.xs, end = spacing.xs, bottom = spacing.sm).testTag(ChangesTags.actionReason),
            )
        }
    }
}

/**
 * One line, vertically centered: the picker (first child), then the primary action (second), which always fits because
 * the picker is capped to the width it leaves, then each later child while it fits whole. Every pass measures afresh and
 * reports through [onFit] how many later children it placed; unplaced children stay out of the accessibility tree.
 */
@Composable
private fun ActionRow(gap: Dp, onFit: (Int) -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val primary = measurables[1].measure(loose)
        val picker = measurables[0].measure(loose.copy(maxWidth = (constraints.maxWidth - gapPx - primary.width).coerceAtLeast(0)))
        var used = picker.width + gapPx + primary.width
        val placed = mutableListOf(picker, primary)
        for (measurable in measurables.drop(2)) {
            val next = measurable.measure(loose)
            used += gapPx + next.width
            if (used > constraints.maxWidth) break
            placed += next
        }
        onFit(placed.size - 2)
        val height = maxOf(constraints.minHeight, placed.maxOf { it.height })
        layout(constraints.maxWidth, height) {
            var x = 0
            placed.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gapPx
            }
        }
    }
}

@Composable
internal fun ConfirmSheet(request: ConfirmRequest, c: PaneController) {
    var prompt by remember(request) { mutableStateOf(request.prompt) }
    AmpSheet(onDismissRequest = { c.confirm = null }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.lg, vertical = AmpTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            Text(
                request.title, style = AmpTheme.type.paneTitle, color = AmpTheme.colors.text.ink,
                modifier = Modifier.testTag(ChangesTags.confirmTitle).semantics { heading() },
            )
            Text(request.target, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(ChangesTags.confirmTarget))
            AmpTextField(
                value = prompt,
                onValueChange = { prompt = it },
                testTag = ChangesTags.confirmPrompt,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                minLines = 4,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm, Alignment.End)) {
                AmpButton("Cancel", { c.confirm = null }, ChangesTags.confirmCancel)
                AmpButton(request.sendLabel, { c.sendConfirm(request, prompt) }, ChangesTags.confirmSend, rank = AmpButtonRank.Primary, enabled = prompt.isNotBlank())
            }
        }
    }
}

@Composable
internal fun SyncSheet(command: String, c: PaneController) {
    AmpSheet(onDismissRequest = { c.syncOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.lg, vertical = AmpTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            Text("Sync to Your Machine", style = AmpTheme.type.paneTitle, color = AmpTheme.colors.text.ink, modifier = Modifier.semantics { heading() })
            SelectionContainer {
                Text(command, style = Mono, modifier = Modifier.fillMaxWidth().background(AmpTheme.colors.surfaces.raised).padding(AmpTheme.spacing.sm).testTag(ChangesTags.syncCommand))
            }
            AmpButton("Copy", { c.copy(command) }, ChangesTags.syncCopy)
        }
    }
}

@Composable
private fun ScopePicker(view: ChangesView.Ready, c: PaneController) {
    var open by remember { mutableStateOf(false) }
    val ahead = aheadOfBase(view)
    val described = if (ahead == null) Modifier else Modifier.semantics { contentDescription = "${currentScope(view).label} · $ahead" }
    Box {
        AmpChip(scopeSummary(view), selected = false, onClick = { open = true }, testTag = ChangesTags.scope, modifier = described, trailingIcon = AmpIcons.ChevronDown)
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (ahead != null) {
                Text(
                    ahead, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted,
                    modifier = Modifier.padding(horizontal = AmpTheme.spacing.md, vertical = AmpTheme.spacing.sm).testTag(ChangesTags.scopeAheadOfBase),
                )
            }
            view.scopes.forEach { option ->
                val chosen = option.id == view.scope
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(option.label, color = if (chosen) AmpTheme.colors.accents.accent else AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (option is ChangeScope.Commit && option.isHead) Text("HEAD", style = AmpTheme.type.mono, color = AmpTheme.colors.text.inkMuted)
                        }
                    },
                    onClick = {
                        open = false
                        c.selectScope(option.id)
                    },
                    modifier = Modifier.testTag(ChangesTags.scopeOption(option.id.raw)).semantics { selected = chosen },
                )
            }
        }
    }
}
