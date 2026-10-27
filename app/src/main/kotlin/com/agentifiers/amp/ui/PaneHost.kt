package com.agentifiers.amp.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.WindowNotifications
import com.agentifiers.amp.design.components.AmpHeaderAction
import com.agentifiers.amp.fleet.ActivityPane
import com.agentifiers.amp.fleet.NewThreadPane
import com.agentifiers.amp.fleet.RunningPane
import com.agentifiers.amp.fleet.NotificationsOffNotice
import com.agentifiers.amp.fleet.SidebarPane
import com.agentifiers.amp.model.NotificationAccess
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.puck.PuckPane
import com.agentifiers.amp.settings.SettingsHost
import com.agentifiers.amp.settings.SettingsPane
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.thread.ThreadMenuItem
import com.agentifiers.amp.thread.ThreadPane
import com.agentifiers.amp.web.WebPane
import com.agentifiers.amp.web.rememberOrbProviderReader
import com.agentifiers.amp.window.WindowOpening
import com.agentifiers.amp.window.openNewWindow

/**
 * What a slot passes to the pane it hosts: the window's selection, navigation by value, and window-level actions.
 * [windowControls] are the column's Expand or Collapse, Focus, and New Window, which a machine pane puts in its
 * header. [terminalInNav] says the window's switcher has its own Terminal item, so the machine strip leaves it out.
 * [collapseSidebar] is null where collapsing the Sidebar would free no room. [back] is the window's Back.
 */
internal class PaneSlot(
    val selected: ThreadId?,
    val navigate: (PaneDestination) -> Unit,
    val openAccounts: (anchor: Rect) -> Unit,
    val threadMenu: List<ThreadMenuItem>,
    val settings: SettingsHost,
    val windowControls: List<AmpHeaderAction>,
    val terminalInNav: Boolean,
    val collapseSidebar: (() -> Unit)?,
    val notifications: WindowNotifications,
    val back: () -> Unit,
)

/** Every pane, one exhaustive `when`. A new PaneDestination without a branch fails to compile. */
@Composable
internal fun PaneHost(dest: PaneDestination, scope: AccountScope, slot: PaneSlot, modifier: Modifier) {
    when (dest) {
        is PaneDestination.Sidebar -> {
            val activity = LocalActivity.current
            val access by slot.notifications.access.collectAsStateWithLifecycle()
            val dismissed by slot.notifications.noticeDismissed.collectAsStateWithLifecycle()
            val notice = if (access is NotificationAccess.Allowed || dismissed) null else NotificationsOffNotice(slot.notifications::turnOn, slot.notifications::dismissNotice)
            SidebarPane(dest, scope, slot.selected, slot.navigate, slot.openAccounts, { opened -> activity?.openNewWindow(WindowOpening(opened, alone = true)) }, slot.collapseSidebar, notice, modifier)
        }
        is PaneDestination.Activity -> ActivityPane(dest, scope, slot.navigate, modifier)
        is PaneDestination.Running -> RunningPane(dest, scope, slot.navigate, modifier)
        is PaneDestination.NewThread -> NewThreadPane(dest, scope, slot.navigate, rememberOrbProviderReader(), modifier)
        is PaneDestination.Thread -> ThreadPane(dest, scope, slot.threadMenu, slot.navigate, modifier)
        is PaneDestination.Machine -> MachinePane(dest, scope, slot.navigate, slot.windowControls, slot.terminalInNav, modifier)
        is PaneDestination.Puck -> PuckPane(dest, scope, slot.selected, slot.navigate, modifier)
        is PaneDestination.Settings -> SettingsPane(dest, scope, slot.settings, modifier)
        is PaneDestination.Web -> WebPane(dest, scope, onDone = slot.back, modifier)
    }
}
