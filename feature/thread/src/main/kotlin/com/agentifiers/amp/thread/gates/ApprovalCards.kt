package com.agentifiers.amp.thread.gates

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.GateTags
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.model.ChoiceGate
import com.agentifiers.amp.model.ChoiceQuestion
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.McpApprovalScope
import com.agentifiers.amp.model.McpServerApprovalGate
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.UnsupportedWhy
import com.agentifiers.amp.model.displayName

/** Allow is the decision being approved, so the whole input stays readable above it, scrolling instead of truncating. */
@Composable
internal fun ToolApprovalCard(gate: ToolApprovalGate, answer: (GateResponse) -> Unit) {
    var denying by rememberSaveable(gate.id.raw) { mutableStateOf(false) }
    var feedback by rememberSaveable(gate.id.raw) { mutableStateOf("") }
    GateFrame(
        gate,
        gate.tool.displayName(),
        actions = {
            GateActions {
                if (denying) {
                    AmpButton("Send Denial", onClick = { answer(gate.deny(feedback.trim().ifEmpty { null })) }, testTag = GateTags.approvalDenySend)
                } else {
                    AmpButton("Deny", onClick = { denying = true }, testTag = GateTags.approvalDeny)
                }
                AmpButton("Allow", onClick = { answer(gate.approve()) }, testTag = GateTags.approvalAllow, rank = AmpButtonRank.Primary)
            }
        },
    ) {
        gate.reason?.let { Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.status.warning) }
        MonoBlock(gate.summary)
        if (gate.answerable && denying) {
            AmpTextField(
                value = feedback,
                onValueChange = { feedback = it },
                testTag = GateTags.approvalDenyFeedback,
                modifier = Modifier.fillMaxWidth(),
                label = "Tell the agent why",
            )
        }
    }
}

/** Option tags count across every question, so question 1's first option follows question 0's last. */
@Composable
internal fun ChoiceCard(gate: ChoiceGate, answer: (GateResponse) -> Unit) {
    val questions = gate.questions
    val offsets = remember(questions) { questions.runningFold(0) { at, question -> at + question.options.size } }
    var picked by rememberSaveable(gate.id.raw) { mutableStateOf(listOf<Int>()) }
    var other by rememberSaveable(gate.id.raw) { mutableStateOf(List(questions.size) { "" }) }
    fun range(q: Int) = offsets[q] until offsets[q + 1]
    fun chosen(q: Int) = range(q).filter { it in picked }

    val complete = questions.indices.all { q -> chosen(q).isNotEmpty() || other[q].isNotBlank() }
    GateFrame(
        gate,
        if (questions.size == 1) "Question" else "${questions.size} questions",
        actions = {
            GateActions {
                AmpButton("Decline", onClick = { answer(gate.decline()) }, testTag = GateTags.decline)
                AmpButton(
                    "Submit",
                    onClick = {
                        val selections = questions.withIndex().associate { (q, question) -> question.key to chosen(q).map { question.options[it - offsets[q]] } }
                        val freeText = questions.withIndex().filter { (q, _) -> other[q].isNotBlank() }.associate { (q, question) -> question.key to other[q] }
                        answer(gate.answer(selections, freeText))
                    },
                    testTag = GateTags.submit,
                    rank = AmpButtonRank.Primary,
                    enabled = complete,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.md)) {
            questions.forEachIndexed { q, question ->
                Column {
                    Text(question.prompt, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
                    Text(question.hint, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
                    question.options.forEachIndexed { i, option ->
                        val n = offsets[q] + i
                        val tag = Modifier.testTag(GateTags.option(n))
                        if (question.multi) {
                            CheckboxRow(option, checked = n in picked, enabled = gate.answerable, onToggle = { picked = if (n in picked) picked - n else picked + n }, modifier = tag)
                        } else {
                            RadioRow(option, selected = n in picked, enabled = gate.answerable, onSelect = { picked = picked.filterNot { it in range(q) } + n }, modifier = tag)
                        }
                    }
                    if (question.allowOther) {
                        AmpTextField(
                            value = other[q],
                            onValueChange = { text -> other = other.toMutableList().also { it[q] = text } },
                            testTag = GateTags.choiceOther(q),
                            modifier = Modifier.fillMaxWidth(),
                            label = "Other",
                            enabled = gate.answerable,
                        )
                    }
                }
            }
        }
    }
}

private val ChoiceQuestion.hint: String get() = if (multi) "Pick any" else "Pick one"

/** No workspace-wide button: the app cannot tell whether the viewer is a workspace admin. */
@Composable
internal fun McpServerApprovalCard(gate: McpServerApprovalGate, answer: (GateResponse) -> Unit) {
    GateFrame(
        gate,
        "Run MCP server ${gate.serverName}?",
        actions = {
            GateActions {
                AmpButton("Always Allow for Me", onClick = { answer(gate.approve(McpApprovalScope.Project)) }, testTag = GateTags.mcpAlwaysForMe)
                AmpButton("Allow Once", onClick = { answer(gate.approve(McpApprovalScope.Thread)) }, testTag = GateTags.submit, rank = AmpButtonRank.Primary)
            }
        },
    ) {
        MonoBlock(gate.command)
    }
}

@Composable
internal fun UnsupportedCard(gate: UnsupportedGate, onAnswerOnWeb: () -> Unit) {
    GateFrame(
        gate,
        gate.kindLabel,
        actionable = true,
        actions = { GateActions { AmpButton("Answer on web", onClick = onAnswerOnWeb, testTag = GateTags.answerOnWeb, rank = AmpButtonRank.Primary) } },
    ) {
        val why = when (gate.why) {
            UnsupportedWhy.AnswerRejected -> "Amp didn't accept the answer from this app."
            UnsupportedWhy.UnknownKind, UnsupportedWhy.DecodeFailed, UnsupportedWhy.AnswerPathNotVerified -> "This request needs an Amp client that supports it."
        }
        Text(why, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
    }
}

@Composable
internal fun MonoBlock(text: String) {
    val shape = AmpTheme.shapes.control
    Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(shape).background(AmpTheme.colors.surfaces.pane).verticalScroll(rememberScrollState()).padding(AmpTheme.spacing.sm)) {
        Text(text, style = Mono)
    }
}
