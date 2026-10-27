package com.agentifiers.amp.thread.transcript

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.code.TerminalOutput
import com.agentifiers.amp.design.code.rememberHighlighted
import com.agentifiers.amp.design.markdown.Markdown
import com.agentifiers.amp.design.markdown.MarkdownImage
import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.AgentError
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.EarlierHistory
import com.agentifiers.amp.model.PendingSend
import com.agentifiers.amp.model.Prompt
import com.agentifiers.amp.model.Recap
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.SendState
import com.agentifiers.amp.model.ImageSource
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadSchedule
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.TurnKey
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.thread.schedule.ScheduleRow
import com.agentifiers.amp.thread.toolcards.ToolCallView
import com.agentifiers.amp.thread.toolcards.ToolCardContext
import com.agentifiers.amp.thread.toolcards.ToolStatusGlyph
import com.agentifiers.amp.thread.toolcards.toolLabel
import com.agentifiers.amp.thread.toolcards.toolStatusColor
import kotlinx.coroutines.launch

/**
 * The transcript list, oldest row first. It follows new rows only while the reader sits at the end; Load Earlier
 * restores the row the reader was looking at after the older page lands above it.
 */
@Composable
fun ThreadTranscript(
    state: ThreadState,
    handle: ThreadHandle,
    images: ImageLoader,
    find: FindState,
    hits: List<FindHit>,
    links: TranscriptLinks,
    schedule: ThreadSchedule?,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val view = state.transcript) {
        TranscriptView.Loading -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Loading transcript…", color = AmpTheme.colors.text.inkMuted) }
        is TranscriptView.Failed -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Couldn't load the transcript: ${view.reason}", color = AmpTheme.colors.status.danger, modifier = Modifier.testTag(ThreadTags.error))
        }
        is TranscriptView.Ready -> key(handle.id) { ReadyTranscript(view, state, handle, images, find, hits, links, schedule, onOpenSchedule, modifier) }
    }
}

/** Where a tapped link goes. Built by the pane, which knows the account's server and the layout's navigation. */
class TranscriptLinks(val open: (String) -> Unit, val openOnWeb: () -> Unit, val cards: (highlight: String?) -> ToolCardContext)

@Composable
private fun ReadyTranscript(
    view: TranscriptView.Ready,
    state: ThreadState,
    handle: ThreadHandle,
    images: ImageLoader,
    find: FindState,
    hits: List<FindHit>,
    links: TranscriptLinks,
    schedule: ThreadSchedule?,
    onOpenSchedule: () -> Unit,
    modifier: Modifier,
) {
    var expansion by remember { mutableStateOf(Expansion.None) }
    val rows = remember(view, state.activity, state.error, state.pending, expansion, schedule) {
        transcriptRows(view, state.activity, state.error, state.pending, expansion, schedule)
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val highlight = find.highlight
    val cards = remember(highlight) { links.cards(highlight) }
    val gatePending = state.gates.isNotEmpty()
    var following by remember { mutableStateOf(true) }
    var anchor by remember { mutableStateOf<ScrollAnchor?>(null) }
    // A list resting within one touch target of the end hides nothing worth the button, so it still counts as at the end.
    val slack = with(LocalDensity.current) { AmpTheme.metrics.touch.roundToPx() }

    // A layout change can push the end out of view for a frame before the follow scroll lands, so only a scroll
    // coming to rest away from the end stops following.
    LaunchedEffect(list, slack) {
        var scrolled = false
        snapshotFlow { list.isScrollInProgress to list.layoutInfo.atEnd(slack) }.collect { (scrolling, atEnd) ->
            when {
                scrolling -> scrolled = true
                scrolled -> {
                    following = atEnd
                    scrolled = false
                }
                atEnd -> following = true
            }
        }
    }
    LaunchedEffect(rows) {
        val kept = anchor
        val index = kept?.let { a -> rows.indexOfFirst { it.key == a.key } } ?: -1
        when {
            kept != null && index > kept.index -> {
                list.scrollToItem(index)
                list.scrollBy(-kept.offset.toFloat())
                anchor = null
            }
            kept != null && view.earlier is EarlierHistory.Failed -> anchor = null
            kept == null && following && rows.isNotEmpty() -> list.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
        }
    }
    // The keyboard shrinks the viewport from the bottom, which would slide the latest rows out of view (UX-15).
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.viewportSize.height }.distinctUntilChanged().collect {
            val last = list.layoutInfo.totalItemsCount - 1
            if (following && anchor == null && last >= 0) list.scrollToItem(last, Int.MAX_VALUE)
        }
    }
    LaunchedEffect(find.moves) {
        val hit = hits.getOrNull(find.current) ?: return@LaunchedEffect
        expansion = expansion.copy(
            steps = hit.group?.let { expansion.steps + it } ?: expansion.steps,
            tools = hit.call?.let { expansion.tools + it } ?: expansion.tools,
            thinking = hit.thinking?.let { expansion.thinking + it } ?: expansion.thinking,
        )
    }
    LaunchedEffect(find.moves, rows) {
        val hit = hits.getOrNull(find.current) ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == hit.rowKey }
        if (index >= 0) list.animateScrollToItem(index)
    }
    LaunchedEffect(find.searchingOlder, view.earlier, hits.size) {
        if (!find.searchingOlder) return@LaunchedEffect
        when {
            hits.size > find.olderBaseline -> find.searchingOlder = false
            view.earlier == EarlierHistory.Available -> handle.loadEarlier()
            view.earlier is EarlierHistory.Failed || view.earlier == EarlierHistory.None -> find.searchingOlder = false
            else -> Unit
        }
    }
    val loadEarlier = {
        anchor = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != TranscriptRow.LoadEarlier.KEY }
            ?.let { ScrollAnchor(it.key as String, it.index, it.offset) }
        handle.loadEarlier()
    }

    Box(modifier.fillMaxWidth()) {
        val fade = AmpTheme.spacing.scrollFade
        LazyColumn(
            Modifier.fillMaxSize().testTag(ThreadTags.transcript).fadeTop(fade, AmpTheme.surfaceRoles.thread),
            state = list,
            contentPadding = PaddingValues(start = AmpTheme.spacing.paneX, end = AmpTheme.spacing.paneX, top = fade, bottom = AmpTheme.spacing.xs),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> row::class }) { index, row ->
                val gap = if (index == 0 || flushUnder(rows[index - 1], row)) AmpTheme.spacing.toolGap else AmpTheme.spacing.blockGap
                Box(Modifier.padding(top = gap)) {
                    TranscriptRowView(row, handle, images, highlight, links, cards, expansion, gatePending, loadEarlier, onOpenSchedule) { expansion = it }
                }
            }
        }
        if (!following) {
            JumpToLatest(Modifier.align(Alignment.BottomCenter).padding(bottom = AmpTheme.spacing.sm)) {
                following = true
                scope.launch {
                    list.animateScrollToItem(rows.lastIndex)
                    list.scrollToItem(rows.lastIndex, Int.MAX_VALUE)
                }
            }
        }
    }
}

/**
 * Floats over the transcript, so it sits on `overlay` with a `line` border to lift it off whatever scrolls behind,
 * a 32dp pill in a 48dp target like AmpButton.
 */
@Composable
private fun JumpToLatest(modifier: Modifier, onClick: () -> Unit) {
    val shape = AmpTheme.shapes.control
    Box(
        modifier.heightIn(min = AmpTheme.metrics.touch).clip(shape).clickable(role = Role.Button, onClick = onClick).testTag(ThreadTags.jumpToLatest),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = AmpTheme.metrics.button).background(AmpTheme.colors.surfaces.overlay, shape)
                .border(AmpTheme.metrics.divider, AmpTheme.colors.surfaces.line, shape).padding(horizontal = AmpTheme.spacing.buttonPadX),
            horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AmpIcons.ChevronDown, contentDescription = null, tint = AmpTheme.colors.text.ink, modifier = Modifier.size(AmpTheme.metrics.icon))
            Text("Jump to Latest", style = AmpTheme.type.button, color = AmpTheme.colors.text.ink, maxLines = 1)
        }
    }
}

/**
 * A2's transcript fades its top [height], so a row scrolled under the header never meets the title with a hard cut.
 * DstIn keeps only the mask's alpha, so any opaque [opaque] color works.
 */
private fun Modifier.fadeTop(height: Dp, opaque: Color) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, opaque), startY = 0f, endY = height.toPx()), blendMode = BlendMode.DstIn)
    }

/** The row the reader was looking at when Load Earlier was pressed, and where it sat in the viewport. */
private class ScrollAnchor(val key: String, val index: Int, val offset: Int)

/** Whether the last row is laid out with its bottom no more than [slack] px below the list's content area. */
internal fun LazyListLayoutInfo.atEnd(slack: Int): Boolean {
    if (totalItemsCount == 0) return true
    val last = visibleItemsInfo.lastOrNull()?.takeIf { it.index == totalItemsCount - 1 } ?: return false
    return last.offset + last.size - (viewportEndOffset - afterContentPadding) <= slack
}

@Composable
private fun TranscriptRowView(
    row: TranscriptRow,
    handle: ThreadHandle,
    images: ImageLoader,
    highlight: String?,
    links: TranscriptLinks,
    cards: ToolCardContext,
    expansion: Expansion,
    gatePending: Boolean,
    onLoadEarlier: () -> Unit,
    onOpenSchedule: () -> Unit,
    onExpansion: (Expansion) -> Unit,
) {
    when (row) {
        is TranscriptRow.LoadEarlier -> LoadEarlierRow(row.state, onLoadEarlier)
        TranscriptRow.CompactionDivider -> Divider("Compacted earlier messages", Modifier.testTag(ThreadTags.compaction))
        is TranscriptRow.PromptRow -> PromptBubble(row.turn, row.prompt, images, highlight, links)
        is TranscriptRow.StepRow -> when (val step = row.step) {
            is Step.Text -> Markdown(
                step.markdown,
                modifier = Modifier.fillMaxWidth().testTag(ThreadTags.text(step.key)),
                highlight = highlight,
                streaming = step.streaming,
                onLink = links.open,
                image = markdownImage(images, links),
            )
            is Step.Thinking -> ThinkingRow(step, step.key in expansion.thinking, highlight) {
                onExpansion(expansion.copy(thinking = expansion.thinking.toggle(step.key)))
            }
            is Step.Tools -> ToolGroupRow(step, expansion.isOpen(step)) {
                onExpansion(expansion.copy(steps = expansion.steps.toggle(step.key)))
            }
            is Step.Manual -> ManualRow(step, highlight)
        }
        is TranscriptRow.CallRow -> ToolCallView(
            row.call,
            expanded = row.call.id in expansion.tools,
            onToggle = { onExpansion(expansion.copy(tools = expansion.tools.toggle(row.call.id))) },
            ctx = cards,
        )
        is TranscriptRow.EndRow -> EndRow(row.turn, row.end)
        is TranscriptRow.RecapRow -> RecapCard(row.recap, highlight, links) { handle.dispatch(ThreadIntent.DismissRecap) }
        is TranscriptRow.ActivityRow -> ActivityRow(row.activity)
        is TranscriptRow.ErrorRow -> ErrorRow(
            row.error,
            gatePending,
            onRetry = { handle.dispatch(ThreadIntent.Retry) },
            onDismiss = { handle.dispatch(ThreadIntent.DismissError) },
            onWeb = links.openOnWeb,
        )
        is TranscriptRow.PendingRow -> PendingBubble(row.send, handle)
        is TranscriptRow.ScheduleRow -> ScheduleRow(row.schedule, onOpenSchedule)
    }
}

/** A markdown image through the account's loader: a tap opens the lightbox, and one that fails offers Open on Web. */
private fun markdownImage(images: ImageLoader, links: TranscriptLinks): MarkdownImage = { url, alt, modifier ->
    AuthedImage(ImageSource.Url(url), images, alt.ifEmpty { null }, links.openOnWeb, modifier)
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

@Composable
private fun LoadEarlierRow(state: EarlierHistory, onLoad: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (state) {
            EarlierHistory.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                CircularProgressIndicator(Modifier.size(AmpTheme.metrics.iconSmall), strokeWidth = 2.dp)
                Text("Loading earlier messages…", style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state is EarlierHistory.Failed) Text("Couldn't load earlier messages: ${state.reason}", style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger)
                AmpButton("Load Earlier", onLoad, ThreadTags.loadEarlier)
            }
        }
    }
}

@Composable
private fun Divider(label: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = AmpTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).heightIn(min = AmpTheme.metrics.divider, max = AmpTheme.metrics.divider).background(AmpTheme.colors.surfaces.line))
        Text(label, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(horizontal = AmpTheme.spacing.sm))
        Box(Modifier.weight(1f).heightIn(min = AmpTheme.metrics.divider, max = AmpTheme.metrics.divider).background(AmpTheme.colors.surfaces.line))
    }
}

/** A2's user message: end-aligned on the userMsg surface, at most [USER_MESSAGE_WIDTH] of the pane, its badge as a label above. */
@Composable
private fun PromptBubble(turn: TurnKey, prompt: Prompt, images: ImageLoader, highlight: String?, links: TranscriptLinks) {
    UserMessage(Modifier.testTag(ThreadTags.turn(turn)), Modifier.background(AmpTheme.surfaceRoles.userMessage).testTag(ThreadTags.message(prompt.id))) {
        prompt.origin.badge?.let { Text(it, style = AmpTheme.type.label, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(bottom = AmpTheme.spacing.userLabelGap)) }
        var imageIndex = 0
        for (block in prompt.blocks) when (block) {
            is UserBlock.Text -> Markdown(block.markdown, highlight = highlight, onLink = links.open, image = markdownImage(images, links))
            is UserBlock.Image -> {
                val index = imageIndex++
                AuthedImage(
                    block.source, images, block.sourcePath ?: "Attached image", links.openOnWeb,
                    Modifier.padding(vertical = AmpTheme.spacing.xs).widthIn(max = 240.dp).heightIn(max = 240.dp).clip(AmpTheme.shapes.control).testTag(ThreadTags.image(prompt.id, index)),
                )
            }
        }
    }
}

/** The end-aligned frame a sent or pending user message shares; [bubble] styles the message itself. */
@Composable
private fun UserMessage(modifier: Modifier, bubble: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.fillMaxWidth(USER_MESSAGE_WIDTH), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.clip(AmpTheme.shapes.userMessage).then(bubble).padding(horizontal = AmpTheme.spacing.userPadX, vertical = AmpTheme.spacing.userPadY),
                content = content,
            )
        }
    }
}

private const val USER_MESSAGE_WIDTH = 0.85f

/** Collapsed by default; collapsed after completion it shows the first line, and redacted thinking never opens. */
@Composable
private fun ThinkingRow(step: Step.Thinking, open: Boolean, highlight: String?, onToggle: () -> Unit) {
    val label = when {
        step.redacted -> "Thinking (redacted)"
        step.streaming -> "Thinking…"
        else -> "Thinking"
    }
    val firstLine = step.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    Column(
        Modifier.fillMaxWidth().testTag(ThreadTags.thinking(step.key))
            .then(if (step.redacted) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)),
    ) {
        Text(
            rememberHighlighted(androidx.compose.ui.text.AnnotatedString(if (open || firstLine.isEmpty()) label else "$label: $firstLine"), highlight),
            style = AmpTheme.type.thinking, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (open && !step.redacted) {
            Text(rememberHighlighted(androidx.compose.ui.text.AnnotatedString(step.text), highlight), style = AmpTheme.type.thinking, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(start = AmpTheme.spacing.sm, top = AmpTheme.spacing.xs))
        }
    }
}

/** A2's tool row: borderless, the group's status glyph, its tool names, the counts, and a chevron, in a 48dp tap target. */
@Composable
private fun ToolGroupRow(step: Step.Tools, open: Boolean, onToggle: () -> Unit) {
    val first = step.calls.first()
    val aggregate = step.summary.aggregate
    val labels = step.calls.map(::toolLabel).distinct().joinToString(", ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch)
            .testTag(ThreadTags.step(first.id))
            .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)
            .padding(horizontal = AmpTheme.spacing.toolPadX),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.toolInnerGap),
    ) {
        ToolStatusGlyph(aggregate)
        Text(labels, style = AmpTheme.type.tool, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(stepChips(step).joinToString(" · "), style = AmpTheme.type.toolMeta, color = AmpTheme.colors.text.inkMuted, maxLines = 1)
        stepWord(step)?.let { Text(it, style = AmpTheme.type.toolMeta, color = toolStatusColor(aggregate), maxLines = 1) }
        Icon(
            if (open) AmpIcons.ChevronDown else AmpIcons.ChevronRight, contentDescription = null, tint = AmpTheme.colors.text.inkFaint,
            modifier = Modifier.size(AmpTheme.metrics.iconSmall).testTag(ThreadTags.stepToggle(first.id)),
        )
    }
}

@Composable
private fun ManualRow(step: Step.Manual, highlight: String?) {
    Column(
        Modifier.fillMaxWidth().clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.raised).padding(horizontal = AmpTheme.spacing.userPadX, vertical = AmpTheme.spacing.userPadY)
            .testTag(ThreadTags.manual(com.agentifiers.amp.model.MessageId(step.key.raw.substringBefore(':')))),
        verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs),
    ) {
        Text(rememberHighlighted(androidx.compose.ui.text.AnnotatedString("$ ${step.command}"), highlight), style = Mono)
        step.cwd?.let { Text(it, style = AmpTheme.type.toolMeta, color = AmpTheme.colors.text.inkMuted) }
        val output = manualOutput(step.run)
        if (output.isNotEmpty()) TerminalOutput(output, highlight = highlight)
        Text(step.run.kind.word, style = AmpTheme.type.toolMeta, color = AmpTheme.colors.text.inkMuted)
    }
}

@Composable
private fun EndRow(turn: TurnKey, end: TurnEnd) {
    val text = if (end == TurnEnd.Interrupted) "Interrupted" else "The agent stopped with an error"
    Text(text, style = AmpTheme.type.meta, color = if (end == TurnEnd.Failed) AmpTheme.colors.status.danger else AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(ThreadTags.turnEnd(turn)))
}

/** A quiet card: the recap is context for the reader coming back, not an alert (UX-28). */
@Composable
private fun RecapCard(recap: Recap, highlight: String?, links: TranscriptLinks, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.raised)
            .padding(start = AmpTheme.spacing.md, bottom = AmpTheme.spacing.md, end = AmpTheme.spacing.md)
            .testTag(ThreadTags.recap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recap", style = AmpTheme.type.label, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.weight(1f).semantics { heading() })
            AmpIconButton(AmpIcons.X, "Dismiss", onDismiss, ThreadTags.recapDismiss)
        }
        Markdown(recap.text, highlight = highlight, onLink = links.open)
    }
}

/**
 * The spinner turns in [SPINNER_STEPS] steps a second instead of every frame, and only while the window is started.
 * Each step costs the whole window a frame, and a working thread can sit on screen for minutes. The angle is read in
 * the layer's draw block, so a step redraws without recomposing.
 */
@Composable
private fun ActivityRow(activity: AgentActivity.Busy) {
    val start = remember(activity) { SystemClock.elapsedRealtime() }
    var now by remember(activity) { mutableLongStateOf(start) }
    val seconds by remember(activity) { derivedStateOf { (now - start) / 1_000 } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activity, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000L / SPINNER_STEPS)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.toolInnerGap),
        modifier = Modifier.padding(start = AmpTheme.spacing.toolPadX).testTag(ThreadTags.activityRow),
    ) {
        Icon(
            AmpIcons.LoaderCircle, contentDescription = null, tint = AmpTheme.colors.text.inkMuted,
            modifier = Modifier.size(AmpTheme.metrics.iconSmall).graphicsLayer { rotationZ = (now - start) * SPINNER_STEPS / 1_000 % SPINNER_STEPS * (360f / SPINNER_STEPS) },
        )
        Text("${activity.word.replaceFirstChar(Char::uppercase)} · ${seconds}s", style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
    }
}

private const val SPINNER_STEPS = 8

/**
 * [error] null: the agent state says error but its message never arrived, so the web is where the details are.
 * A pending gate is what the agent waits on, so while [gatePending] its action keeps the pane's one filled primary.
 */
@Composable
private fun ErrorRow(error: AgentError?, gatePending: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit, onWeb: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.raised).padding(AmpTheme.spacing.md).testTag(ThreadTags.error)) {
        Text(error?.message ?: "The agent stopped with an error.", color = AmpTheme.colors.status.danger, style = AmpTheme.type.body)
        error?.code?.let { Text(it, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta) }
        Row(Modifier.padding(top = AmpTheme.spacing.sm), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            AmpButton("Retry", onRetry, ThreadTags.errorRetry, rank = if (gatePending) AmpButtonRank.Secondary else AmpButtonRank.Primary)
            AmpButton("Dismiss", onDismiss, ThreadTags.errorDismiss)
            if (error == null) AmpButton("Open on Web", onWeb, ThreadTags.errorOpenWeb, rank = AmpButtonRank.Link)
        }
    }
}

@Composable
private fun PendingBubble(send: PendingSend, handle: ThreadHandle) {
    UserMessage(Modifier, Modifier.border(AmpTheme.metrics.divider, AmpTheme.colors.surfaces.line, AmpTheme.shapes.userMessage).testTag(ThreadTags.pending(send.id))) {
        Text(send.text, style = AmpTheme.type.body, color = AmpTheme.colors.text.inkMuted)
        when (val sendState = send.state) {
            SendState.Waiting -> Text("Waiting for connection", style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
            SendState.Sending -> Text("Sending…", style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
            is SendState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not sent: ${sendState.reason}", style = AmpTheme.type.meta, color = AmpTheme.colors.status.danger, modifier = Modifier.weight(1f, fill = false))
                AmpButton(
                    "Retry",
                    { handle.dispatch(ThreadIntent.Send(Draft.text(send.text), if (send.steer) SendMode.Steer else SendMode.Auto, send.id)) },
                    ThreadTags.pendingRetry(send.id),
                    rank = AmpButtonRank.Tertiary,
                )
            }
        }
    }
}
