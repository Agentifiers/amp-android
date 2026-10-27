package com.agentifiers.amp.sync.internal.gates

import com.agentifiers.amp.model.JsonValue
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal fun JsonElement.toJsonValue(): JsonValue = when (this) {
    JsonNull -> JsonValue.Null
    is JsonPrimitive -> when {
        isString -> JsonValue.Str(content)
        booleanOrNull != null -> JsonValue.Bool(booleanOrNull!!)
        else -> doubleOrNull?.let(JsonValue::Num) ?: JsonValue.Str(content)
    }
    is JsonArray -> JsonValue.Arr(map { it.toJsonValue() }.toPersistentList())
    is JsonObject -> JsonValue.Obj(mapValues { it.value.toJsonValue() }.toPersistentMap())
}

/** Whole numbers go out without a fraction, as a person typed them. */
internal fun JsonValue.toJsonElement(): JsonElement = when (this) {
    JsonValue.Null -> JsonNull
    is JsonValue.Bool -> JsonPrimitive(v)
    is JsonValue.Num -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
    is JsonValue.Str -> JsonPrimitive(v)
    is JsonValue.Arr -> JsonArray(items.map { it.toJsonElement() })
    is JsonValue.Obj -> JsonObject(fields.mapValues { it.value.toJsonElement() })
}
