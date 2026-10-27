package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.runtime.Composable
import com.agentifiers.amp.design.code.CodeBlock
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

/** What the generic card shows under the input: the result as text or JSON, the empty marker, or the failure. */
internal sealed interface GenericOutput {
    val text: String

    data class Text(override val text: String) : GenericOutput
    data class Json(override val text: String) : GenericOutput

    /** A plugin tool with an empty result (Amp's docs). */
    data object Empty : GenericOutput {
        override val text = "(no tool output)"
    }

    data class Failed(override val text: String) : GenericOutput
}

/** Any tool without a card, and any call its card could not decode. */
object GenericToolCard {
    /** Input keys the web reads first for a call's one-line detail (ampcode.com's web bundle, `function Q`). */
    private val PRIMARY = listOf("path", "filePattern", "pattern", "query", "url", "objective", "question", "description", "prompt")

    fun summary(call: ToolCall): String {
        val input = call.input.value
        PRIMARY.firstNotNullOfOrNull { input.text(it) }?.let { return firstLine(it) }
        val short = (input as? JsonValue.Obj)?.fields?.values?.firstNotNullOfOrNull { value ->
            value.string?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 && '\n' !in it }
        }
        return short.orEmpty()
    }

    internal fun output(run: ToolRun): GenericOutput? = when (run) {
        is ToolRun.Done -> run.result.prose()?.let(GenericOutput::Text)
            ?: if (run.result.isEmpty) GenericOutput.Empty else GenericOutput.Json(run.result.pretty())
        is ToolRun.Error -> GenericOutput.Failed(run.message ?: run.result.prose() ?: run.result.pretty())
        is ToolRun.Active -> run.progress.takeUnless { it.isEmpty }?.let { GenericOutput.Json(it.pretty()) }
        is ToolRun.Cancelled, is ToolRun.Rejected, ToolRun.Pending, ToolRun.Queued, ToolRun.BlockedOnUser, ToolRun.CancelRequested -> null
    }

    @Composable
    fun Body(call: ToolCall, ctx: ToolCardContext) {
        if (!call.input.value.isEmpty) CodeBlock(call.input.value.pretty(), "json", highlight = ctx.highlight, collapseAfterLines = 40)
        when (val output = output(call.run)) {
            is GenericOutput.Text -> CodeBlock(output.text, null, highlight = ctx.highlight, collapseAfterLines = 40)
            is GenericOutput.Json -> CodeBlock(output.text, "json", highlight = ctx.highlight, collapseAfterLines = 40)
            GenericOutput.Empty -> FindText(output.text, ctx, color = AmpTheme.colors.text.inkMuted)
            is GenericOutput.Failed -> FindText(output.text, ctx, color = AmpTheme.colors.status.danger)
            null -> Unit
        }
    }
}
