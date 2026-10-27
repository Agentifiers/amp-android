package com.agentifiers.amp.thread.presence

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.tagsAsResourceIds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.Multiplayer
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.thread.TitleBarControl
import com.agentifiers.amp.thread.share.SEVEN_DAYS_SECONDS
import com.agentifiers.amp.thread.share.SharingWrite
import com.agentifiers.amp.thread.share.rememberSharingFailure
import com.agentifiers.amp.thread.share.threadUrl
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Change Duration choices, 5 minutes to 7 days (screens.md "Multiplayer"). */
private enum class MultiplayerDuration(val label: String, val seconds: Int, val tag: String) {
    FiveMinutes("5 min", 300, ThreadTags.multiplayerDuration5m),
    OneHour("1 h", 3_600, ThreadTags.multiplayerDuration1h),
    OneDay("1 day", 86_400, ThreadTags.multiplayerDuration1d),
    SevenDays("7 days", SEVEN_DAYS_SECONDS, ThreadTags.multiplayerDuration7d),
}

private const val WARNING =
    "While multiplayer is active, your workspace teammates can write prompts in this thread and access its orb or " +
        "shared runner, including portals, the terminal, and the file browser. All agent and orb costs are billed to " +
        "the thread owner."
private const val SECURITY =
    "Security: while multiplayer is active, every workspace member can access the thread, its orb or shared runner, " +
        "secrets, files, and terminal."
private const val DURATION_NOTE = "Multiplayer turns on for 7 days. Enabling it on a private thread shares the thread with your workspace."

/** The title bar's joystick button: it opens a confirm dialog while multiplayer is off and the multiplayer menu while on. */
@Composable
fun multiplayerControl(scope: AccountScope, handle: ThreadHandle, state: ThreadState): TitleBarControl {
    val openState = remember(handle.id) { mutableStateOf(false) }
    val failureState = rememberSharingFailure(scope, handle.id, setOf(SharingWrite.Multiplayer))
    val on = state.multiplayer is Multiplayer.Open
    val label = when {
        failureState.value != null -> "Multiplayer not changed"
        on -> "Multiplayer on"
        else -> "Enable Multiplayer"
    }
    val show = { openState.value = true }
    return TitleBarControl(ThreadTags.menuMultiplayer, label, show, surface = { MultiplayerSurface(scope, handle, state, openState, failureState) }) {
        AmpIconButton(AmpIcons.Joystick, label, show, ThreadTags.multiplayer, tint = if (on) AmpTheme.colors.accents.accent else AmpTheme.colors.text.inkMuted)
    }
}

@Composable
private fun MultiplayerSurface(scope: AccountScope, handle: ThreadHandle, state: ThreadState, openState: MutableState<Boolean>, failureState: MutableState<String?>) {
    val account by scope.account.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var failure by failureState
    var open by openState

    fun setOpen(ttlSeconds: Int?) {
        failure = null
        open = false
        handle.dispatch(ThreadIntent.OpenToCollaborators(ttlSeconds))
    }

    val failureRow: @Composable () -> Unit = {
        failure?.let { reason ->
            Text("Multiplayer did not change: $reason", style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger, modifier = Modifier.testTag(ThreadTags.multiplayerFailure))
            AmpButton("Open Multiplayer on web", { uriHandler.openUri(threadUrl(account, handle.id)) }, ThreadTags.multiplayerOpenWeb, rank = AmpButtonRank.Link)
        }
    }

    val multiplayer = state.multiplayer
    if (multiplayer is Multiplayer.Open) {
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val now by produceState(Clock.System.now(), multiplayer.until) {
                while (true) {
                    delay(30_000)
                    value = Clock.System.now()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs)) {
                Text(
                    "Multiplayer on, ${timeLeft(multiplayer.until, now)}",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(ThreadTags.multiplayerTimeLeft),
                )
                Text("Change Duration", style = AmpTheme.type.label, color = AmpTheme.colors.text.inkMuted)
            }
            MultiplayerDuration.entries.forEach { duration ->
                DropdownMenuItem(text = { Text(duration.label) }, onClick = { setOpen(duration.seconds) }, modifier = Modifier.testTag(duration.tag))
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("End Multiplayer") }, onClick = { setOpen(null) }, modifier = Modifier.testTag(ThreadTags.multiplayerEnd))
            failureRow()
        }
    } else if (open) {
        AlertDialog(
            modifier = Modifier.tagsAsResourceIds(),
            onDismissRequest = { open = false },
            title = { Text("Enable Multiplayer for Thread?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                    Text(WARNING)
                    Text(SECURITY, fontWeight = FontWeight.SemiBold)
                    Text(DURATION_NOTE, color = AmpTheme.colors.text.inkMuted)
                    failureRow()
                }
            },
            confirmButton = {
                AmpButton("Enable Multiplayer", { setOpen(SEVEN_DAYS_SECONDS) }, ThreadTags.multiplayerEnable, rank = AmpButtonRank.Primary)
            },
            dismissButton = {
                AmpButton("Cancel", { open = false }, ThreadTags.multiplayerCancel)
            },
        )
    }
}
