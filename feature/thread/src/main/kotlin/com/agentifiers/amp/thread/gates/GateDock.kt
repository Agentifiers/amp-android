package com.agentifiers.amp.thread.gates

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import com.agentifiers.amp.design.GateTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.ChoiceGate
import com.agentifiers.amp.model.ElicitationGate
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateOutcome
import com.agentifiers.amp.model.GateRecord
import com.agentifiers.amp.model.GateSource
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.McpServerApprovalGate
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.PluginNotice
import com.agentifiers.amp.model.ReadOnlyReason
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.displayName
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pending human gates, recent gate records and the newest plugin notice, between the queue strip and the composer. */
@Composable
fun GateDock(state: ThreadState, onIntent: (ThreadIntent) -> Unit, onAnswerOnWeb: () -> Unit, modifier: Modifier = Modifier) {
    TickOnNewGate(state.gates)

    var tapped by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedNotice by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    // Keeps a collapsed card's saveable drafts, so tapping another chip and back loses nothing.
    val drafts = rememberSaveableStateHolder()

    val gates = state.gates
    val expanded = gates.firstOrNull { it.id.raw == tapped }
        ?: gates.firstOrNull { it.answerability == Answerability.Answerable }
        ?: gates.firstOrNull()
    val yours = state.gateRecords.filter { it.outcome is GateOutcome.ByYou }.takeLast(3)
    val settled = state.gateRecords.lastOrNull { it.outcome !is GateOutcome.ByYou }
    val settledVisible = isBefore(settled?.let { it.at + 3.seconds })
    val notice = state.pluginNotices.lastOrNull()?.takeIf { it.id > dismissedNotice }

    if (gates.isEmpty() && yours.isEmpty() && !settledVisible && notice == null) return

    Column(
        modifier.fillMaxWidth().atMostFractionOfHeight(DOCK_SHARE).background(LocalPaneSurface.current).testTag(GateTags.dock)
            .padding(start = AmpTheme.spacing.paneX, top = AmpTheme.spacing.sm, end = AmpTheme.spacing.paneX),
        verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.blockGap),
    ) {
        notice?.let { shown -> NoticeBar(shown, onDismiss = { dismissedNotice = shown.id }) }
        yours.forEach { RecordRow(it, Modifier.testTag(GateTags.record(it.gate))) }
        if (settledVisible && settled != null) RecordRow(settled, Modifier.testTag(GateTags.answeredElsewhere))
        gates.forEach { gate ->
            key(gate.id.raw) {
                if (gate.id == expanded?.id) {
                    // The card takes what the rows around it leave, so its own body scrolls and its actions stay in view.
                    Box(Modifier.weight(1f, fill = false)) {
                        drafts.SaveableStateProvider(gate.id.raw) { GateCard(gate, onIntent, onAnswerOnWeb) }
                    }
                } else {
                    GateChip(gate, onClick = { tapped = gate.id.raw })
                }
            }
        }
    }
}

private const val DOCK_SHARE = 0.6f

/**
 * ThreadPane's Column measures the dock before its weighted transcript, so the height offered here is the pane below
 * the title bar. Capping the dock at [share] of it keeps the question above the gate in view (UX-13).
 */
private fun Modifier.atMostFractionOfHeight(share: Float) = layout { measurable, constraints ->
    val max = if (constraints.hasBoundedHeight) (constraints.maxHeight * share).toInt() else constraints.maxHeight
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight.coerceAtMost(max), maxHeight = max))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
private fun TickOnNewGate(gates: List<HumanGate>) {
    val haptic = LocalHapticFeedback.current
    val ids = gates.mapTo(HashSet()) { it.id }
    val seen = remember { mutableStateOf<Set<GateId>?>(null) }
    LaunchedEffect(ids) {
        val previous = seen.value
        if (previous != null && !previous.containsAll(ids)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        seen.value = ids
    }
}

/** True until [deadline] passes, recomposing once when it does. A null deadline is always past. */
@Composable
private fun isBefore(deadline: Instant?): Boolean {
    var before by remember(deadline) { mutableStateOf(deadline != null && Clock.System.now() < deadline) }
    LaunchedEffect(deadline) {
        if (deadline == null) return@LaunchedEffect
        val left = deadline - Clock.System.now()
        if (left.isPositive()) delay(left)
        before = false
    }
    return before
}

@Composable
private fun GateCard(gate: HumanGate, onIntent: (ThreadIntent) -> Unit, onAnswerOnWeb: () -> Unit) {
    val answer = { response: GateResponse -> onIntent(ThreadIntent.Answer(response)) }
    when (gate) {
        is ToolApprovalGate -> ToolApprovalCard(gate, answer)
        is ChoiceGate -> ChoiceCard(gate, answer)
        is PluginGate.Confirm -> PluginConfirmCard(gate, answer)
        is PluginGate.Input -> PluginInputCard(gate, answer)
        is PluginGate.SecretInput -> PluginSecretInputCard(gate, answer, onSecret = { onIntent(ThreadIntent.AnswerSecret(it)) })
        is PluginGate.Select -> PluginSelectCard(gate, answer)
        is ElicitationGate.Form -> ElicitationFormCard(gate, answer)
        is ElicitationGate.OpenUrl -> ElicitationOpenUrlCard(gate, answer)
        is McpServerApprovalGate -> McpServerApprovalCard(gate, answer)
        is UnsupportedGate -> UnsupportedCard(gate, onAnswerOnWeb)
    }
}

private fun titleOf(gate: HumanGate): String = when (gate) {
    is ToolApprovalGate -> gate.tool.displayName()
    is ChoiceGate -> gate.questions.firstOrNull()?.prompt ?: "Question"
    is PluginGate.Confirm -> gate.title
    is PluginGate.Input -> gate.title
    is PluginGate.SecretInput -> gate.title
    is PluginGate.Select -> gate.title
    is ElicitationGate.Form -> gate.message
    is ElicitationGate.OpenUrl -> gate.message
    is McpServerApprovalGate -> "MCP server ${gate.serverName}"
    is UnsupportedGate -> gate.kindLabel
}

private fun sourceLabel(source: GateSource): String = when (source) {
    GateSource.BuiltIn -> "Built-in"
    is GateSource.Plugin -> source.name
    is GateSource.Mcp -> source.server
}

private fun reasonText(reason: ReadOnlyReason): String = when (reason) {
    ReadOnlyReason.ContributorCannotApprove -> "Only the thread owner can approve tools."
    ReadOnlyReason.ContributorCannotEnterSecrets -> "Only the thread owner can enter secrets."
    ReadOnlyReason.ReaderOnly -> "You can read this thread but not answer it."
    ReadOnlyReason.AnsweredElsewhere -> "Answered elsewhere"
    ReadOnlyReason.Expired -> "Expired"
    ReadOnlyReason.RequiresHumanOnOtherClient -> "Waiting for a person on another client."
}

internal val HumanGate.answerable: Boolean get() = answerability == Answerability.Answerable

/**
 * The expanded card: title and source pinned on top, then the kind's [content] and the read-only reason when the
 * viewer cannot answer, scrolling inside the card, and [actions] pinned under them while [actionable]. Hairlines under
 * the title and over the actions say the body runs on. It sits on `overlay`, one step above the raised fields and code
 * inside it, so they read as inset rather than vanishing.
 */
@Composable
internal fun GateFrame(
    gate: HumanGate,
    title: String,
    actions: @Composable ColumnScope.() -> Unit,
    actionable: Boolean = gate.answerable,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = AmpTheme.spacing
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.overlay).testTag(GateTags.gate(gate.id))) {
        Column(Modifier.padding(start = spacing.userPadX, top = spacing.userPadY, end = spacing.userPadX, bottom = spacing.sm)) {
            Text(title, style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink)
            Text(sourceLabel(gate.source), style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(GateTags.source))
        }
        HorizontalDivider(color = if (scroll.canScrollBackward) AmpTheme.colors.surfaces.line else Color.Transparent)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(scroll)
                .padding(horizontal = spacing.userPadX)
                .then(if (actionable) Modifier else Modifier.padding(bottom = spacing.userPadY)),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            content()
            when (val answerability = gate.answerability) {
                Answerability.Answerable -> Unit
                is Answerability.ReadOnly -> Text(reasonText(answerability.reason), style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(GateTags.readOnlyReason))
            }
        }
        if (actionable) {
            HorizontalDivider(color = if (scroll.canScrollForward) AmpTheme.colors.surfaces.line else Color.Transparent)
            Column(Modifier.padding(horizontal = spacing.userPadX, vertical = spacing.sm), content = actions)
        }
    }
}

@Composable
internal fun GateActions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.actionGap, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun GateChip(gate: HumanGate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.overlay)
            .clickable(onClick = onClick).padding(horizontal = AmpTheme.spacing.md).testTag(GateTags.gate(gate.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(titleOf(gate), style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(sourceLabel(gate.source), style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1, modifier = Modifier.padding(start = AmpTheme.spacing.rowTitleMetaGap))
    }
}

@Composable
private fun RecordRow(record: GateRecord, modifier: Modifier) {
    val text = when (val outcome = record.outcome) {
        is GateOutcome.ByYou -> "${record.title}: ${outcome.line}"
        GateOutcome.Elsewhere -> "Answered elsewhere"
        GateOutcome.Withdrawn -> "Closed by the plugin"
    }
    Text(text, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.fillMaxWidth())
}

@Composable
private fun NoticeBar(notice: PluginNotice, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.overlay).padding(start = AmpTheme.spacing.md).testTag(GateTags.notice),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val link = AmpTheme.colors.status.link
        val payload = remember(notice.text) { noticePayload(notice.text) }
        val lead = payload?.lead ?: notice.text
        val text = remember(lead, link) { linkified(lead, link) }
        Column(Modifier.weight(1f).padding(vertical = AmpTheme.spacing.sm)) {
            if (lead.isNotEmpty()) Text(text, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
            payload?.lines?.forEach { line -> Text(line, style = AmpTheme.type.monoSmall, color = AmpTheme.colors.text.inkMuted) }
        }
        AmpButton("Dismiss", onDismiss, GateTags.noticeDismiss, rank = AmpButtonRank.Secondary)
    }
}

private val urlPattern = Regex("""https?://[^\s<>"]+""")

private fun linkified(text: String, link: Color): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var cursor = 0
    urlPattern.findAll(text).forEach { match ->
        val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'')
        append(text, cursor, match.range.first)
        withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
        cursor = match.range.first + url.length
    }
    append(text, cursor, text.length)
}
