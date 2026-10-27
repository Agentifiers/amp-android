package com.agentifiers.amp.sync.internal.wire

import com.agentifiers.amp.model.ImageSource
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.PromptOrigin
import com.agentifiers.amp.model.QueuedMessage
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolRun
import com.agentifiers.amp.model.UserBlock
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A transcript message as the actor stores it, decoded once at the boundary. */
internal sealed interface DecodedMessage {
    val id: MessageId

    /** A user-role message. [blocks] is empty when it holds only tool results, which is not a human turn. */
    data class User(
        override val id: MessageId,
        val blocks: List<UserBlock>,
        val origin: PromptOrigin,
        val results: Map<ToolCallId, ToolRun>,
    ) : DecodedMessage {
        val text: String? get() = blocks.filterIsInstance<UserBlock.Text>().map { it.markdown }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        val steer: Boolean get() = origin == PromptOrigin.Steer
    }

    /** [parts] by block index in `content`, the same index `delta` frames use; hidden and program blocks have no entry. */
    data class Assistant(override val id: MessageId, val parts: Map<Int, Part>, val state: AssistantState) : DecodedMessage

    /** A command the user ran directly. */
    data class Info(override val id: MessageId, val command: String, val cwd: String?, val run: ToolRun) : DecodedMessage
}

/** One assistant content block. Hidden text and program blocks have no part. */
internal sealed interface Part {
    data class Text(val text: String) : Part
    data class Thinking(val text: String, val redacted: Boolean) : Part
    data class Tool(val id: ToolCallId, val name: String, val displayTitle: String?, val input: JsonValue, val complete: Boolean) : Part
}

/** The assistant message's `state`; [Streaming] while it still arrives through deltas. */
internal sealed interface AssistantState {
    data object Streaming : AssistantState
    /** [stopReason] is absent on live frames and `tool_use` on paged ones when the model called a tool. */
    data class Complete(val stopReason: String?) : AssistantState
    data object Cancelled : AssistantState
    data object Error : AssistantState
}

/** Events key a message by `messageId`; history pages by `protocolMessageID`, with `messageId` as a numeric index. */
internal enum class MessageKey(val field: String) { Event("messageId"), Page("protocolMessageID") }

internal fun decodeMessage(message: JsonObject, key: MessageKey): DecodedMessage? {
    val id = message.string(key.field)?.let(::MessageId) ?: return null
    val content = message.array("content")?.mapNotNull { it as? JsonObject } ?: return null
    return when (message.string("role")) {
        "user" -> DecodedMessage.User(
            id = id,
            blocks = content.mapNotNull(::userBlock),
            origin = originOf(message.obj("meta")),
            results = content.filter { it.string("type") == "tool_result" }.mapNotNull(::toolResult).toMap(),
        )
        "assistant" -> DecodedMessage.Assistant(
            id = id,
            parts = content.withIndex().mapNotNull { (index, block) -> part(block)?.let { index to it } }.toMap(),
            state = assistantState(message.obj("state")),
        )
        "info" -> content.firstOrNull { it.string("type") == "manual_bash_invocation" }?.let { manual(id, it) }
        else -> null
    }
}

internal fun decodeQueued(item: JsonObject): QueuedMessage? {
    val queued = item.obj("queuedMessage") ?: return null
    val id = queued.string("messageId") ?: return null
    val text = queued.array("content")?.mapNotNull { (it as? JsonObject)?.let(::userBlock) as? UserBlock.Text }.orEmpty()
        .joinToString("\n\n") { it.markdown }
    return QueuedMessage(QueuedMessageId(id), text, steer = item.bool("steer") == true)
}

private fun userBlock(block: JsonObject): UserBlock? = when (block.string("type")) {
    "text" -> block.string("text")?.takeIf { block.bool("hidden") != true }?.let(UserBlock::Text)
    "image" -> block.obj("source")?.let(::imageSource)?.let { UserBlock.Image(it, block.string("sourcePath")) }
    else -> null
}

private fun imageSource(source: JsonObject): ImageSource? = when (source.string("type")) {
    "url" -> source.string("url")?.let(ImageSource::Url)
    "base64" -> {
        val data = source.string("data")
        val type = source.string("mediaType") ?: source.string("media_type")
        if (data == null || type == null) null else ImageSource.Inline(type, data)
    }
    else -> null
}

private fun originOf(meta: JsonObject?): PromptOrigin = when {
    meta == null -> PromptOrigin.Typed
    meta.bool("steer") == true -> PromptOrigin.Steer
    meta.bool("fromPuck") == true -> PromptOrigin.Puck
    meta.bool("fromVoiceCall") == true -> PromptOrigin.Voice
    meta.bool("fromAutomation") == true -> PromptOrigin.Automation
    meta.string("changesWorkflow") != null -> PromptOrigin.ChangesAction
    meta["generatedByPlugin"] != null -> PromptOrigin.Plugin
    meta.string("fromExecutorThreadID") != null -> PromptOrigin.OtherThread
    else -> PromptOrigin.Typed
}

internal fun part(block: JsonObject): Part? = when (block.string("type")) {
    "text" -> block.string("text")?.takeIf { block.bool("hidden") != true }?.let(Part::Text)
    "thinking" -> Part.Thinking(block.string("thinking").orEmpty(), redacted = false)
    "redacted_thinking" -> Part.Thinking("", redacted = true)
    "tool_use", "server_tool_use" -> {
        val id = block.string("id")
        val name = block.string("name")
        if (id == null || name == null) {
            null
        } else {
            val input = block.obj("input")?.toJsonValue() ?: JsonValue.Obj(persistentMapOf())
            Part.Tool(ToolCallId(id), name, block.obj("metadata")?.string("displayTitle"), input, complete = block.bool("complete") != false)
        }
    }
    else -> null
}

private fun assistantState(state: JsonObject?): AssistantState = when (state?.string("type")) {
    null -> AssistantState.Streaming
    "cancelled" -> AssistantState.Cancelled
    "error" -> AssistantState.Error
    else -> AssistantState.Complete(state.string("stopReason"))
}

private fun manual(id: MessageId, block: JsonObject): DecodedMessage.Info? {
    val args = block.obj("args") ?: return null
    val cmd = args.string("cmd") ?: return null
    val rest = args.array("args")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    val run = block.obj("toolRun")?.let { runOf(it, null) } ?: ToolRun.Pending
    return DecodedMessage.Info(id, (listOf(cmd) + rest).joinToString(" "), args.string("cwd"), run)
}

private fun toolResult(block: JsonObject): Pair<ToolCallId, ToolRun>? {
    val id = block.string("toolUseID") ?: return null
    val run = block.obj("run") ?: return ToolCallId(id) to ToolRun.Pending
    return ToolCallId(id) to runOf(run, block.obj("userInput"))
}

/** `run{status, result?, error?{message}, progress?, reason?}` from a tool_result (Amp's docs, 438). */
private fun runOf(run: JsonObject, userInput: JsonObject?): ToolRun {
    val result = run["result"]?.toJsonValue() ?: JsonValue.Null
    return when (run.string("status")) {
        "done" -> ToolRun.Done(result, userInput?.toJsonValue() ?: JsonValue.Null)
        "error" -> ToolRun.Error(run.obj("error")?.string("message") ?: run.string("error"), result)
        "cancelled" -> ToolRun.Cancelled(run.string("reason"))
        "rejected-by-user" -> ToolRun.Rejected(userInput?.string("denyFeedback"))
        "queued" -> ToolRun.Queued
        "blocked-on-user" -> ToolRun.BlockedOnUser
        "cancellation-requested" -> ToolRun.CancelRequested
        else -> ToolRun.Active(run["progress"]?.toJsonValue() ?: JsonValue.Null)
    }
}
