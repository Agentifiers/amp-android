package com.agentifiers.amp.puck

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.components.AmpInputBar
import com.agentifiers.amp.puck.voice.PuckVoiceControls
import com.agentifiers.amp.design.components.AmpDropdownMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.PuckTags
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpHeaderRow
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.MarkdownPart
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.PendingSend
import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.SendState
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.model.Prompt
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.UserBlock
import com.agentifiers.amp.model.TranscriptView
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.ThreadHandle
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The Puck panel: Puck chrome over one conversation thread. [selected] is the focused thread in the same window,
 * which Puck takes as context. With no conversation in [dest], the pane moves to the newest one.
 */
@Composable
fun PuckPane(
    dest: PaneDestination.Puck,
    scope: AccountScope,
    selected: ThreadId?,
    navigate: (PaneDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by scope.puck.conversations.collectAsStateWithLifecycle()
    val conversation = dest.conversation
    LaunchedEffect(conversation, conversations) {
        if (conversation == null) conversations.firstOrNull()?.let { navigate(PaneDestination.Puck(PuckConversationId(it.id))) }
    }
    var newChatFailure by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().background(LocalPaneSurface.current).testTag(ShellTags.pane("puck"))) {
        Header(scope, conversation, conversations, navigate, onNewChatFailure = { newChatFailure = it })
        if (selected != null) ContextChip(scope, selected)
        newChatFailure?.let {
            Text(it, color = AmpTheme.colors.status.danger, style = AmpTheme.type.secondary, modifier = Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs).testTag(PuckTags.newChatFailure))
        }
        when {
            conversation != null -> Conversation(scope.thread(conversation.thread), Modifier.weight(1f))
            conversations.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Puck can start agents to run code for you.", color = AmpTheme.colors.text.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(AmpTheme.spacing.xl).testTag(PuckTags.empty))
            }
            else -> Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Header(
    scope: AccountScope,
    conversation: PuckConversationId?,
    conversations: List<ThreadSummary>,
    navigate: (PaneDestination) -> Unit,
    onNewChatFailure: (String?) -> Unit,
) {
    val coroutines = rememberCoroutineScope()
    var switching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var calling by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val title = conversation?.let { id -> conversations.firstOrNull { it.id == id.thread }?.title ?: "New conversation" } ?: "Puck"

    AmpHeaderRow(Modifier, testTag = null, kind = "puck") {
        Row(Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                AmpChip(title, selected = false, onClick = { switching = true }, testTag = PuckTags.switcher, trailingIcon = AmpIcons.ChevronDown)
                AmpDropdownMenu(expanded = switching, onDismissRequest = { switching = false }) {
                    val groups = groupConversations(conversations, Clock.System.now())
                    fun pick(summary: ThreadSummary) {
                        switching = false
                        navigate(PaneDestination.Puck(PuckConversationId(summary.id)))
                    }
                    if (conversations.isEmpty()) DropdownMenuItem(text = { Text("No conversations yet", color = AmpTheme.colors.text.inkMuted) }, onClick = { switching = false })
                    groups.recent.forEach { ConversationItem(it, current = it.id == conversation?.thread, onClick = ::pick) }
                    if (groups.inactive.isNotEmpty()) {
                        Text(
                            "Inactive Last 72h".uppercase(),
                            color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.section,
                            modifier = Modifier.padding(start = AmpTheme.spacing.md, end = AmpTheme.spacing.md, top = AmpTheme.spacing.sectionTop, bottom = AmpTheme.spacing.sectionBottom)
                                .semantics { heading() }.testTag(PuckTags.switcherInactive),
                        )
                        groups.inactive.forEach { ConversationItem(it, current = it.id == conversation?.thread, onClick = ::pick) }
                    }
                }
            }
            AmpButton("Call", { calling = !calling }, PuckTags.call, enabled = conversation != null)
            AmpButton(
                if (starting) "Starting…" else "New Chat",
                {
                    starting = true
                    onNewChatFailure(null)
                    coroutines.launch {
                        val outcome = scope.puck.open(startFresh = conversation != null)
                        starting = false
                        if (outcome is Outcome.Ok) navigate(PaneDestination.Puck(outcome.value)) else onNewChatFailure(newChatFailure(outcome))
                    }
                },
                PuckTags.newChat,
                enabled = !starting,
            )
            if (conversation != null) {
                Box {
                    AmpIconButton(AmpIcons.EllipsisVertical, "More Actions", { menu = true }, PuckTags.menu)
                    AmpDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Archive Conversation") },
                            onClick = {
                                menu = false
                                scope.thread(conversation.thread).dispatch(ThreadIntent.Archive(true))
                                navigate(PaneDestination.Puck(null))
                            },
                            modifier = Modifier.testTag(PuckTags.archive),
                        )
                    }
                }
            }
        }
    }

    if (calling && conversation != null) PuckVoiceControls(conversation, scope, Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs))
}

@Composable
private fun ConversationItem(summary: ThreadSummary, current: Boolean, onClick: (ThreadSummary) -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(summary.title ?: "New conversation", color = AmpTheme.colors.text.ink, style = AmpTheme.type.rowTitle, fontWeight = if (current) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull("Unread".takeIf { summary.unread }, "Open now".takeIf { current }).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), color = if (summary.unread) AmpTheme.colors.status.done else AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta)
                }
            }
        },
        onClick = { onClick(summary) },
        modifier = Modifier.testTag(PuckTags.conversation(summary.id)),
    )
}

@Composable
private fun ContextChip(scope: AccountScope, selected: ThreadId) {
    val state by scope.thread(selected).state.collectAsStateWithLifecycle()
    Text(
        "Looking at ${state.title ?: "Untitled"}",
        color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = AmpTheme.spacing.paneX).clip(AmpTheme.shapes.chip).background(AmpTheme.colors.surfaces.raised)
            .padding(horizontal = AmpTheme.spacing.chipPadX, vertical = AmpTheme.spacing.xs).testTag(PuckTags.context),
    )
}

@Composable
private fun Conversation(handle: ThreadHandle, modifier: Modifier) {
    val state by handle.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        linkLine(state.link)?.let {
            Text(it, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta, modifier = Modifier.fillMaxWidth().background(AmpTheme.colors.surfaces.pane).padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs).testTag(PuckTags.link))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val transcript = state.transcript) {
                TranscriptView.Loading -> Text("Loading conversation…", color = AmpTheme.colors.text.inkMuted, modifier = Modifier.align(Alignment.Center))
                is TranscriptView.Failed -> Text("Couldn't load the conversation: ${transcript.reason}", color = AmpTheme.colors.status.danger, modifier = Modifier.align(Alignment.Center))
                // Reverse layout keeps the newest row at the bottom.
                is TranscriptView.Ready -> LazyColumn(
                    Modifier.fillMaxSize().testTag(PuckTags.transcript),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.blockGap),
                    contentPadding = PaddingValues(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.sm),
                ) {
                    items(state.pending.asReversed(), key = { "pending-${it.id.raw}" }) { PendingBubble(it) }
                    transcript.turns.asReversed().forEach { turn ->
                        items(turn.steps.asReversed(), key = { it.key.raw }) { StepRow(it) }
                        turn.prompt?.let { prompt -> item(key = prompt.id.raw) { PromptRow(prompt) } }
                    }
                }
            }
        }
        Composer(handle)
    }
}

@Composable
private fun PromptRow(prompt: Prompt) {
    val text = prompt.blocks.filterIsInstance<UserBlock.Text>().joinToString("\n\n") { it.markdown }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text, color = AmpTheme.colors.text.ink, style = AmpTheme.type.body,
            modifier = Modifier.fillMaxWidth(BUBBLE_MAX).wrapContentWidth(Alignment.End).testTag(PuckTags.message(prompt.id)).bubble(),
        )
    }
}

/** A2's user message: right-aligned, at most 85% of the pane, on the raised surface. */
private const val BUBBLE_MAX = 0.85f

@Composable
private fun Modifier.bubble(): Modifier =
    clip(AmpTheme.shapes.userMessage).background(AmpTheme.colors.surfaces.raised)
        .padding(horizontal = AmpTheme.spacing.userPadX, vertical = AmpTheme.spacing.userPadY)

/** Puck's text in full; tools as one line each; thinking stays hidden in chat. */
@Composable
private fun StepRow(step: Step) {
    val tag = Modifier.testTag(PuckTags.step(step.key))
    when (step) {
        is Step.Text -> Text(
            MarkdownPart.withoutFences(step.markdown, step.streaming) + if (step.streaming) " …" else "",
            color = AmpTheme.colors.text.ink, style = AmpTheme.type.body, modifier = tag.fillMaxWidth(),
        )
        is Step.Tools -> Column(tag.fillMaxWidth()) {
            step.calls.forEach { call -> Text(call.displayTitle ?: call.name.raw, style = Mono, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        is Step.Manual -> Text(step.command, style = Mono, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = tag.fillMaxWidth())
        is Step.Thinking -> Unit
    }
}

@Composable
private fun PendingBubble(send: PendingSend) {
    val status = when (val state = send.state) {
        SendState.Waiting -> "Waiting for network"
        SendState.Sending -> "Sending"
        is SendState.Failed -> "Not sent: ${state.reason}"
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxWidth(BUBBLE_MAX).wrapContentWidth(Alignment.End).bubble().testTag(PuckTags.pending(send.id))) {
            Text(send.text, color = AmpTheme.colors.text.ink, style = AmpTheme.type.body)
            Text(status, color = if (send.state is SendState.Failed) AmpTheme.colors.status.danger else AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta)
        }
    }
}

@Composable
private fun Composer(handle: ThreadHandle) {
    var text by rememberSaveable(handle.id.raw) { mutableStateOf("") }
    AmpInputBar(
        value = text,
        onValueChange = { text = it },
        placeholder = "Ask Puck",
        testTag = PuckTags.input,
        modifier = Modifier.padding(AmpTheme.spacing.composerMargin),
        maxLines = 6,
        trailing = {
            AmpIconButton(
                AmpIcons.Send, "Send",
                {
                    handle.dispatch(ThreadIntent.Send(Draft.text(text.trim()), SendMode.Auto))
                    text = ""
                },
                PuckTags.send,
                enabled = text.isNotBlank(),
                primary = true,
            )
        },
    )
}
