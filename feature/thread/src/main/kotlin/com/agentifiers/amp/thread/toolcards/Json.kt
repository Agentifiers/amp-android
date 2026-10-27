package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.ToolRun

/** The string at [key], trimmed; null when absent, not a string, or blank. */
internal fun JsonValue.text(key: String): String? = get(key).string?.trim()?.takeIf { it.isNotEmpty() }

/** A Done run's result; [JsonValue.Null] for every other run. */
internal val ToolRun.result: JsonValue get() = (this as? ToolRun.Done)?.result ?: JsonValue.Null

/**
 * What the tool has produced so far: a Done result, an Error's partial result, or an Active run's progress, which
 * the web unwraps from `{type: snapshot, value}` the same way.
 */
internal val ToolRun.payload: JsonValue
    get() = when (this) {
        is ToolRun.Done -> result
        is ToolRun.Error -> result
        is ToolRun.Active -> progress["value"].takeIf { progress["type"].string == "snapshot" } ?: progress
        else -> JsonValue.Null
    }

/** A result as prose: a string, or an array of strings and `{type: text, text}` blocks joined by newlines. */
internal fun JsonValue.prose(): String? = when (this) {
    is JsonValue.Str -> v.takeIf { it.isNotBlank() }
    is JsonValue.Arr -> items.map { item -> (item as? JsonValue.Str)?.v ?: item["text"].string.takeIf { item["type"].string == "text" } ?: return null }
        .joinToString("\n").takeIf { it.isNotBlank() }
    else -> null
}

internal val JsonValue.isEmpty: Boolean
    get() = when (this) {
        JsonValue.Null -> true
        is JsonValue.Str -> v.isBlank()
        is JsonValue.Arr -> items.isEmpty()
        is JsonValue.Obj -> fields.isEmpty()
        else -> false
    }

/** Every string value in the tree, keys excluded, in document order. */
internal fun JsonValue.strings(): Sequence<String> = when (this) {
    is JsonValue.Str -> sequenceOf(v)
    is JsonValue.Arr -> items.asSequence().flatMap { it.strings() }
    is JsonValue.Obj -> fields.values.asSequence().flatMap { it.strings() }
    else -> emptySequence()
}

/** Every object in the tree, this one first. */
internal fun JsonValue.objects(): Sequence<JsonValue.Obj> = when (this) {
    is JsonValue.Obj -> sequenceOf(this) + fields.values.asSequence().flatMap { it.objects() }
    is JsonValue.Arr -> items.asSequence().flatMap { it.objects() }
    else -> emptySequence()
}

/** Two-space indented JSON. */
internal fun JsonValue.pretty(): String = StringBuilder().also { write(it, 0) }.toString()

private fun JsonValue.write(out: StringBuilder, indent: Int) {
    when (this) {
        JsonValue.Null -> out.append("null")
        is JsonValue.Bool -> out.append(v)
        is JsonValue.Num -> out.append(if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString())
        is JsonValue.Str -> out.quote(v)
        is JsonValue.Arr -> block(out, indent, '[', ']', items) { item -> item.write(out, indent + 2) }
        is JsonValue.Obj -> block(out, indent, '{', '}', fields.entries) { (key, value) ->
            out.quote(key).append(": ")
            value.write(out, indent + 2)
        }
    }
}

private inline fun <E> block(out: StringBuilder, indent: Int, open: Char, close: Char, entries: Collection<E>, each: (E) -> Unit) {
    if (entries.isEmpty()) {
        out.append(open).append(close)
        return
    }
    out.append(open).append('\n')
    entries.forEachIndexed { index, entry ->
        repeat(indent + 2) { out.append(' ') }
        each(entry)
        if (index < entries.size - 1) out.append(',')
        out.append('\n')
    }
    repeat(indent) { out.append(' ') }
    out.append(close)
}

private fun StringBuilder.quote(s: String): StringBuilder {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    return append('"')
}

/** The first line of [text], trimmed. */
internal fun firstLine(text: String): String = text.trim().lineSequence().first().trim()

/** "1 image", "3 images". */
internal fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
