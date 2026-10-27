package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.code.rememberHighlighted
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun
import com.agentifiers.amp.model.displayName

/**
 * One tool family's card. [decode] reads the call's input and its run's result into [T] and returns null on any
 * shape surprise, so a server-side change degrades one card to [GenericToolCard], never the transcript.
 */
interface ToolCard<T : Any> {
    val names: List<String>
    fun decode(call: ToolCall): T?
    fun summary(call: T, run: ToolRun): String

    @Composable
    fun Body(call: T, run: ToolRun, ctx: ToolCardContext)
}

/** Everything a card may use. Cards are pure renderers: opening anything goes through [openUrl] or [navigate]. */
class ToolCardContext(
    /** AccountScope.images.coil: Bearer to the account's origins. */
    val images: coil3.ImageLoader,
    /** The find-in-thread term. */
    val highlight: String?,
    val openUrl: (String) -> Unit,
    val navigate: (PaneDestination) -> Unit,
    /** The thread the call belongs to. */
    val thread: ThreadId,
)

object ToolCards {
    val all: List<ToolCard<*>> = listOf(
        ShellCard, EditCard, ReadCard, SubagentCard, ThreadToolCard, WebCard, MediaCard, ChoiceCard, TodoCard, FilesCard,
    )
    val byName: Map<String, ToolCard<*>> = indexByName(all)
}

/** Fails on a name two cards claim, which `associateBy` would silently resolve to the last one. */
internal fun indexByName(cards: List<ToolCard<*>>): Map<String, ToolCard<*>> {
    val index = LinkedHashMap<String, ToolCard<*>>()
    for (card in cards) for (name in card.names) {
        val prior = index.put(name, card)
        check(prior == null) { "Tool name $name is claimed by ${prior!!::class.simpleName} and ${card::class.simpleName}" }
    }
    return index
}

/** A call and the value its card decoded, kept together so the card's type parameter never escapes. */
internal class Decoded<T : Any>(private val card: ToolCard<T>, val value: T) {
    fun summary(run: ToolRun): String = card.summary(value, run)

    @Composable
    fun Body(run: ToolRun, ctx: ToolCardContext) = card.Body(value, run, ctx)
}

internal fun decoded(call: ToolCall): Decoded<*>? = ToolCards.byName[call.name.raw]?.let { decodeWith(it, call) }

private fun <T : Any> decodeWith(card: ToolCard<T>, call: ToolCall): Decoded<T>? = card.decode(call)?.let { Decoded(card, it) }

/** `displayTitle`, else the label every surface shares for the tool's name. */
fun toolLabel(call: ToolCall): String = call.displayTitle?.takeIf { it.isNotBlank() } ?: call.name.displayName()

fun toolSummary(call: ToolCall): String = decoded(call)?.summary(call.run) ?: GenericToolCard.summary(call)

fun toolSearchText(call: ToolCall): String {
    val run = call.run
    val runStrings = when (run) {
        is ToolRun.Done -> run.result.strings() + run.userInput.strings()
        is ToolRun.Error -> sequenceOf(run.message.orEmpty()) + run.result.strings()
        is ToolRun.Active -> run.progress.strings()
        is ToolRun.Cancelled -> sequenceOf(run.reason.orEmpty())
        is ToolRun.Rejected -> sequenceOf(run.feedback.orEmpty())
        ToolRun.Pending, ToolRun.Queued, ToolRun.BlockedOnUser, ToolRun.CancelRequested -> emptySequence()
    }
    return (sequenceOf(toolLabel(call), toolSummary(call)) + call.input.value.strings() + runStrings)
        .filter { it.isNotEmpty() }.joinToString("\n")
}

/** Card text that the find term highlights. */
@Composable
internal fun FindText(text: String, ctx: ToolCardContext, modifier: Modifier = Modifier, mono: Boolean = false, color: Color = AmpTheme.colors.text.ink) {
    val display = rememberHighlighted(AnnotatedString(text), ctx.highlight)
    Text(display, modifier, color = color, style = if (mono) Mono.copy(color = color) else AmpTheme.type.body)
}

/** The file extension as a code language label: `calc.py` -> "py". */
internal fun languageOf(path: String): String? = path.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
