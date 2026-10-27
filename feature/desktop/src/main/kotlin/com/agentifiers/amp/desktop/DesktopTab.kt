package com.agentifiers.amp.desktop

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.DesktopTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.DesktopStream
import com.agentifiers.amp.sync.api.RelayChannel
import com.agentifiers.amp.sync.api.RelayChannelFactory
import com.agentifiers.amp.waymote.Audio
import com.agentifiers.amp.waymote.Control
import com.agentifiers.amp.waymote.EndReason
import com.agentifiers.amp.waymote.SessionState
import com.agentifiers.amp.waymote.AndroidMedia
import com.agentifiers.amp.waymote.Video
import com.agentifiers.amp.waymote.WaymoteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How touches on the video drive the remote pointer. */
internal enum class InputMode { Direct, Trackpad }

/** The Desktop machine tab: the remote desktop's video over :waymote, with a toolbar of controls. */
@Composable
fun DesktopTab(dest: PaneDestination.Machine, tab: MachineTab.Desktop, scope: AccountScope, modifier: Modifier = Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    val session = remember(dest.thread, tab.target, attempt) {
        val machine = scope.thread(dest.thread).machine
        WaymoteSession(Deferred { machine.desktop(tab.target) }, AndroidMedia)
    }
    LaunchedEffect(session) { withContext(Dispatchers.Default) { session.run() } }
    val state by session.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(InputMode.Direct) }
    var keyboardOpen by remember { mutableStateOf(false) }
    val keyboardFocus = remember { FocusRequester() }
    val active = (state as? SessionState.Streaming)?.control == Control.Active

    Column(
        modifier.fillMaxSize().background(LocalPaneSurface.current).onPreviewKeyEvent { active && sendKey(session, it) },
    ) {
        DesktopToolbar(
            state = state,
            session = session,
            mode = mode,
            onMode = { mode = it },
            keyboardOpen = keyboardOpen,
            keyboardFocus = keyboardFocus,
            onKeyboardOpen = { keyboardOpen = it },
        )
        Column(Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs)) {
            Text(statusText(state), color = AmpTheme.colors.text.ink, style = AmpTheme.type.secondary, modifier = Modifier.testTag(DesktopTags.status))
            (state as? SessionState.Streaming)?.let { streaming ->
                statsText(streaming)?.let { Text(it, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta, modifier = Modifier.testTag(DesktopTags.stats)) }
            }
        }
        if (state is SessionState.Ended) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                AmpEmptyState(AmpIcons.Monitor, "Desktop disconnected", "Reconnect to start a new session with the desktop.", DesktopTags.ended) {
                    AmpButton("Reconnect", { attempt++ }, DesktopTags.reconnect, rank = AmpButtonRank.Primary)
                }
            }
        } else {
            DesktopVideo(
                session = session,
                state = state,
                mode = mode,
                keyboardOpen = keyboardOpen,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * Resolves the Machine's factory on first use, so a failure to reach the desktop relay ends the session with its
 * message instead of throwing out of composition.
 */
private class Deferred(resolve: () -> RelayChannelFactory) : RelayChannelFactory {
    private val factory by lazy(resolve)

    override suspend fun open(stream: DesktopStream): RelayChannel = factory.open(stream)
}

private fun statusText(state: SessionState): String = when (state) {
    SessionState.Connecting -> "Connecting to the desktop…"
    is SessionState.Ended -> when (val reason = state.reason) {
        is EndReason.UnsupportedCodec -> "This device has no decoder for the desktop's video (${reason.codec})."
        is EndReason.Closed -> "The desktop's ${reason.stream.name.lowercase()} channel closed."
        is EndReason.Failed -> reason.message
    }
    is SessionState.Streaming -> {
        val error = state.displays.error
        val notice = state.control != Control.Busy
        when {
            notice && error != null -> error
            notice && state.displays.switching && state.video is Video.Showing -> "Switching display…"
            else -> when (state.control) {
                Control.Watching -> "Watching. Take over to use the desktop."
                Control.Requesting -> "Requesting control…"
                Control.Active -> "You are controlling the desktop."
                Control.Busy -> "Busy: another viewer is controlling the desktop."
            }
        }
    }
}

private fun statsText(state: SessionState.Streaming): String? = listOfNotNull(
    state.resolution?.let { "${it.width}×${it.height}" },
    state.quality?.let { "${it.bitrateKbps} kbps · ${it.fps} fps" },
    when (val audio = state.audio) {
        Audio.Off -> "No audio"
        is Audio.Failed -> audio.reason
        Audio.Pending, Audio.Playing -> null
    },
).joinToString(" · ").ifEmpty { null }
