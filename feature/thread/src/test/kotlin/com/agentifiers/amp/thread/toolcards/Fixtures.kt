package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.ToolInput
import com.agentifiers.amp.model.ToolName
import com.agentifiers.amp.model.ToolRun
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/** A [JsonValue] from Kotlin literals: maps, lists, strings, numbers, booleans, null. */
internal fun jv(value: Any?): JsonValue = when (value) {
    null -> JsonValue.Null
    is JsonValue -> value
    is String -> JsonValue.Str(value)
    is Number -> JsonValue.Num(value.toDouble())
    is Boolean -> JsonValue.Bool(value)
    is List<*> -> JsonValue.Arr(value.map(::jv).toPersistentList())
    is Map<*, *> -> JsonValue.Obj(value.entries.associate { (k, v) -> k as String to jv(v) }.toPersistentMap())
    else -> error("not JSON: $value")
}

internal fun call(id: String, name: String, input: Map<String, Any?>, run: ToolRun, displayTitle: String? = null) =
    ToolCall(ToolCallId(id), ToolName(name), displayTitle, ToolInput.Complete(jv(input)), run)

internal fun done(result: Any?, userInput: Any? = null) = ToolRun.Done(jv(result), jv(userInput))
