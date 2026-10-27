package com.agentifiers.amp.fleet.newthread

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpSearchField
import com.agentifiers.amp.design.components.AmpSheet
import com.agentifiers.amp.design.components.AmpTab
import com.agentifiers.amp.design.components.AmpTabRow
import com.agentifiers.amp.model.AgentMode
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.ModeSource
import com.agentifiers.amp.sync.api.AccountScope

/**
 * The account's modes plus the chosen runner's, first occurrence of a key wins. Amp Modes ([raw] false) excludes
 * raw models and orders Dial, BuiltIn, Plugin, keeping catalog order within each source.
 */
fun pickerModes(catalog: List<AgentMode>, runnerModes: List<AgentMode>, query: String, raw: Boolean): List<AgentMode> {
    val needle = query.trim()
    return (catalog + runnerModes)
        .distinctBy { it.key }
        .filter { (it.source == ModeSource.RawModel) == raw }
        .filter { mode ->
            needle.isEmpty() || listOfNotNull(mode.display, mode.key, mode.description).any { it.contains(needle, ignoreCase = true) }
        }
        .sortedBy { it.source.ordinal }
}

@Composable
internal fun ModePicker(scope: AccountScope, executor: ExecutorChoice?, selected: AgentMode?, onPick: (AgentMode) -> Unit, onDismiss: () -> Unit) {
    val catalog by scope.catalog.modes.collectAsStateWithLifecycle()
    val runners by scope.catalog.runners.collectAsStateWithLifecycle()
    val runnerModes = (executor as? ExecutorChoice.Runner)?.let { choice -> runners.firstOrNull { it.id == choice.runner }?.modes }.orEmpty()
    var raw by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = pickerModes(catalog, runnerModes, query, raw)
    val noRawModels = raw && pickerModes(catalog, runnerModes, "", raw = true).isEmpty()

    AmpSheet(onDismissRequest = onDismiss) {
        val spacing = AmpTheme.spacing
        val controlsX = Modifier.padding(horizontal = spacing.paneX - spacing.listInset)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = spacing.listInset).padding(bottom = spacing.sm)
                .semantics { testTagsAsResourceId = true }.testTag(ThreadTags.modePicker),
        ) {
            AmpTabRow(
                listOf(AmpTab("Amp Modes", ThreadTags.modeTabAmp), AmpTab("Raw Models", ThreadTags.modeTabRaw)),
                selected = if (raw) 1 else 0,
                onSelect = { raw = it == 1 },
                modifier = controlsX,
            )
            AmpSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search",
                testTag = ThreadTags.modeSearch,
                modifier = controlsX.padding(vertical = spacing.searchMarginY),
            )
            if (noRawModels) {
                Text("Raw models are available on web.", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.body, modifier = controlsX.testTag(ThreadTags.modeRawOpenWeb))
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.key }) { mode -> ModeRow(mode, mode.key == selected?.key) { onPick(mode) } }
            }
        }
    }
}

/** A mode on the list row's rhythm, with its description free to wrap and its model on a meta line. */
@Composable
private fun ModeRow(mode: AgentMode, chosen: Boolean, onClick: () -> Unit) {
    val colors = AmpTheme.colors
    Column(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.row).clip(AmpTheme.shapes.control)
            .background(if (chosen) AmpTheme.surfaceRoles.selected else Color.Transparent)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick).testTag(ThreadTags.modeOption(mode.key))
            .padding(horizontal = AmpTheme.spacing.rowPadX, vertical = AmpTheme.spacing.xs),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(mode.display, color = if (chosen) colors.accents.accent else colors.text.ink, style = AmpTheme.type.rowTitle)
        mode.description?.let { Text(it, color = colors.text.inkMuted, style = AmpTheme.type.secondary) }
        mode.model?.let { Text(it, color = colors.text.inkMuted, style = AmpTheme.type.meta) }
    }
}
