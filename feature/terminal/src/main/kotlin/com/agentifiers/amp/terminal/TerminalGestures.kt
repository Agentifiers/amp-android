package com.agentifiers.amp.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.agentifiers.amp.vt.GridPoint
import com.agentifiers.amp.vt.MouseAction
import com.agentifiers.amp.vt.MouseButton
import com.agentifiers.amp.vt.VtMods
import kotlin.math.roundToInt

/** Where selection handles sit, in grid pixels: below the first cell's left edge and the last cell's right edge. */
internal fun handleOffsets(selection: TerminalSelection, topLine: Int, font: TerminalFont): Pair<Offset, Offset> {
    val start = selection.start
    val end = selection.end
    return Offset(start.col * font.cellWidthPx, (start.line - topLine + 1) * font.lineHeightPx) to
        Offset((end.col + 1) * font.cellWidthPx, (end.line - topLine + 1) * font.lineHeightPx)
}

private enum class TouchMode { Tap, Scroll, Pinch, Select }

private const val MULTI_CLICK_MS = 400L
private const val WHEEL_ROWS = 3

/**
 * terminal.md, "Mouse, trackpad, and touch". Touch: tap focuses, one-finger vertical drag scrolls, long-press
 * selects, pinch zooms. Mouse: left drag selects (or reports, when the application asked and Shift is up),
 * double and triple click select a word and a line, right click copies or pastes, Ctrl+click opens a link.
 */
internal fun Modifier.terminalGestures(state: TerminalPaneState, font: TerminalFont, focus: () -> Unit): Modifier =
    pointerInput(state, font) {
        var lastClickMs = 0L
        var lastClickCell: GridPoint? = null
        var clicks = 0
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type == PointerType.Mouse) {
                val cell = absoluteCell(state, font, down.position)
                clicks = if (down.uptimeMillis - lastClickMs < MULTI_CLICK_MS && cell == lastClickCell) clicks % 3 + 1 else 1
                lastClickMs = down.uptimeMillis
                lastClickCell = cell
                mouseGesture(down, clicks, state, font, focus)
            } else {
                touchGesture(down, state, font, focus)
            }
        }
    }.pointerInput(state, font) {
        var hoverCell: GridPoint? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when {
                    event.type == PointerEventType.Scroll -> {
                        val notches = change.scrollDelta.y.roundToInt().takeIf { it != 0 } ?: (if (change.scrollDelta.y > 0) 1 else -1)
                        state.wheel(-notches, visibleCell(state, font, change.position), mods(event.keyboardModifiers), WHEEL_ROWS)
                        change.consume()
                    }
                    event.type == PointerEventType.Move && change.type == PointerType.Mouse && !change.pressed && state.mouseReporting -> {
                        val cell = visibleCell(state, font, change.position)
                        if (cell != hoverCell) state.mouse(MouseAction.Move, MouseButton.None, cell, mods(event.keyboardModifiers))
                        hoverCell = cell
                    }
                }
            }
        }
    }

private fun mods(keys: PointerKeyboardModifiers) = VtMods(ctrl = keys.isCtrlPressed, alt = keys.isAltPressed, shift = keys.isShiftPressed)

/** The cell on the visible screen under [position]: line is the row, 0 at the top. */
private fun visibleCell(state: TerminalPaneState, font: TerminalFont, position: Offset): GridPoint {
    val snapshot = state.screen
    return GridPoint(
        (position.y / font.lineHeightPx).toInt().coerceIn(0, snapshot.rows - 1),
        (position.x / font.cellWidthPx).toInt().coerceIn(0, snapshot.cols - 1),
    )
}

/** The cell under [position] in history-first line numbers, the coordinates selections use. */
private fun absoluteCell(state: TerminalPaneState, font: TerminalFont, position: Offset): GridPoint {
    val cell = visibleCell(state, font, position)
    return GridPoint(state.topLine(state.screen) + cell.line, cell.col)
}

private suspend fun AwaitPointerEventScope.mouseGesture(down: PointerInputChange, clicks: Int, state: TerminalPaneState, font: TerminalFont, focus: () -> Unit) {
    val event = currentEvent
    val keys = event.keyboardModifiers
    if (event.buttons.isSecondaryPressed) {
        down.consume()
        state.secondaryClick()
        return awaitRelease(down)
    }
    if (!event.buttons.isPrimaryPressed) return
    down.consume()
    focus()
    if (keys.isCtrlPressed && state.openLinkAt(absoluteCell(state, font, down.position), anyUrl = true)) return awaitRelease(down)
    if (state.mouseReporting && !keys.isShiftPressed) {
        var cell = visibleCell(state, font, down.position)
        state.mouse(MouseAction.Press, MouseButton.Left, cell, mods(keys))
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            val now = visibleCell(state, font, change.position)
            if (!change.pressed) return state.mouse(MouseAction.Release, MouseButton.Left, now, mods(keys))
            if (now != cell) state.mouse(MouseAction.Move, MouseButton.Left, now, mods(keys))
            cell = now
        }
        return
    }
    val anchor = absoluteCell(state, font, down.position)
    when (clicks) {
        1 -> state.selection = null
        2 -> state.selectWord(anchor)
        else -> state.selectLine(anchor.line)
    }
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        if (!change.pressed) break
        val cell = absoluteCell(state, font, change.position)
        val current = state.selection
        state.selection = when {
            current == null -> if (cell != anchor) TerminalSelection(anchor, cell) else null
            clicks == 1 -> current.copy(focus = cell)
            cell < current.start -> TerminalSelection(current.end, cell)
            else -> TerminalSelection(current.start, maxOf(cell, current.end))
        }
    }
    state.copySelection()
}

private suspend fun AwaitPointerEventScope.awaitRelease(down: PointerInputChange) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

private suspend fun AwaitPointerEventScope.touchGesture(down: PointerInputChange, state: TerminalPaneState, font: TerminalFont, focus: () -> Unit) {
    state.selection?.let { selection ->
        val (startHandle, endHandle) = handleOffsets(selection, state.topLine(state.screen), font)
        val reach = viewConfiguration.minimumTouchTargetSize.width.toPx() / 2
        val grabbed = when {
            (down.position - startHandle).getDistance() <= reach -> true
            (down.position - endHandle).getDistance() <= reach -> false
            else -> null
        }
        if (grabbed != null) return dragHandle(down, grabbedStart = grabbed, state, font)
    }
    val slop = viewConfiguration.touchSlop
    val mode = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) return@withTimeoutOrNull TouchMode.Tap
            if (pressed >= 2) return@withTimeoutOrNull TouchMode.Pinch
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull TouchMode.Tap
            if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull TouchMode.Scroll
        }
        @Suppress("UNREACHABLE_CODE")
        TouchMode.Tap
    } ?: TouchMode.Select
    when (mode) {
        TouchMode.Tap -> {
            if (state.selection != null) {
                state.selection = null
            } else if (!state.openLinkAt(absoluteCell(state, font, down.position), anyUrl = false)) {
                focus()
            }
        }
        TouchMode.Select -> {
            state.selectWord(absoluteCell(state, font, down.position))
            val anchor = state.selection?.start ?: return
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
                change.consume()
                if (!change.pressed) return
                state.selection = TerminalSelection(anchor, absoluteCell(state, font, change.position))
            }
        }
        TouchMode.Scroll -> scrollOrPinch(down, state, font, pinching = false)
        TouchMode.Pinch -> scrollOrPinch(down, state, font, pinching = true)
    }
}

private suspend fun AwaitPointerEventScope.scrollOrPinch(down: PointerInputChange, state: TerminalPaneState, font: TerminalFont, pinching: Boolean) {
    var pinch = pinching
    var zoom = 1f
    val startSize = state.textSize
    var pendingRows = 0f
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) return
        if (pressed.size >= 2) pinch = true
        if (pinch) {
            zoom *= event.calculateZoom()
            val target = TextSizeSp.of((startSize.value * zoom).roundToInt())
            if (target != state.textSize) state.resizeText(target)
        } else {
            val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
            pendingRows += change.position.y - change.previousPosition.y
            val rows = (pendingRows / font.lineHeightPx).toInt()
            if (rows != 0) {
                state.scroll(rows, visibleCell(state, font, change.position), VtMods.None)
                pendingRows -= rows * font.lineHeightPx
            }
        }
        event.changes.forEach { it.consume() }
    }
}

private suspend fun AwaitPointerEventScope.dragHandle(down: PointerInputChange, grabbedStart: Boolean, state: TerminalPaneState, font: TerminalFont) {
    down.consume()
    val lift = Offset(0f, font.lineHeightPx)
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
        change.consume()
        if (!change.pressed) return
        val selection = state.selection ?: return
        val cell = absoluteCell(state, font, change.position - lift)
        state.selection = if (grabbedStart) TerminalSelection(selection.end, cell) else TerminalSelection(selection.start, cell)
    }
}
