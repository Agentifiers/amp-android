package com.agentifiers.amp.thread.composer

import com.agentifiers.amp.design.AmpTheme
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpInputBar
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.model.AgentActivity
import com.agentifiers.amp.model.ComposerActionKind
import com.agentifiers.amp.model.AttachmentDraft
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.Guard
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.OutboxEntry
import com.agentifiers.amp.model.OutboxState
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.StopReason
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.offeredReplies
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.ThreadHandle
import com.agentifiers.amp.thread.composer.attachments.AttachButton
import com.agentifiers.amp.thread.composer.attachments.AttachmentChips
import com.agentifiers.amp.thread.composer.attachments.attachmentDropTarget
import com.agentifiers.amp.thread.composer.attachments.rememberAttachmentTray
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * The composer's buttons, primary first. While busy the primary is Queue, whose long press steers, and the other is
 * the stop button, so the field keeps the width of one more button (review M3).
 */
fun composerActions(activity: AgentActivity, hasText: Boolean): List<ComposerActionKind> = when {
    activity !is AgentActivity.Busy -> listOf(ComposerActionKind.Send)
    hasText -> listOf(ComposerActionKind.Queue, ComposerActionKind.InterruptAndSend)
    else -> listOf(ComposerActionKind.Queue, ComposerActionKind.Interrupt)
}

fun intentsFor(kind: ComposerActionKind, text: String, attachments: PersistentList<AttachmentDraft> = persistentListOf()): List<ThreadIntent> {
    val draft = Draft(text, attachments)
    return when (kind) {
        ComposerActionKind.Send -> listOf(ThreadIntent.Send(draft, SendMode.Auto))
        ComposerActionKind.Steer -> listOf(ThreadIntent.Send(draft, SendMode.Steer))
        ComposerActionKind.Queue -> listOf(ThreadIntent.Send(draft, SendMode.Queue))
        ComposerActionKind.Interrupt -> listOf(ThreadIntent.Interrupt)
        ComposerActionKind.InterruptAndSend -> listOf(ThreadIntent.Interrupt, ThreadIntent.Send(draft, SendMode.Auto))
    }
}

internal fun stoppedNotice(reason: StopReason): String = when (reason) {
    StopReason.Archived -> "This thread is archived."
    StopReason.MissingRivetToken -> "This build has no Rivet token."
}

/** Null for entries still on their way; those need nothing from the user. */
internal fun outboxNotice(entry: OutboxEntry): String? = when (val state = entry.state) {
    is OutboxState.Rejected -> state.reason
    OutboxState.Unknown -> "${entry.preview} sent, but the connection dropped before Amp answered."
    is OutboxState.Dropped -> "${entry.preview} was not sent: " + when (state.why) {
        is Guard.QueuedStillPresent -> "the queued message is gone."
        Guard.AgentFailed -> "the agent is no longer in an error state."
        Guard.SetupFailed -> "setup is no longer failed."
        is Guard.GatePending -> "it was answered elsewhere."
    }
    OutboxState.Queued, is OutboxState.Sending, is OutboxState.WaitingForNetwork -> null
}

private const val DOUBLE_ESC_MS = 1_000L

/**
 * One row (UX-14): Attach, the field, then the stop button and the primary rightmost. Idle: Send. Busy: Interrupt
 * (Interrupt and Send once the field has text), then Queue, whose long press steers. A queued message steers from the
 * queue strip.
 */
@Composable
fun Composer(state: ThreadState, handle: ThreadHandle, text: MutableState<String>, scope: AccountScope, modifier: Modifier = Modifier) {
    val tray = rememberAttachmentTray(scope, handle.id)
    val busy = state.activity is AgentActivity.Busy
    val stopped = state.link as? Link.Stopped
    val writable = stopped == null
    val hasDraft = text.value.isNotBlank() || tray.drafts.isNotEmpty()
    val actions = composerActions(state.activity, hasDraft)
    val configuration = LocalConfiguration.current
    val hardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    var lastEsc by remember { mutableLongStateOf(0L) }
    val field = remember { FocusRequester() }

    fun allowed(kind: ComposerActionKind): Boolean = writable && when (kind) {
        ComposerActionKind.Interrupt, ComposerActionKind.InterruptAndSend -> true
        ComposerActionKind.Send, ComposerActionKind.Steer, ComposerActionKind.Queue -> hasDraft && tray.waitNotice == null
    }

    fun run(kind: ComposerActionKind) {
        if (!allowed(kind)) return
        intentsFor(kind, text.value.trim(), tray.drafts).forEach(handle::dispatch)
        if (kind != ComposerActionKind.Interrupt) {
            text.value = ""
            tray.clear()
        }
    }

    fun onEscape() {
        val now = SystemClock.uptimeMillis()
        if (lastEsc != 0L && now - lastEsc <= DOUBLE_ESC_MS) {
            lastEsc = 0L
            run(actions.last())
        } else {
            lastEsc = now
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!hardwareKeyboard || event.type != KeyEventType.KeyDown) return false
        return when (event.key) {
            Key.Enter, Key.NumPadEnter -> when {
                event.isShiftPressed -> false
                event.isCtrlPressed -> true.also { run(if (busy) ComposerActionKind.Steer else actions.first()) }
                else -> true.also { run(actions.first()) }
            }
            Key.Escape -> busy.also { if (busy) onEscape() }
            else -> false
        }
    }

    val spacing = AmpTheme.spacing
    val notice = Modifier.padding(start = spacing.sm, end = spacing.sm, bottom = spacing.xs)
    Column(modifier.fillMaxWidth().attachmentDropTarget(tray).padding(spacing.composerMargin)) {
        if (stopped != null) {
            Text(stoppedNotice(stopped.reason), style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, modifier = notice.testTag(ThreadTags.composerNotice))
        } else {
            state.outbox.lastOrNull { outboxNotice(it) != null }?.let { entry -> OutboxNotice(entry, handle, notice) }
        }
        AttachmentChips(tray, Modifier.padding(horizontal = spacing.sm))
        tray.waitNotice?.let { Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, modifier = notice) }
        SuggestedReplies(
            replies = if (hasDraft) emptyList() else state.offeredReplies(),
            onSend = { reply -> intentsFor(ComposerActionKind.Send, reply.text).forEach(handle::dispatch) },
            onEdit = { reply ->
                text.value = reply.text
                field.requestFocus()
            },
            modifier = Modifier.padding(horizontal = spacing.sm),
        )
        AmpInputBar(
            value = text.value,
            onValueChange = { text.value = it },
            placeholder = if (busy) "Steer the agent" else "Message the agent",
            testTag = ThreadTags.composerInput,
            enabled = writable,
            maxLines = 6,
            fieldModifier = Modifier.focusRequester(field).onPreviewKeyEvent(::onKey),
            leading = { AttachButton(tray, Modifier) },
            trailing = {
                actions.subList(1, actions.size).forEach { kind -> ActionButton(kind, enabled = allowed(kind), onRun = ::run) }
                ActionButton(actions.first(), enabled = allowed(actions.first()), onRun = ::run)
            },
        )
    }
}

@Composable
private fun ActionButton(kind: ComposerActionKind, enabled: Boolean, onRun: (ComposerActionKind) -> Unit) {
    when (kind) {
        ComposerActionKind.Send -> PrimaryAction(AmpIcons.Send, "Send", ThreadTags.composerSend, enabled, onClick = { onRun(kind) })
        ComposerActionKind.Queue -> PrimaryAction(
            AmpIcons.ListPlus,
            "Queue",
            ThreadTags.composerQueue,
            enabled,
            onClick = { onRun(kind) },
            longClick = LongClick("Steer Now") { onRun(ComposerActionKind.Steer) },
        )
        // No button of its own: Queue's long press and the queue strip's Steer Now steer.
        ComposerActionKind.Steer -> Unit
        ComposerActionKind.Interrupt -> AmpIconButton(AmpIcons.Square, "Interrupt", { onRun(kind) }, ThreadTags.composerInterrupt, enabled = enabled)
        ComposerActionKind.InterruptAndSend -> AmpIconButton(AmpIcons.Square, "Interrupt and Send", { onRun(kind) }, ThreadTags.composerInterrupt, enabled = enabled)
    }
}

@Composable
private fun OutboxNotice(entry: OutboxEntry, handle: ThreadHandle, modifier: Modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(outboxNotice(entry).orEmpty(), style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger, modifier = modifier.weight(1f).testTag(ThreadTags.composerNotice))
        if (entry.state !is OutboxState.Dropped) {
            AmpButton("Retry", { handle.resend(entry.id) }, ThreadTags.composerResend, rank = AmpButtonRank.Tertiary)
        }
        AmpButton("Discard", { handle.discard(entry.id) }, ThreadTags.composerDiscard, rank = AmpButtonRank.Secondary)
    }
}

private class LongClick(val label: String, val action: () -> Unit)

/**
 * The composer's primary button, drawn here rather than with AmpIconButton for the long press it lacks. It shares that
 * button's disabled look, a `raised` circle with an `inkMuted` glyph.
 */
@Composable
private fun PrimaryAction(icon: ImageVector, description: String, testTag: String, enabled: Boolean, onClick: () -> Unit, longClick: LongClick? = null) {
    val metrics = AmpTheme.metrics
    val colors = AmpTheme.colors
    Box(
        Modifier
            .size(metrics.touch)
            .testTag(testTag)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = metrics.iconButton / 2),
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = longClick?.label,
                onClick = onClick,
                onLongClick = longClick?.action,
            )
            .semantics { contentDescription = description }
            .padding((metrics.touch - metrics.iconButton) / 2)
            .background(if (enabled) colors.accents.accent else colors.surfaces.raised, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) colors.text.onAccent else colors.text.inkMuted, modifier = Modifier.size(metrics.icon))
    }
}
