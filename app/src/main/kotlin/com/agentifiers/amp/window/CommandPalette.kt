package com.agentifiers.amp.window

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpSearchField
import com.agentifiers.amp.design.components.currentPanelSpan

/** Filters by title and category, ignoring case (screens.md "Command palette"). */
internal fun paletteMatches(commands: List<WindowCommand>, query: String): List<WindowCommand> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return commands.filter { command -> words.all { word -> "${command.category}: ${command.title}".lowercase().contains(word) } }
}

/**
 * The palette over the window's commands. Enter runs the first match, Esc closes, a tap runs a row. It opens from no
 * pane, so it sits in the window's widest panel ([currentPanelSpan]), the middle one on the TriFold; a tap beside it closes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CommandPalette(commands: List<WindowCommand>, onRun: (WindowCommand) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = paletteMatches(commands, query)
    val focus = remember { FocusRequester() }
    val span = currentPanelSpan(maxWidth = 560.dp)
    val margin = AmpTheme.spacing.sm
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
            Column(
                Modifier.semantics { testTagsAsResourceId = true }.offset(x = span.start + margin).width(span.width - margin * 2)
                    .background(AmpTheme.colors.surfaces.pane, RoundedCornerShape(12.dp)).padding(AmpTheme.spacing.md).testTag(ShellTags.palette),
            ) {
                AmpSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Type a command",
                    testTag = ShellTags.paletteInput,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { matches.firstOrNull()?.let(onRun) }),
                    modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> { matches.firstOrNull()?.let(onRun); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    },
                )
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = AmpTheme.spacing.sm)) {
                    items(matches, key = { it.id }) { command ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).clickable { onRun(command) }.padding(horizontal = AmpTheme.spacing.sm)
                                .testTag(ShellTags.paletteItem(command.id)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${command.category.replaceFirstChar(Char::uppercase)}: ${command.title}", style = AmpTheme.type.body,
                                color = AmpTheme.colors.text.ink, modifier = Modifier.weight(1f),
                            )
                            command.chords.firstOrNull()?.let { Text(it.label, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted) }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
