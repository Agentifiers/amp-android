package com.agentifiers.amp.window

import com.agentifiers.amp.design.components.AmpDropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LayoutTags
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.layout.Arrangement
import com.agentifiers.amp.layout.WindowBar

/**
 * The 40dp bar above every pane on Medium and wider windows (layout.md "The window bar holds window controls"): the
 * arrangement chooser as quiet text with a chevron, and the Command Palette button, both 40dp tall.
 */
@Composable
internal fun WindowBarRow(bar: WindowBar, onArrange: (Arrangement) -> Unit, onExitFocus: () -> Unit, onPalette: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val spacing = AmpTheme.spacing
    Row(
        modifier.fillMaxWidth().height(AmpTheme.metrics.windowBar).background(AmpTheme.surfaceRoles.window).testTag(ShellTags.windowBar).padding(horizontal = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            // The row shows the short name; the description keeps the control's name for TalkBack and flows.
            Row(
                Modifier.heightIn(min = AmpTheme.metrics.windowBar).clip(AmpTheme.shapes.control)
                    .clickable(role = Role.Button) { open = true }
                    .testTag(ShellTags.windowArrangement)
                    .semantics { contentDescription = "Arrangement: ${bar.selected.name}" }
                    .padding(horizontal = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.xs),
            ) {
                Text(bar.selected.name, style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink, maxLines = 1)
                Icon(AmpIcons.ChevronDown, contentDescription = null, tint = AmpTheme.colors.text.inkMuted, modifier = Modifier.size(AmpTheme.metrics.iconSmall))
            }
            AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                bar.arrangements.forEach { arrangement ->
                    val selected = arrangement == bar.selected
                    DropdownMenuItem(
                        text = { Text(arrangement.name, style = AmpTheme.type.body, color = if (selected) AmpTheme.colors.accents.accent else AmpTheme.colors.text.ink) },
                        onClick = {
                            open = false
                            onArrange(arrangement)
                        },
                        // Compose reports `selected` to accessibility only for tabs (others read as checked), and
                        // agent-device and verification.md check arrangement items with `selected`.
                        modifier = Modifier.testTag(LayoutTags.arrangement(arrangement.name)).semantics {
                            role = Role.Tab
                            this.selected = selected
                        },
                    )
                }
            }
        }
        if (bar.exitFocus) {
            AmpButton("Exit Focus", onExitFocus, ShellTags.windowExitFocus, Modifier.padding(start = AmpTheme.spacing.sm), rank = AmpButtonRank.Secondary, leadingIcon = AmpIcons.Minimize2)
        }
        Spacer(Modifier.weight(1f))
        AmpIconButton(AmpIcons.Command, "Command Palette", onPalette, ShellTags.windowPalette)
    }
}
