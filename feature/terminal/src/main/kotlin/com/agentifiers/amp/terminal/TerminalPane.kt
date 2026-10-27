package com.agentifiers.amp.terminal

import com.agentifiers.amp.design.AmpTheme
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.TerminalTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.TerminalSession
import com.agentifiers.amp.vt.ScreenSnapshot
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow

/** What the pane asks of the tab strip: Ctrl+Shift+T, Ctrl+Shift+W, and **Close tab** on the reconnect overlay. */
internal class TabControls(val openNew: () -> Unit, val closeCurrent: () -> Unit)

private const val MIRROR_INTERVAL_MS = 250L
private const val SIZE_TAG = "AmpTerm"

/**
 * One terminal tab's pane: header, grid, overlays, extra keys and dialogs (terminal.md, "Rendering in Compose",
 * "Input", "What the pane shows", "Accessibility and agent-device"). [header] holds the session chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalPane(
    thread: ThreadId,
    tab: TerminalTabNumber,
    session: TerminalSession,
    tabs: TabControls,
    header: @Composable RowScope.() -> Unit,
    publishMenu: (List<AmpMenuItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(session) {
        TerminalPaneState(thread, session, context, scope, TextSizePreference(context), SnackbarHostState())
    }
    SideEffect {
        state.onTabShortcut = { shortcut ->
            if (shortcut == TerminalShortcut.NewTab) tabs.openNew() else tabs.closeCurrent()
        }
    }
    val screen = session.screen.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val pane = paneStatus(status)
    val density = LocalDensity.current
    val font = remember(state.textSize, density) {
        with(density) { TerminalFont(state.textSize.value.sp.toPx(), lineHeightSp(state.textSize).sp.toPx()) }
    }
    val colors = AmpTheme.colors
    val renderer = remember(font, colors) { TerminalRenderer(font, TerminalPalette(colors)) }
    val focusRequester = remember { FocusRequester() }
    val showKeyboard = remember { Channel<Unit>(Channel.CONFLATED) }
    val showRequests = remember(showKeyboard) { showKeyboard.receiveAsFlow() }
    val focus = {
        focusRequester.requestFocus()
        showKeyboard.trySend(Unit)
        Unit
    }

    PublishPaneMenu(state, publishMenu)
    ViewerAttachment(state, font)
    PaneEffects(state, thread, tab)

    Column(modifier.fillMaxSize().background(AmpTheme.colors.surfaces.canvas)) {
        PaneHeader(state, pane.word, header)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TerminalGrid(state, screen, font, renderer, focusRequester, showRequests, focus)
            PaneOverlay(state, pane.view, tabs)
            StagingBar(state, Modifier.align(Alignment.BottomCenter))
            SnackbarHost(state.snackbars, Modifier.align(Alignment.BottomCenter)) { data ->
                val snack = data.visuals as PaneSnack
                Snackbar(
                    action = snack.action?.let { action ->
                        { AmpButton(action.label, data::performAction, action.tag) }
                    },
                    modifier = Modifier.padding(AmpTheme.spacing.sm),
                ) { Text(snack.message) }
            }
        }
        val configuration = LocalConfiguration.current
        val hardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
            configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        if (state.pinKeys || (state.focused && WindowInsets.isImeVisible && !hardwareKeyboard)) {
            SoftKeysRow(state.ctrl, state.alt, onAction = { state.softKey(it, System.currentTimeMillis()) })
        }
    }
    PasteDialog(state)
    LinkDialog(state)
}

/**
 * Holds a viewer while this window is STARTED and the grid has a usable size. The window id is the Activity's task,
 * as :app's VisibleThreadLeases names windows, so both rotations of one window are one viewer.
 */
@Composable
private fun ViewerAttachment(state: TerminalPaneState, font: TerminalFont) {
    val activity = checkNotNull(LocalActivity.current) { "the terminal pane needs an Activity window" }
    val windowId = "task-${activity.taskId}"
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val grid = state.grid
    val currentGrid by rememberUpdatedState(grid)
    DisposableEffect(state, started, grid != null) {
        val size = currentGrid
        if (!started || size == null) return@DisposableEffect onDispose {}
        val viewer = state.session.attachViewer(windowId, size.cols, size.rows)
        state.viewer = viewer
        onDispose {
            state.viewer = null
            viewer.close()
        }
    }
    LaunchedEffect(grid) {
        grid?.let { state.viewer?.resize(it.cols, it.rows) }
    }
    val measured = remember(state.gridPx, font) {
        gridSize(state.gridPx.width.toFloat(), state.gridPx.height.toFloat(), font.cellWidthPx, font.lineHeightPx)
    }
    val density = LocalDensity.current
    val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    LaunchedEffect(measured) {
        if (!measured.usable) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        if (measured == state.grid) return@LaunchedEffect
        state.grid = measured
        if (debuggable) {
            val widthDp = with(density) { state.gridPx.width.toDp().value } + 24f
            Log.i(SIZE_TAG, "size cols=${measured.cols} rows=${measured.rows} widthDp=${widthDp.roundToInt()}")
        }
    }
}

@Composable
private fun PaneEffects(state: TerminalPaneState, thread: ThreadId, tab: TerminalTabNumber) {
    val view = LocalView.current
    LaunchedEffect(state) {
        state.session.screen.collect { state.onScreen(it) }
    }
    LaunchedEffect(state) {
        state.session.events.collect { event -> state.onEvent(event) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
    }
    LaunchedEffect(state) {
        state.session.screen.collect { snapshot ->
            state.mirror = snapshot.lines.map { it.text }
            delay(MIRROR_INTERVAL_MS)
        }
    }
    DisposableEffect(state, thread, tab) {
        val registration = TerminalPasteRequests.register(thread, tab, state::paste)
        onDispose { registration.close() }
    }
}

@Composable
private fun PaneHeader(state: TerminalPaneState, word: String, chips: @Composable RowScope.() -> Unit) {
    SessionStrip(chips) {
        if (state.selection != null) {
            AmpIconButton(AmpIcons.Copy, "Copy", { state.copySelection() }, TerminalTags.copy)
        }
        Text(
            word,
            color = AmpTheme.colors.text.inkMuted,
            style = AmpTheme.type.meta,
            maxLines = 1,
            modifier = Modifier
                .padding(start = AmpTheme.spacing.xs, end = AmpTheme.spacing.paneX)
                .testTag(TerminalTags.status)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The session's actions, published to the machine header's More Actions every composition so labels and enablement stay current. */
@Composable
private fun PublishPaneMenu(state: TerminalPaneState, publishMenu: (List<AmpMenuItem>) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) state.stage(uris)
    }
    val items = listOf(
        AmpMenuItem("Smaller text", TerminalTags.fontSmaller, enabled = state.textSize.value > TextSizeSp.MIN) {
            state.resizeText(state.textSize.smaller())
        },
        AmpMenuItem("Larger text", TerminalTags.fontLarger, enabled = state.textSize.value < TextSizeSp.MAX) {
            state.resizeText(state.textSize.larger())
        },
        AmpMenuItem("Keyboard mode: ${state.keyboardMode.name}", TerminalTags.keyboardMode) {
            state.keyboardMode = if (state.keyboardMode == KeyboardMode.Terminal) KeyboardMode.Text else KeyboardMode.Terminal
        },
        AmpMenuItem(if (state.pinKeys) "Unpin extra keys" else "Pin extra keys", TerminalTags.pinKeys) {
            state.pinKeys = !state.pinKeys
        },
        AmpMenuItem("Upload file", TerminalTags.upload) { picker.launch(arrayOf("*/*")) },
    )
    SideEffect { publishMenu(items) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalGrid(
    state: TerminalPaneState,
    screen: State<ScreenSnapshot>,
    font: TerminalFont,
    renderer: TerminalRenderer,
    focusRequester: FocusRequester,
    showRequests: kotlinx.coroutines.flow.Flow<Unit>,
    focus: () -> Unit,
) {
    val activity = LocalActivity.current
    val dropTarget = remember(state) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                activity?.requestDragAndDropPermissions(drag)
                val clip = drag.clipData ?: return false
                val items = (0 until clip.itemCount).map(clip::getItemAt)
                val files = items.mapNotNull { it.uri }
                if (files.isNotEmpty()) {
                    state.stage(files)
                    return true
                }
                val text = items.mapNotNull { it.text?.toString() }.joinToString("")
                if (text.isEmpty()) return false
                state.paste(text)
                return true
            }
        }
    }
    val density = LocalDensity.current
    val cellWidth = with(density) { font.cellWidthPx.toDp() }
    val lineHeight = with(density) { font.lineHeightPx.toDp() }
    val handleRadius = with(density) { AmpTheme.metrics.dot.toPx() }
    val handleColor = AmpTheme.colors.accents.accent
    val mirror = state.mirror
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(AmpTheme.colors.surfaces.canvas)
            .padding(horizontal = AmpTheme.spacing.paneX)
            .onSizeChanged { state.gridPx = it }
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .terminalGestures(state, font, focus)
            .pointerHoverIcon(PointerIcon.Text)
            .drawBehind {
                val snapshot = screen.value
                val top = state.topLine(snapshot)
                drawIntoCanvas { renderer.draw(it.nativeCanvas, TerminalFrame(snapshot, top, state.selection, state.focused, state.composing)) }
                state.selection?.let { selection ->
                    val (start, end) = handleOffsets(selection, top, font)
                    drawCircle(handleColor, handleRadius, start + Offset(0f, handleRadius))
                    drawCircle(handleColor, handleRadius, end + Offset(0f, handleRadius))
                }
            }
            .testTag(TerminalTags.screen)
            .semantics {
                contentDescription = "Terminal"
                text = AnnotatedString(mirror.joinToString("\n"))
                onClick(label = "Type in the terminal") {
                    focus()
                    true
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            mirror.forEachIndexed { index, row ->
                Box(Modifier.fillMaxWidth().height(lineHeight).testTag(TerminalTags.row(index)).semantics { text = AnnotatedString(row) })
            }
        }
        Box(
            Modifier
                .offset {
                    val snapshot = screen.value
                    val row = snapshot.scrollback + snapshot.cursor.row - state.topLine(snapshot)
                    IntOffset((snapshot.cursor.col * font.cellWidthPx).roundToInt(), (row.coerceAtLeast(0) * font.lineHeightPx).roundToInt())
                }
                .size(cellWidth, lineHeight)
                .terminalInput(state, state.keyboardMode, showRequests)
                .onFocusChanged { state.onFocus(it.isFocused) }
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (event.type == KeyEventType.KeyDown) state.onKeyDown(native) else keyAction(native) != KeyAction.Pass
                }
                .focusable()
                .testTag(TerminalTags.input)
                .semantics {
                    editableText = AnnotatedString(state.composing)
                    setText { typed ->
                        state.type(typed.text)
                        true
                    }
                },
        )
    }
}

@Composable
private fun PaneOverlay(state: TerminalPaneState, view: PaneView, tabs: TabControls) {
    when (view) {
        PaneView.Live -> Unit
        PaneView.Connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        PaneView.Reconnecting -> Box(
            Modifier.fillMaxSize().background(AmpTheme.colors.surfaces.scrim).testTag(TerminalTags.reconnecting),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                Text("Reconnecting terminal", color = AmpTheme.colors.text.ink, style = AmpTheme.type.body)
                AmpButton("Close tab", tabs.closeCurrent, TerminalTags.reconnectingClose)
            }
        }
        is PaneView.Notice -> Box(Modifier.fillMaxSize().background(AmpTheme.colors.surfaces.canvas), contentAlignment = Alignment.TopCenter) {
            AmpEmptyState(AmpIcons.Terminal, "Terminal unavailable", view.message, TerminalTags.unavailable, action = view.actions.takeIf { it.isNotEmpty() }?.let { actions ->
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.actionGap)) {
                        actions.forEach { action ->
                            when (action) {
                                PaneAction.Retry ->
                                    AmpButton("Retry", state.session::retry, TerminalTags.retry, rank = AmpButtonRank.Primary)
                                PaneAction.OpenOnWeb ->
                                    AmpButton("Open on web", state::openOnWeb, TerminalTags.openWeb, rank = AmpButtonRank.Link)
                            }
                        }
                    }
                }
            })
        }
    }
}

@Composable
private fun StagingBar(state: TerminalPaneState, modifier: Modifier) {
    val staging by state.session.staging.collectAsStateWithLifecycle()
    val file = staging ?: return
    Surface(modifier.fillMaxWidth().padding(AmpTheme.spacing.sm).testTag(TerminalTags.uploadProgress), color = AmpTheme.colors.surfaces.overlay, shape = AmpTheme.shapes.control) {
        Row(Modifier.padding(start = AmpTheme.spacing.paneX, end = AmpTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            val label = when (file.phase) {
                com.agentifiers.amp.sync.api.FileStaging.Phase.Uploading -> "Uploading ${file.name}…"
                com.agentifiers.amp.sync.api.FileStaging.Phase.Saving -> "Saving ${file.name} to the orb…"
            }
            Text(label, color = AmpTheme.colors.text.ink, style = AmpTheme.type.secondary, modifier = Modifier.weight(1f))
            AmpButton("Cancel", state::cancelStaging, TerminalTags.uploadCancel)
        }
    }
}

@Composable
private fun PasteDialog(state: TerminalPaneState) {
    val text = state.pendingPaste ?: return
    AlertDialog(
        onDismissRequest = { state.pendingPaste = null },
        modifier = PopupTags,
        text = { Text("Paste ${pasteLineCount(text)} lines into the terminal? Each line runs as a command.") },
        confirmButton = {
            AmpButton("Paste", state::confirmPaste, TerminalTags.pasteConfirmPaste, rank = AmpButtonRank.Primary)
        },
        dismissButton = {
            AmpButton("Cancel", { state.pendingPaste = null }, TerminalTags.pasteConfirmCancel)
        },
    )
}

@Composable
private fun LinkDialog(state: TerminalPaneState) {
    val target = state.pendingLink ?: return
    AlertDialog(
        onDismissRequest = { state.pendingLink = null },
        modifier = PopupTags,
        title = { Text("Open this link?") },
        text = { Text(target) },
        confirmButton = {
            AmpButton("Open", state::confirmLink, TerminalTags.linkOpen, rank = AmpButtonRank.Primary)
        },
        dismissButton = {
            AmpButton("Cancel", { state.pendingLink = null }, TerminalTags.linkCancel)
        },
    )
}

/** Dialogs and menus are their own windows, so the root's testTagsAsResourceId does not reach them. */
private val PopupTags = Modifier.semantics { testTagsAsResourceId = true }
