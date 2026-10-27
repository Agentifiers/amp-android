package com.agentifiers.amp.sync.internal.wire

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull

internal val AmpJson = Json { ignoreUnknownKeys = true }

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

/** Accepts integers and fractional numbers such as `1792729569472.0803`, which the thread actor also sends. */
internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()?.toLong()

/**
 * Rivet JSON rejects an integer of 2^32 (captures/probe-json-int.jsonl:9-10) and accepts one written with a
 * fractional part (:3-4). Every integer at or above 2^31 goes out as a `.0` literal, written digit by digit
 * because Double.toString switches to exponent notation.
 */
internal object RivetNumber {
    private const val LIMIT = 1L shl 31

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(value: Long): JsonPrimitive = if (value < LIMIT) JsonPrimitive(value) else JsonUnquotedLiteral("$value.0")
}
