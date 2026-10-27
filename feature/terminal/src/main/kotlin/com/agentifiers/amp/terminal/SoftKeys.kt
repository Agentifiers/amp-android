package com.agentifiers.amp.terminal

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.agentifiers.amp.design.TerminalTags
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.vt.VtKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface SoftKeyAction {
    /** [ctrl] forces Ctrl on, for ^C. */
    data class Send(val key: VtKey, val ctrl: Boolean = false) : SoftKeyAction
    data object Ctrl : SoftKeyAction
    data object Alt : SoftKeyAction
    data object Select : SoftKeyAction
    data object Paste : SoftKeyAction
}

private class SoftKey(val label: String, val tag: String, val action: SoftKeyAction, val repeats: Boolean = false)

/** The extra-keys row of terminal.md, "Soft keyboard and the extra-keys row", in order. */
private val softKeys = listOf(
    SoftKey("Esc", TerminalTags.keyEsc, SoftKeyAction.Send(VtKey.Named.Esc)),
    SoftKey("Ctrl", TerminalTags.keyCtrl, SoftKeyAction.Ctrl),
    SoftKey("Alt", TerminalTags.keyAlt, SoftKeyAction.Alt),
    SoftKey("Tab", TerminalTags.keyTab, SoftKeyAction.Send(VtKey.Named.Tab)),
    SoftKey("←", TerminalTags.keyLeft, SoftKeyAction.Send(VtKey.Named.Left), repeats = true),
    SoftKey("↑", TerminalTags.keyUp, SoftKeyAction.Send(VtKey.Named.Up), repeats = true),
    SoftKey("↓", TerminalTags.keyDown, SoftKeyAction.Send(VtKey.Named.Down), repeats = true),
    SoftKey("→", TerminalTags.keyRight, SoftKeyAction.Send(VtKey.Named.Right), repeats = true),
    SoftKey("|", TerminalTags.keyPipe, SoftKeyAction.Send(VtKey.Char('|'.code))),
    SoftKey("~", TerminalTags.keyTilde, SoftKeyAction.Send(VtKey.Char('~'.code))),
    SoftKey("/", TerminalTags.keySlash, SoftKeyAction.Send(VtKey.Char('/'.code))),
    SoftKey("^C", TerminalTags.keyCtrlC, SoftKeyAction.Send(VtKey.Char('c'.code), ctrl = true)),
    SoftKey("Select", TerminalTags.select, SoftKeyAction.Select),
    SoftKey("Paste", TerminalTags.paste, SoftKeyAction.Paste),
)

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L

/** Keys drawn at button height inside 48dp targets; the row scrolls horizontally when the pane is narrower than the 14 keys. */
@Composable
internal fun SoftKeysRow(ctrl: Latch, alt: Latch, onAction: (SoftKeyAction) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(LocalPaneSurface.current).horizontalScroll(rememberScrollState()).testTag(TerminalTags.softkeys),
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
    ) {
        softKeys.forEach { key ->
            when (key.action) {
                SoftKeyAction.Ctrl -> LatchKey(key, ctrl) { onAction(key.action) }
                SoftKeyAction.Alt -> LatchKey(key, alt) { onAction(key.action) }
                else -> if (key.repeats) RepeatingKey(key) { onAction(key.action) } else PlainKey(key) { onAction(key.action) }
            }
        }
    }
}

@Composable
private fun KeyFace(label: String, lit: Boolean, modifier: Modifier) {
    val metrics = AmpTheme.metrics
    Box(modifier.heightIn(min = metrics.touch).widthIn(min = metrics.touch), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = (metrics.touch - metrics.button) / 2)
                .background(if (lit) AmpTheme.colors.accents.accentMuted else AmpTheme.surfaceRoles.buttonSecondary, AmpTheme.shapes.control),
        )
        Text(label, Modifier.padding(horizontal = AmpTheme.spacing.sm), color = AmpTheme.colors.text.ink, style = AmpTheme.type.button, maxLines = 1)
    }
}

@Composable
private fun PlainKey(key: SoftKey, onPress: () -> Unit) {
    KeyFace(key.label, lit = false, Modifier.testTag(key.tag).clickable(role = Role.Button, onClick = onPress))
}

@Composable
private fun LatchKey(key: SoftKey, latch: Latch, onTap: () -> Unit) {
    val modifier = Modifier
        .testTag(key.tag)
        .toggleable(value = latch.active, role = Role.Switch, onValueChange = { onTap() })
        .semantics { stateDescription = latch.state.name.lowercase() }
    KeyFace(key.label, lit = latch.active, modifier)
}

@Composable
private fun RepeatingKey(key: SoftKey, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    val scope = rememberCoroutineScope()
    val modifier = Modifier
        .testTag(key.tag)
        .semantics {
            role = Role.Button
            onClick { press(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                press()
                val repeat = scope.launch {
                    delay(REPEAT_DELAY_MS)
                    while (true) {
                        press()
                        delay(REPEAT_INTERVAL_MS)
                    }
                }
                waitForUpOrCancellation()
                repeat.cancel()
            }
        }
    KeyFace(key.label, lit = false, modifier)
}
