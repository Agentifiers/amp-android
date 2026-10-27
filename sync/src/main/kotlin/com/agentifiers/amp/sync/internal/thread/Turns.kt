package com.agentifiers.amp.sync.internal.thread

import com.agentifiers.amp.model.AssistantText
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.Prompt
import com.agentifiers.amp.model.Step
import com.agentifiers.amp.model.StepKey
import com.agentifiers.amp.model.SuggestedReply
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolInput
import com.agentifiers.amp.model.ToolName
import com.agentifiers.amp.model.ToolRun
import com.agentifiers.amp.model.Turn
import com.agentifiers.amp.model.TurnEnd
import com.agentifiers.amp.model.TurnKey
import com.agentifiers.amp.sync.internal.wire.AssistantState
import com.agentifiers.amp.sync.internal.wire.DecodedMessage
import com.agentifiers.amp.sync.internal.wire.Part
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * Groups messages into turns. A human user message opens a turn; a user message of only tool results attaches its
 * results to the calls before it; consecutive tool calls, across assistant messages, form one [Step.Tools].
 * Streaming messages follow the stored ones, as the agent is still writing them.
 */
internal fun buildTurns(messages: Collection<DecodedMessage>, streaming: Map<MessageId, StreamingMessage>): PersistentList<Turn> {
    val results = HashMap<ToolCallId, ToolRun>()
    for (message in messages) if (message is DecodedMessage.User) results.putAll(message.results)

    val turns = ArrayList<Turn>()
    var open: TurnBuilder? = null
    fun builder(anchor: MessageId) = open ?: TurnBuilder(TurnKey(anchor), null).also { open = it }

    for (message in messages) {
        when (message) {
            is DecodedMessage.User -> if (message.blocks.isNotEmpty()) {
                open?.let { turns += it.build() }
                open = TurnBuilder(TurnKey(message.id), Prompt(message.id, message.blocks.toPersistentList(), message.origin))
            }
            is DecodedMessage.Assistant -> builder(message.id).assistant(message.id, message.parts, message.state, results)
            is DecodedMessage.Info -> builder(message.id).step(Step.Manual(StepKey.of(message.id, 0), message.command, message.cwd, message.run))
        }
    }
    for ((id, message) in streaming) builder(id).assistant(id, message.parts, AssistantState.Streaming, results)
    open?.let { turns += it.build() }
    return turns.toPersistentList()
}

private class TurnBuilder(val key: TurnKey, val prompt: Prompt?) {
    private val steps = ArrayList<Step>()
    private var end = TurnEnd.Open
    private var replies = persistentListOf<SuggestedReply>()

    fun step(step: Step) {
        steps += step
    }

    /** Parsed here rather than at decode, because a streaming block arrives across deltas that append raw text. */
    fun assistant(id: MessageId, parts: Map<Int, Part>, state: AssistantState, results: Map<ToolCallId, ToolRun>) {
        val streaming = state == AssistantState.Streaming
        replies = persistentListOf()
        for ((index, part) in parts.entries.sortedBy { it.key }) {
            val key = StepKey.of(id, index)
            when (part) {
                is Part.Text -> {
                    val text = AssistantText.parse(part.text)
                    if (text.replies.isNotEmpty()) replies = text.replies
                    if (text.prose.isNotBlank() || streaming) steps += Step.Text(key, text.prose, streaming)
                }
                is Part.Thinking -> if (part.text.isNotBlank() || part.redacted || streaming) {
                    steps += Step.Thinking(key, part.text, part.redacted, streaming)
                }
                is Part.Tool -> {
                    val call = ToolCall(
                        id = part.id,
                        name = ToolName(part.name),
                        displayTitle = part.displayTitle,
                        input = if (part.complete) ToolInput.Complete(part.input) else ToolInput.Streaming(part.input),
                        run = results[part.id] ?: ToolRun.Pending,
                    )
                    val last = steps.lastOrNull()
                    if (last is Step.Tools) steps[steps.lastIndex] = last.copy(calls = last.calls.add(call)) else steps += Step.Tools(key, persistentListOf(call))
                }
            }
        }
        end = when (state) {
            AssistantState.Streaming -> TurnEnd.Open
            AssistantState.Cancelled -> TurnEnd.Interrupted
            AssistantState.Error -> TurnEnd.Failed
            is AssistantState.Complete -> {
                val calledTool = state.stopReason == "tool_use" || (state.stopReason == null && parts.values.any { it is Part.Tool })
                if (calledTool) TurnEnd.Open else TurnEnd.Done
            }
        }
    }

    /** A cancel during a tool leaves the message `complete` with stop reason tool_use; only the cancelled result says so (S5). */
    fun build(): Turn {
        val cancelledLast = (steps.lastOrNull() as? Step.Tools)?.calls?.any { it.run is ToolRun.Cancelled } == true
        return Turn(key, prompt, steps.toPersistentList(), if (end == TurnEnd.Open && cancelledLast) TurnEnd.Interrupted else end, replies)
    }
}
