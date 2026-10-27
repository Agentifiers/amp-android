package com.agentifiers.amp.space

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.SpaceTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.sync.api.AccountScope

/**
 * The Space machine tab. Amp mints Space call tokens only for a signed-in browser session: the token route answers a
 * Bearer API key with the same sign-in redirect as no credential. So the call runs on
 * ampcode.com in the user's browser, and this tab says so instead of offering a join that can never succeed.
 */
@Composable
fun SpaceTab(dest: PaneDestination.Machine, tab: MachineTab.Space, scope: AccountScope, modifier: Modifier = Modifier) {
    val account by scope.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val threadUrl = "${account.server.ampBaseUrl.trimEnd('/')}/threads/${dest.thread.raw}"
    Box(modifier.fillMaxSize().testTag(SpaceTags.pane), contentAlignment = Alignment.TopCenter) {
        AmpEmptyState(
            AmpIcons.Users,
            "Space calls run on ampcode.com",
            "Amp gives a Space call token only to a browser that is signed in to ampcode.com, so the app cannot join " +
                "the call itself. Join opens this thread in your browser, where the call, the camera, and screen sharing work.",
            SpaceTags.reason,
        ) {
            AmpButton(
                "Join on ampcode.com",
                { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(threadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                SpaceTags.openWeb,
                rank = AmpButtonRank.Primary,
            )
        }
    }
}
