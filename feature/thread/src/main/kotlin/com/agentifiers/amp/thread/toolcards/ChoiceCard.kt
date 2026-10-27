package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.runtime.Composable
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

sealed interface ChoiceAnswer {
    data class Chosen(val text: String) : ChoiceAnswer
    data object Dismissed : ChoiceAnswer
}

/** `ask_user_choice {question, options}` and what the user answered. The live question is a gate in the dock. */
data class ChoiceCall(val question: String, val options: List<String>, val answer: ChoiceAnswer?)

object ChoiceCard : ToolCard<ChoiceCall> {
    override val names = listOf("ask_user_choice")

    /** The result text the web parses (ampcode.com's web bundle, `function Gl`). */
    private val SELECTED = Regex("^User selected(?: option \\d+)?: ([\\s\\S]+)$")

    override fun decode(call: ToolCall): ChoiceCall? {
        val input = call.input.value
        val question = input.text("question") ?: return null
        val options = input["options"].list.mapNotNull { it.string }
        return ChoiceCall(question, options, answerOf(call.run))
    }

    private fun answerOf(run: ToolRun): ChoiceAnswer? {
        val text = run.result.prose()?.trim()
        if (text != null) {
            if (text.startsWith("User dismissed")) return ChoiceAnswer.Dismissed
            SELECTED.find(text)?.let { return ChoiceAnswer.Chosen(it.groupValues[1].trim()) }
        }
        val asked = (run as? ToolRun.Done)?.userInput?.get("askAnswers")?.strings()?.filter { it.isNotBlank() }?.toList().orEmpty()
        return if (asked.isEmpty()) null else ChoiceAnswer.Chosen(asked.joinToString(", "))
    }

    override fun summary(call: ChoiceCall, run: ToolRun): String = when (val answer = call.answer) {
        is ChoiceAnswer.Chosen -> "${firstLine(call.question)} → ${answer.text}"
        ChoiceAnswer.Dismissed -> "${firstLine(call.question)} → dismissed"
        null -> firstLine(call.question)
    }

    @Composable
    override fun Body(call: ChoiceCall, run: ToolRun, ctx: ToolCardContext) {
        FindText(call.question, ctx)
        val chosen = (call.answer as? ChoiceAnswer.Chosen)?.text
        call.options.forEachIndexed { index, option ->
            val picked = option == chosen
            FindText("${index + 1}. $option${if (picked) " ✓" else ""}", ctx, color = if (picked) AmpTheme.colors.accents.accent else AmpTheme.colors.text.ink)
        }
        if (chosen != null && chosen !in call.options) FindText("Other: $chosen ✓", ctx, color = AmpTheme.colors.accents.accent)
        if (call.answer == ChoiceAnswer.Dismissed) FindText("Dismissed without choosing.", ctx, color = AmpTheme.colors.text.inkMuted)
    }
}
