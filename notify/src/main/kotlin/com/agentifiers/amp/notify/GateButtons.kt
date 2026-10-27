package com.agentifiers.amp.notify

import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.ChoiceGate
import com.agentifiers.amp.model.ElicitationGate
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.McpServerApprovalGate
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.displayName

/** One notification button for a gate. Android shows at most three. */
internal sealed interface GateButton {
    val label: String

    /** Sends [response] in the background; the notification then reads [resolved]. */
    data class Answer(override val label: String, val response: GateResponse, val resolved: String) : GateButton

    /** A plugin text input answered through RemoteInput. */
    data class TextAnswer(override val label: String, val gate: PluginGate.Input) : GateButton

    /** Opens the thread; [onGate] focuses the gate, for a Review of input too long to read here. */
    data class Open(override val label: String, val onGate: Boolean) : GateButton

    /** Opens the thread on the web, for a gate the app cannot answer natively. */
    data class Web(override val label: String) : GateButton
}

/** The notification text for a gate: what is asked, in full when it fits. */
internal fun gateText(gate: HumanGate): String = when (gate) {
    is ToolApprovalGate -> "${gate.tool.displayName()}: ${gate.summary}"
    is PluginGate.Confirm -> listOf(gate.title, gate.body).filter { it.isNotBlank() }.joinToString("\n")
    is PluginGate.Input -> listOf(gate.title, gate.helpText).filter { it.isNotBlank() }.joinToString("\n")
    is PluginGate.SecretInput -> gate.title
    is PluginGate.Select -> gate.title
    is ChoiceGate -> gate.questions.joinToString("\n") { it.prompt }
    is UnsupportedGate -> gate.kindLabel
    is ElicitationGate.Form -> gate.message
    is ElicitationGate.OpenUrl -> gate.message
    is McpServerApprovalGate -> "${gate.serverName}: ${gate.command}"
}

/**
 * The expanded notification shows about six lines of about 45 characters. Allow appears only when the whole input
 * fits there, so nobody approves a command they could not read (`notifications.md`, "Answer from the notification").
 */
internal fun fitsVisibleText(text: String): Boolean = text.length <= 270 && text.lines().size <= 6

/** The buttons for [gate], per `notifications.md` and the sketch's Notifier.postGate. */
internal fun gateButtons(gate: HumanGate): List<GateButton> {
    val open = GateButton.Open("Open", onGate = true)
    if (gate.answerability != Answerability.Answerable) return listOf(open)
    return when (gate) {
        is ToolApprovalGate -> if (fitsVisibleText(gateText(gate))) {
            listOf(GateButton.Answer("Allow", gate.approve(), "Allowed"), GateButton.Answer("Deny", gate.deny(null), "Denied"), open)
        } else {
            listOf(GateButton.Open("Review", onGate = true), GateButton.Answer("Deny", gate.deny(null), "Denied"), open)
        }
        is PluginGate.Confirm -> if (gate.fileField == null && fitsVisibleText(gateText(gate))) {
            listOf(
                GateButton.Answer("Allow", gate.respond(confirmed = true, file = null), "Allowed"),
                GateButton.Answer("Deny", gate.respond(confirmed = false, file = null), "Denied"),
                open,
            )
        } else {
            listOf(GateButton.Open("Review", onGate = true), GateButton.Answer("Deny", gate.respond(confirmed = false, file = null), "Denied"), open)
        }
        is PluginGate.Input -> listOf(GateButton.TextAnswer(gate.submitLabel.ifBlank { "Answer" }, gate), open)
        is ChoiceGate -> {
            val question = gate.questions.singleOrNull()
            if (question != null && !question.multi && question.options.size <= 3) {
                question.options.map { option -> GateButton.Answer(option, gate.answer(mapOf(question.key to listOf(option)), emptyMap()), option) }
            } else {
                listOf(open)
            }
        }
        is UnsupportedGate -> listOf(GateButton.Web("Answer on web"), open)
        is PluginGate.SecretInput, is PluginGate.Select, is ElicitationGate, is McpServerApprovalGate -> listOf(open)
    }
}
