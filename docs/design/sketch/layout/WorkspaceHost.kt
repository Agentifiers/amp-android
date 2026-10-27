// :layout  package amp.layout.compose
// Compose host for a LayoutPlan. :layout never imports a feature: :app passes the content of each slot
// as a lambda (app/AppShell.kt PaneHost, an exhaustive `when` over PaneDestination).
package amp.layout.compose

import amp.layout.*
import amp.model.PaneDestination
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lays out the window bar, chrome, slots and draggable dividers. Each slot: Modifier.testTag(slot.testTag),
 * its own back stack (predictive back pops the focused slot first), and drag-and-drop targets (drop a
 * thread row on a slot to open it there; drop a file into a Thread slot to attach). The window bar
 * (plan.windowBar) sits outside every pane and carries the arrangement menu, the palette button and,
 * in Focus, the exit button. Chrome placed by this host stays out of plan.avoidBands. Root semantics:
 * testTagsAsResourceId = true is set once in :app.
 */
@Composable
fun AdaptiveWorkspace(
    plan: LayoutPlan,
    pane: @Composable (dest: PaneDestination, modifier: Modifier) -> Unit,
    onWorkspaceChange: (Workspace) -> Unit,
    onOpenPalette: () -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO()
