package com.agentifiers.amp.terminal

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.agentifiers.amp.design.TerminalTags
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.TerminalEvent
import com.agentifiers.amp.sync.api.TerminalSession
import com.agentifiers.amp.sync.api.TerminalViewer
import com.agentifiers.amp.vt.GridPoint
import com.agentifiers.amp.vt.MouseAction
import com.agentifiers.amp.vt.MouseButton
import com.agentifiers.amp.vt.MouseTracking
import com.agentifiers.amp.vt.ScreenSnapshot
import com.agentifiers.amp.vt.VtKey
import com.agentifiers.amp.vt.VtMods
import com.agentifiers.amp.vt.VtMouse
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

internal class SnackAction(val label: String, val tag: String, val run: () -> Unit)

/** A snackbar whose action button carries its own test tag. */
internal class PaneSnack(override val message: String, val action: SnackAction? = null) : SnackbarVisuals {
    override val actionLabel: String? get() = action?.label
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long
}

/** Lines a paste would run: a trailing line break ends the last line instead of starting another. */
internal fun pasteLineCount(text: String): Int {
    val lines = text.split("\r\n", "\n", "\r")
    return if (lines.last().isEmpty()) lines.size - 1 else lines.size
}

/**
 * One visible pane over one [TerminalSession]: everything input, selection, scrollback, paste and staging decide.
 * The composable owns layout and gestures; this owns what they mean.
 */
@Stable
internal class TerminalPaneState(
    val thread: ThreadId,
    val session: TerminalSession,
    private val context: Context,
    private val scope: CoroutineScope,
    private val textSizes: TextSizePreference,
    val snackbars: SnackbarHostState,
) : TerminalInputSink {
    var textSize by mutableStateOf(textSizes.read())
        private set
    var keyboardMode by mutableStateOf(KeyboardMode.Terminal)
    var pinKeys by mutableStateOf(false)
    var focused by mutableStateOf(false)
        private set
    var composing by mutableStateOf("")
        private set
    var ctrl by mutableStateOf(Latch())
        private set
    var alt by mutableStateOf(Latch())
        private set
    var selection by mutableStateOf<TerminalSelection?>(null)

    /** Rows scrolled back from the bottom of the primary screen; 0 follows new output. */
    var scrollBack by mutableIntStateOf(0)
        private set

    /** A multi-line paste waiting for the confirm dialog. */
    var pendingPaste by mutableStateOf<String?>(null)

    /** An OSC 8 target whose visible text differs, waiting for the confirm dialog. */
    var pendingLink by mutableStateOf<String?>(null)

    /** The grid area in pixels, as laid out; [grid] is the size reported to the viewer once stable. */
    var gridPx by mutableStateOf(IntSize.Zero)
    var grid by mutableStateOf<GridSize?>(null)

    /** The visible rows' text for the accessibility mirror, refreshed at most 4 times a second. */
    var mirror by mutableStateOf<List<String>>(emptyList())

    /** Set by the pane for the shortcuts it owns: new tab and close tab. */
    var onTabShortcut: (TerminalShortcut) -> Unit = {}

    var viewer: TerminalViewer? = null
        set(value) {
            field = value
            if (value != null && focused) value.focused()
        }

    private var interacted = false
    private var stagingJob: Job? = null
    private var knownScrollback = 0

    val screen: ScreenSnapshot get() = session.screen.value
    private val clipboard: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)

    /** The absolute line of the first drawn row. The alternate screen has no local scrollback. */
    fun topLine(snapshot: ScreenSnapshot): Int = snapshot.scrollback - if (snapshot.modes.altScreen) 0 else scrollBack

    /** Keeps the scrolled-back view on the same rows while output appends to history. */
    fun onScreen(snapshot: ScreenSnapshot) {
        val grown = snapshot.scrollback - knownScrollback
        knownScrollback = snapshot.scrollback
        if (scrollBack > 0) scrollBack = (scrollBack + grown).coerceIn(0, snapshot.scrollback)
    }

    fun onFocus(isFocused: Boolean) {
        if (isFocused == focused) return
        focused = isFocused
        if (isFocused) viewer?.focused()
        session.focus(isFocused)
    }

    fun resizeText(size: TextSizeSp) {
        textSize = size
        textSizes.write(size)
    }

    fun send(key: VtKey, mods: VtMods) {
        val latched = mods.copy(ctrl = mods.ctrl || ctrl.active, alt = mods.alt || alt.active)
        ctrl = ctrl.used()
        alt = alt.used()
        interacted = true
        scrollBack = 0
        session.key(key, latched)
    }

    /** Typed text from the IME or agent-device's SetText; a latched Ctrl or Alt applies to a single character. */
    fun type(text: String) {
        if (text.isEmpty()) return
        val single = text.codePointCount(0, text.length) == 1
        if (single && (ctrl.active || alt.active)) return send(VtKey.Char(text.codePointAt(0)), VtMods.None)
        interacted = true
        scrollBack = 0
        session.text(text)
    }

    fun softKey(action: SoftKeyAction, nowMs: Long) {
        when (action) {
            is SoftKeyAction.Send -> send(action.key, VtMods(ctrl = action.ctrl))
            SoftKeyAction.Ctrl -> ctrl = ctrl.tap(nowMs)
            SoftKeyAction.Alt -> alt = alt.tap(nowMs)
            SoftKeyAction.Select -> selectCursorLine()
            SoftKeyAction.Paste -> pasteClipboard()
        }
    }

    /** Hardware and IME key events. False lets the window handle the key (app shortcuts, system Meta chords). */
    fun onKeyDown(event: KeyEvent): Boolean = when (val action = keyAction(event)) {
        KeyAction.Pass -> false
        is KeyAction.Send -> {
            send(action.key, action.mods)
            true
        }
        is KeyAction.Shortcut -> {
            if (event.repeatCount == 0) shortcut(action.shortcut)
            true
        }
    }

    private fun shortcut(shortcut: TerminalShortcut) {
        when (shortcut) {
            TerminalShortcut.Copy -> copySelection()
            TerminalShortcut.Paste -> pasteClipboard()
            TerminalShortcut.NewTab, TerminalShortcut.CloseTab -> onTabShortcut(shortcut)
            TerminalShortcut.TextLarger -> resizeText(textSize.larger())
            TerminalShortcut.TextSmaller -> resizeText(textSize.smaller())
            TerminalShortcut.TextDefault -> resizeText(TextSizeSp.Default)
        }
    }

    override fun commit(text: String) = type(text)

    override fun compose(text: String) {
        composing = text
    }

    override fun finishComposing() {
        val text = composing
        composing = ""
        type(text)
    }

    override fun deleteBefore(count: Int) = repeat(count) { send(VtKey.Named.Backspace, VtMods.None) }

    override fun key(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event)
    }

    override fun editorAction() = send(VtKey.Named.Enter, VtMods.None)

    /** A mouse wheel turned [older] notches toward history: one report per notch, otherwise [rowsPerNotch] rows each. */
    fun wheel(older: Int, cell: GridPoint, mods: VtMods, rowsPerNotch: Int) {
        if (screen.modes.mouseTracking != MouseTracking.Off) {
            val button = if (older > 0) MouseButton.WheelUp else MouseButton.WheelDown
            repeat(min(abs(older), MAX_SCROLL_KEYS)) { session.mouse(VtMouse(cell.col, cell.line, MouseAction.Press, button, mods)) }
        } else {
            scroll(older * rowsPerNotch, cell, mods)
        }
    }

    /**
     * Trackpad and one-finger scroll, [older] rows toward history when positive. Mouse reporting gets wheel
     * buttons; the alternate screen gets arrows, at most 5 per event; the primary screen scrolls local history.
     */
    fun scroll(older: Int, cell: GridPoint, mods: VtMods) {
        if (older == 0) return
        val snapshot = screen
        when {
            snapshot.modes.mouseTracking != MouseTracking.Off -> {
                val button = if (older > 0) MouseButton.WheelUp else MouseButton.WheelDown
                repeat(min(abs(older), MAX_SCROLL_KEYS)) { session.mouse(VtMouse(cell.col, cell.line, MouseAction.Press, button, mods)) }
            }
            snapshot.modes.altScreen -> {
                val key = if (older > 0) VtKey.Named.Up else VtKey.Named.Down
                repeat(min(abs(older), MAX_SCROLL_KEYS)) { session.key(key, VtMods.None) }
            }
            else -> scrollBack = (scrollBack + older).coerceIn(0, snapshot.scrollback)
        }
    }

    /** [cell] is on the visible screen: line is the row, 0 at the top. */
    fun mouse(action: MouseAction, button: MouseButton, cell: GridPoint, mods: VtMods) {
        interacted = true
        session.mouse(VtMouse(cell.col, cell.line, action, button, mods))
    }

    val mouseReporting: Boolean get() = screen.modes.mouseTracking != MouseTracking.Off

    fun selectWord(point: GridPoint) {
        val word = screen.wordAt(point)
        selection = if (word == null) TerminalSelection(point, point) else TerminalSelection(word.first, word.second)
    }

    fun selectLine(line: Int) {
        selection = TerminalSelection(GridPoint(line, 0), GridPoint(line, screen.cols - 1))
    }

    private fun selectCursorLine() {
        val snapshot = screen
        scrollBack = 0
        selectLine(snapshot.scrollback + snapshot.cursor.row)
    }

    fun copySelection(): Boolean {
        val text = selection?.text(screen)?.takeIf { it.isNotEmpty() } ?: return false
        copy(text)
        notify(PaneSnack("Copied terminal selection"))
        return true
    }

    private fun copy(text: String) = clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))

    /** Right click: copies the selection if there is one, otherwise pastes. */
    fun secondaryClick() {
        if (!copySelection()) pasteClipboard()
    }

    /** Ctrl+Shift+V, Meta+V, right click, **Paste**: text pastes; a clipboard with only files or images stages them. */
    fun pasteClipboard() {
        val clip = clipboard.primaryClip ?: return
        val items = (0 until clip.itemCount).map(clip::getItemAt)
        val text = items.mapNotNull { it.text?.toString() }.joinToString("")
        if (text.isNotEmpty()) return paste(text)
        val files = items.mapNotNull { it.uri }
        if (files.isNotEmpty()) stage(files)
    }

    /** The one paste path: a multi-line paste into a shell without bracketed paste asks first. */
    fun paste(text: String) {
        if (text.isEmpty()) return
        interacted = true
        scrollBack = 0
        if (!screen.modes.bracketedPaste && pasteLineCount(text) > 1) pendingPaste = text else session.paste(text)
    }

    fun confirmPaste() {
        val text = pendingPaste ?: return
        pendingPaste = null
        session.paste(text)
    }

    fun onEvent(event: TerminalEvent, haptic: () -> Unit) {
        when (event) {
            TerminalEvent.Bell -> haptic()
            is TerminalEvent.ClipboardWrite ->
                if (focused && interacted) copy(event.text)
                else notify(PaneSnack("The terminal wants to copy text", SnackAction("Copy", TerminalTags.osc52Copy) { copy(event.text) }))
            TerminalEvent.InputDropped -> notify(PaneSnack("The terminal is still connecting."))
        }
    }

    /**
     * Opens the link under [point]: an OSC 8 target, or with [anyUrl] also a plain URL in the text. An OSC 8 link whose
     * visible text is not its target asks first. False when there is no link there.
     */
    fun openLinkAt(point: GridPoint, anyUrl: Boolean): Boolean {
        val snapshot = screen
        if (point.line !in 0 until snapshot.totalLines) return false
        val row = snapshot.line(point.line)
        val osc8 = row.link(point.col)
        val target = osc8 ?: (if (anyUrl) snapshot.linkAt(point) else null) ?: return false
        if (osc8 != null && linkText(row, point.col, osc8).trim() != osc8) pendingLink = osc8 else openUrl(target)
        return true
    }

    private fun linkText(row: com.agentifiers.amp.vt.Row, col: Int, target: String): String {
        var start = col
        while (start > 0 && row.link(start - 1) == target) start--
        var end = col
        while (end + 1 < row.columns && row.link(end + 1) == target) end++
        return (start..end).joinToString("") { row.grapheme(it) }
    }

    fun confirmLink() {
        val target = pendingLink ?: return
        pendingLink = null
        openUrl(target)
    }

    fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return notify(PaneSnack("Only web links open from the terminal."))
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            notify(PaneSnack("No app on this device opens web links."))
        }
    }

    fun openOnWeb() = openUrl("https://ampcode.com/threads/${thread.raw}/terminal")

    /** Files from a drop, the picker, or the clipboard, staged one at a time. */
    fun stage(uris: List<Uri>) {
        val previous = stagingJob
        stagingJob = scope.launch {
            previous?.join()
            uris.forEach { stageOne(it) }
        }
    }

    fun cancelStaging() {
        stagingJob?.cancel()
        stagingJob = null
    }

    private suspend fun stageOne(uri: Uri) {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0).orEmpty()
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val label = name.ifEmpty { "the file" }
        if (size < 0) return notify(PaneSnack("Could not read the size of $label."))
        val bytes = flow {
            val input = resolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
            input.use {
                val buffer = ByteArray(STAGE_CHUNK)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    emit(buffer.copyOf(read))
                }
            }
        }.flowOn(Dispatchers.IO)
        when (val outcome = session.stageFile(name, resolver.getType(uri) ?: "application/octet-stream", bytes, size)) {
            is Outcome.Ok -> if (outcome.value.startsWith("http")) notify(PaneSnack("Inserted the file's URL. The executor could not save the file."))
            is Outcome.Refused -> notify(PaneSnack(outcome.why))
            is Outcome.Failed, Outcome.Unknown -> notify(PaneSnack("Could not upload $label."))
        }
    }

    fun notify(snack: PaneSnack) {
        scope.launch {
            if (snackbars.showSnackbar(snack) == SnackbarResult.ActionPerformed) snack.action?.run?.invoke()
        }
    }

    private companion object {
        const val MAX_SCROLL_KEYS = 5
        const val STAGE_CHUNK = 64 * 1024
    }
}
