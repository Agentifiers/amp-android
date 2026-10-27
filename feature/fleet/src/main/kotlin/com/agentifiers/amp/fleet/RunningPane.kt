package com.agentifiers.amp.fleet

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.design.components.AmpPaneHeader
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.sync.api.AccountScope

/** Live monitor of working threads (amp top parity): title, status, and time since the last activity. */
@Composable
fun RunningPane(dest: PaneDestination.Running, scope: AccountScope, navigate: (PaneDestination) -> Unit, modifier: Modifier = Modifier) {
    val running by scope.fleet.running.collectAsStateWithLifecycle()
    val now = rememberNow()
    val spacing = AmpTheme.spacing
    Column(modifier.background(LocalPaneSurface.current).testTag(ShellTags.pane(dest.route)).padding(horizontal = spacing.listInset)) {
        AmpPaneHeader(title = "Running", testTag = FleetTags.runningHeader, kind = "running")
        if (running.isEmpty()) {
            Text(
                "No threads are working.",
                color = AmpTheme.colors.text.inkMuted,
                style = AmpTheme.type.body,
                modifier = Modifier.padding(horizontal = spacing.paneX - spacing.listInset, vertical = spacing.sm),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(running, key = { it.id.raw }) { summary ->
                ThreadListRow(summary, now, onClick = { navigate(PaneDestination.Thread(summary.id)) }, testTag = FleetTags.runningRow(summary.id))
            }
        }
    }
}
