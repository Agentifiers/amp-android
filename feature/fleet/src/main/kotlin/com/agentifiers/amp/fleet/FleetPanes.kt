package com.agentifiers.amp.fleet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.agentifiers.amp.fleet.newthread.NewThreadComposer
import com.agentifiers.amp.model.OrbProviderSource
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.sync.api.AccountScope

/** New thread: composer plus executor, mode, and visibility pickers. [orbProviders] lists the Self-Hosted providers. */
@Composable
fun NewThreadPane(dest: PaneDestination.NewThread, scope: AccountScope, navigate: (PaneDestination) -> Unit, orbProviders: OrbProviderSource, modifier: Modifier = Modifier) {
    NewThreadComposer(dest, scope, navigate, orbProviders, modifier)
}
