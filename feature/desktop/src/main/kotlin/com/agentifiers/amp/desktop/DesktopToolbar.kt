package com.agentifiers.amp.desktop

import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.design.AmpTheme
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.DesktopTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.waymote.Control
import com.agentifiers.amp.waymote.Displays
import com.agentifiers.amp.waymote.SessionState
import com.agentifiers.amp.waymote.WaymoteSession
import kotlinx.coroutines.launch

/** Watch or take over, input mode, soft keyboard, display picker, and clipboard both ways. */
@Composable
internal fun DesktopToolbar(
    state: SessionState,
    session: WaymoteSession,
    mode: InputMode,
    onMode: (InputMode) -> Unit,
    keyboardOpen: Boolean,
    keyboardFocus: FocusRequester,
    onKeyboardOpen: (Boolean) -> Unit,
) {
    val streaming = state as? SessionState.Streaming
    val control = streaming?.control
    val active = control == Control.Active
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.paneX)) {
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.subRow),
            horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.actionGap),
            verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.toolGap),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Segmented(
                listOf(Segment(false, "Watch", DesktopTags.handBack), Segment(true, "Take over", DesktopTags.takeOver)),
                selected = control == Control.Active || control == Control.Requesting,
                onSelect = { if (it) session.takeOver() else session.handBack() },
                enabled = streaming != null,
            )
            Segmented(
                listOf(Segment(InputMode.Direct, "Direct", DesktopTags.inputDirect), Segment(InputMode.Trackpad, "Trackpad", DesktopTags.inputTrackpad)),
                selected = mode,
                onSelect = onMode,
                modifier = Modifier.testTag(DesktopTags.inputMode),
            )
            AmpButton(
                if (keyboardOpen) "Hide keyboard" else "Keyboard",
                {
                    if (keyboardOpen) {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    } else {
                        keyboardFocus.requestFocus()
                        keyboard?.show()
                    }
                },
                DesktopTags.keyboard,
                enabled = active,
            )
            streaming?.displays?.let { DisplayPicker(it, session) }
            AmpButton(
                "Send clipboard",
                {
                    scope.launch {
                        clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { session.writeClipboard(it.toString()) }
                    }
                },
                DesktopTags.sendClipboard,
                enabled = active,
            )
            KeyboardField(session, keyboardFocus, onKeyboardOpen)
        }
        streaming?.remoteClipboard?.let { text ->
            Row(Modifier.heightIn(min = AmpTheme.metrics.subRow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.actionGap)) {
                Text(
                    "Desktop copied: $text",
                    color = AmpTheme.colors.text.inkMuted,
                    style = AmpTheme.type.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(DesktopTags.remoteClipboard),
                )
                AmpButton(
                    "Copy",
                    { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Desktop", text))) } },
                    DesktopTags.copyRemoteClipboard,
                )
            }
        }
    }
}

/** One option of a [Segmented] control: the [value] it selects, its label, and its node's tag. */
private class Segment<T>(val value: T, val label: String, val testTag: String)

/**
 * Exclusive options side by side, each a 32dp segment inside a 48dp target: the [selected] one in the selection style
 * (accentMuted fill, accent label), the rest on the Secondary button fill. The Tab role reports `selected`, as AmpChip does.
 */
@Composable
private fun <T> Segmented(segments: List<Segment<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = AmpTheme.colors
    val corner = AmpTheme.shapes.control
    Row(modifier.selectableGroup()) {
        segments.forEachIndexed { index, segment ->
            val chosen = segment.value == selected
            val shape = when (index) {
                0 -> corner.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
                segments.lastIndex -> corner.copy(topStart = ZeroCornerSize, bottomStart = ZeroCornerSize)
                else -> RectangleShape
            }
            Box(
                Modifier.testTag(segment.testTag)
                    .minimumInteractiveComponentSize()
                    .heightIn(min = AmpTheme.metrics.button)
                    .clip(shape)
                    .background(if (chosen) colors.accents.accentMuted else AmpTheme.surfaceRoles.buttonSecondary)
                    .selectable(selected = chosen, enabled = enabled, role = Role.Tab) { if (!chosen) onSelect(segment.value) }
                    .padding(horizontal = AmpTheme.spacing.buttonPadX),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    segment.label,
                    style = AmpTheme.type.button,
                    color = when {
                        !enabled -> colors.text.inkFaint
                        chosen -> colors.accents.accent
                        else -> colors.text.ink
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DisplayPicker(displays: Displays, session: WaymoteSession) {
    var open by remember { mutableStateOf(false) }
    val selected = displays.list.firstOrNull { it.id == displays.selected }
    Box {
        AmpButton(
            selected?.name ?: "Display",
            { open = true },
            DesktopTags.displayPicker,
            enabled = displays.list.size > 1 && !displays.switching,
        )
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            displays.list.forEach { display ->
                DropdownMenuItem(
                    text = { Text("${display.name} · ${display.width}×${display.height}", color = AmpTheme.colors.text.ink) },
                    onClick = {
                        open = false
                        session.selectDisplay(display.id)
                    },
                    modifier = Modifier.testTag(DesktopTags.display(display.id)),
                )
            }
        }
    }
}

/**
 * An invisible text field that holds the soft keyboard's focus. Compositions go out as preedit, commits as text,
 * and the field empties after each commit; keys the IME sends as key events (Backspace, Enter) reach the pane's
 * key handler instead.
 */
@Composable
private fun KeyboardField(session: WaymoteSession, focus: FocusRequester, onOpen: (Boolean) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            val composing = next.composition
            value = if (composing != null) {
                session.preedit(next.text.substring(composing.start, composing.end))
                next
            } else {
                if (next.text.isNotEmpty()) session.type(next.text)
                TextFieldValue("")
            }
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus).onFocusChanged { onOpen(it.isFocused) }.testTag(DesktopTags.keyboardInput),
    )
}
