package com.agentifiers.amp.desktop

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.agentifiers.amp.design.DesktopTags
import com.agentifiers.amp.waymote.KeyAction
import com.agentifiers.amp.waymote.MouseButton
import com.agentifiers.amp.waymote.SessionState
import com.agentifiers.amp.waymote.SurfaceTarget
import com.agentifiers.amp.waymote.Video
import com.agentifiers.amp.waymote.WaymoteSession
import kotlinx.coroutines.awaitCancellation
import android.view.KeyEvent as AndroidKeyEvent

/**
 * The remote video, letterboxed to the stream's aspect ratio. The pane's pixel size is the resolution this viewer
 * asks for; touches become pointer input per [mode].
 */
@Composable
internal fun DesktopVideo(session: WaymoteSession, state: SessionState, mode: InputMode, keyboardOpen: Boolean, modifier: Modifier) {
    val density = LocalDensity.current.density
    val video = (state as? SessionState.Streaming)?.video
    val showing = video as? Video.Showing
    val surfaceFocus = remember { FocusRequester() }
    val takeFocus by rememberUpdatedState { if (!keyboardOpen) surfaceFocus.requestFocus() }
    val remoteWidth by rememberUpdatedState(showing?.width)

    Box(
        modifier.background(AmpTheme.colors.media.letterbox).onSizeChanged { session.resize(it.width, it.height, density) },
        contentAlignment = Alignment.Center,
    ) {
        AndroidExternalSurface(
            modifier = (if (showing != null) Modifier.aspectRatio(showing.width.toFloat() / showing.height) else Modifier.fillMaxSize())
                .testTag(DesktopTags.surface)
                .focusRequester(surfaceFocus)
                .focusable()
                .pointerInput(session, mode) {
                    when (mode) {
                        InputMode.Direct -> direct(session) { takeFocus() }
                        InputMode.Trackpad -> trackpad(session, remotePerPx = { (remoteWidth ?: size.width).toFloat() / size.width }) { takeFocus() }
                    }
                },
        ) {
            onSurface { surface, _, _ ->
                val target = SurfaceTarget(surface)
                surface.onDestroyed { target.release() }
                session.attach(target)
                try {
                    awaitCancellation()
                } finally {
                    session.attach(null)
                }
            }
        }
        if (state is SessionState.Streaming && video == Video.Waiting) {
            Text("Waiting for video…", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.body)
        }
    }
}

/** Direct: the finger is the pointer. Press, drag and lift are left button down, moves, and up at the touched point. */
private suspend fun PointerInputScope.direct(session: WaymoteSession, onTouch: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown()
    onTouch()
    down.consume()
    session.pointAt(down.position.x / size.width, down.position.y / size.height)
    session.button(MouseButton.Left, down = true)
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        if (change.positionChange() != Offset.Zero) session.pointAt(change.position.x / size.width, change.position.y / size.height)
        change.consume()
        if (!change.pressed) break
    }
    session.button(MouseButton.Left, down = false)
}

/**
 * Trackpad: one finger moves the pointer relatively, two fingers scroll, and a tap clicks (two-finger tap is a right
 * click). [remotePerPx] converts view pixels to remote pixels.
 */
private suspend fun PointerInputScope.trackpad(session: WaymoteSession, remotePerPx: () -> Float, onTouch: () -> Unit) = awaitEachGesture {
    awaitFirstDown().consume()
    onTouch()
    var travel = 0f
    var fingers = 1
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        fingers = maxOf(fingers, pressed.size)
        val delta = pressed.fold(Offset.Zero) { sum, change -> sum + change.positionChange() } / pressed.size.toFloat()
        travel += delta.getDistance()
        val scale = remotePerPx()
        when {
            delta == Offset.Zero -> Unit
            pressed.size >= 2 -> session.wheel(-delta.x * scale, -delta.y * scale)
            fingers == 1 -> session.move(delta.x * scale, delta.y * scale)
        }
        event.changes.forEach { it.consume() }
    }
    if (travel < viewConfiguration.touchSlop) session.click(if (fingers >= 2) MouseButton.Right else MouseButton.Left)
}

/** A hardware or IME key event as a key struct; false for keys the device keeps, such as Back and volume. */
internal fun sendKey(session: WaymoteSession, event: KeyEvent): Boolean {
    val native = event.nativeKeyEvent
    if (!session.handlesKey(native.keyCode)) return false
    val action = when {
        native.action == AndroidKeyEvent.ACTION_UP -> KeyAction.Up
        native.repeatCount > 0 -> KeyAction.Repeat
        else -> KeyAction.Down
    }
    session.key(native.keyCode, action, native.unicodeChar, native.metaState)
    return true
}
