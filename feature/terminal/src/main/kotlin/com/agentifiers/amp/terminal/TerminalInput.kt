package com.agentifiers.amp.terminal

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** terminal.md, "IME composition": Terminal commits each character as typed; Text allows composition for CJK and voice. */
internal enum class KeyboardMode { Terminal, Text }

/** Where the input connection's calls go; every call arrives on the main thread. */
internal interface TerminalInputSink {
    fun commit(text: String)
    fun compose(text: String)
    fun finishComposing()
    fun deleteBefore(count: Int)
    fun key(event: KeyEvent)
    fun editorAction()
}

/**
 * The `terminal-input` node: a focus target whose focus opens an [android.view.inputmethod.InputConnection] through
 * Compose's platform text input session, so hardware keys, IME calls and focus stay on one Compose node. [showRequests]
 * asks the IME to show, for a tap on the terminal.
 */
internal fun Modifier.terminalInput(sink: TerminalInputSink, mode: KeyboardMode, showRequests: Flow<Unit>): Modifier =
    this then TerminalInputElement(sink, mode, showRequests)

private data class TerminalInputElement(val sink: TerminalInputSink, val mode: KeyboardMode, val showRequests: Flow<Unit>) :
    ModifierNodeElement<TerminalInputNode>() {
    override fun create() = TerminalInputNode(sink, mode, showRequests)

    override fun update(node: TerminalInputNode) = node.update(sink, mode, showRequests)

    override fun InspectorInfo.inspectableProperties() {
        name = "terminalInput"
    }
}

private class TerminalInputNode(
    private var sink: TerminalInputSink,
    private var mode: KeyboardMode,
    private var showRequests: Flow<Unit>,
) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {
    private var session: Job? = null
    private var focused = false

    fun update(sink: TerminalInputSink, mode: KeyboardMode, showRequests: Flow<Unit>) {
        this.sink = sink
        val restart = mode != this.mode || showRequests != this.showRequests
        this.mode = mode
        this.showRequests = showRequests
        if (restart && focused) start()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        if (focused) start() else stop()
    }

    override fun onDetach() = stop()

    private fun start() {
        session?.cancel()
        val mode = mode
        val requests = showRequests
        session = coroutineScope.launch {
            establishTextInputSession {
                coroutineScope {
                    launch {
                        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        requests.collect { imm.showSoftInput(view, 0) }
                    }
                    startInputMethod(TerminalInputRequest(view, mode) { sink })
                }
            }
        }
    }

    private fun stop() {
        session?.cancel()
        session = null
    }
}

private class TerminalInputRequest(private val view: View, private val mode: KeyboardMode, private val sink: () -> TerminalInputSink) :
    PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): android.view.inputmethod.InputConnection {
        outAttributes.inputType = when (mode) {
            KeyboardMode.Terminal -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            KeyboardMode.Text -> InputType.TYPE_CLASS_TEXT
        }
        outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        outAttributes.initialSelStart = 0
        outAttributes.initialSelEnd = 0
        return TerminalInputConnection(view, sink)
    }
}

/** terminal.md, "IME composition": the connection's editor stays empty, so every call becomes PTY input or composing text. */
private class TerminalInputConnection(view: View, private val sink: () -> TerminalInputSink) : BaseInputConnection(view, false) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        sink().compose("")
        if (!text.isNullOrEmpty()) sink().commit(text.toString())
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        sink().compose(text?.toString().orEmpty())
        return true
    }

    override fun finishComposingText(): Boolean {
        sink().finishComposing()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) sink().deleteBefore(beforeLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        sink().key(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        sink().editorAction()
        return true
    }
}
