package com.agentifiers.amp.sync.internal.wire

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

/** The wire JSON as the domain's [JsonValue], so feature renderers never import kotlinx.serialization. */
internal fun JsonElement.toJsonValue(): JsonValue = when (this) {
    JsonNull -> JsonValue.Null
    is JsonObject -> JsonValue.Obj(mapValues { it.value.toJsonValue() }.toPersistentMap())
    is JsonArray -> JsonValue.Arr(map { it.toJsonValue() }.toPersistentList())
    is JsonPrimitive -> when {
        isString -> JsonValue.Str(content)
        booleanOrNull != null -> JsonValue.Bool(booleanOrNull!!)
        else -> doubleOrNull?.let(JsonValue::Num) ?: JsonValue.Str(content)
    }
}
