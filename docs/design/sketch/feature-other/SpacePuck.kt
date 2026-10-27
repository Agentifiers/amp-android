// :feature:space  package amp.space   +  :feature:puck  package amp.puck
package amp.space

import amp.model.*
import androidx.compose.runtime.Composable

/**
 * Space on the LiveKit Android SDK. Token: POST /api/threads/<id>/space-token {action:"reconcile"}
 * (web-plain: allowed natively, the ban is on web-remote). Bearer acceptance NOT VERIFIED.
 * SpaceAccess is a sum type so the pane can't try to join without a grant:
 */
sealed interface SpaceAccess {
    data class Native(val serverUrl: String, val token: String) : SpaceAccess
    /** 401/403 on Bearer, or policy unknown → AccountWebPage.ThreadOnWeb in the account's WebView profile. Visible, labeled. */
    data object WebOnly : SpaceAccess
    data class Disabled(val reason: String) : SpaceAccess          // workspace kill switch, archived thread, one-Space-per-user
}

/**
 * The Space machine tab (layout.md lists Space among the machine panes). Call UI: participant grid
 * (video tiles), mic/camera/screen-share/leave as real buttons, screen share via MediaProjection,
 * PiP on leave-app, foreground service type microphone|camera|mediaProjection while joined,
 * Bluetooth/route picker. Invite = space-token {action:"invite", userID}. Incoming invites arrive as
 * user-actor spaceInvitationReceived and post on the Other channel. Product.md defers Space to a
 * later release; until then the tab renders SpaceAccess.WebOnly.
 */
@Composable
fun SpaceTab(dest: PaneDestination.Machine, tab: MachineTab.Space, scope: amp.sync.api.AccountScope): Unit = TODO()

// ─────────────────────────────────────────────────────────────────────────────
// :feature:puck
// Puck conversations are threads (collab-apps.md:207). PuckPane(dest: PaneDestination.Puck, scope), called
// from :app's PaneHost, is a ThreadPane with Puck chrome: conversation switcher (PuckDirectory.conversations),
// media presentation viewer (puck_media_presentation), and voice. openPuckThread✓ starts/opens a conversation.
//
// Voice: client_puck_voice_start{version:"v3", downloadFormat:"opus_24k", binaryAudio:true,
// mediaKinds:["audio"]} then client_puck_voice_audio frames (cli tier). Call lifecycle via
// androidx.core:core-telecom CallsManager (self-managed VoIP call: status bar, lock screen, audio
// routes, Bluetooth, background), for parity with iOS's system-call Puck (collab-apps.md:415).
// puck_platform_navigation{thread | thread_view(portal|diffs) | file | portal(url)} arrives already
// mapped to PaneDestination and is placed by the layout (Puck drives the other panels).
//
// Entry points: rail button, the Call Puck shortcut (screens.md "Keyboard shortcuts"), App Shortcuts "Dictate to Puck" / "Call Puck",
// Quick Settings tile, share target (ACTION_SEND/SEND_MULTIPLE of images, text, files, URLs →
// attachments + optional message into the latest conversation).
// ─────────────────────────────────────────────────────────────────────────────
