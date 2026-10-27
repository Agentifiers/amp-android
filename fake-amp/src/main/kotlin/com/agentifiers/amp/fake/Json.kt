package com.agentifiers.amp.fake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom

internal val FakeJson = Json { ignoreUnknownKeys = true }

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to element(v) })

internal fun element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map(::element))
    else -> error("not a JSON value: ${value::class.simpleName}")
}

internal operator fun JsonObject.plus(other: Map<String, JsonElement>): JsonObject = JsonObject(LinkedHashMap(this).apply { putAll(other) })
internal operator fun JsonObject.minus(key: String): JsonObject = JsonObject(LinkedHashMap(this).apply { remove(key) })

/** Applies [rewrite] to every string value in the tree, keys untouched. */
internal fun JsonElement.mapStrings(rewrite: (String) -> String): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { it.value.mapStrings(rewrite) })
    is JsonArray -> JsonArray(map { it.mapStrings(rewrite) })
    is JsonPrimitive -> if (isString) JsonPrimitive(rewrite(content)) else this
    JsonNull -> this
}

internal fun JsonObject.mapStrings(rewrite: (String) -> String): JsonObject = (this as JsonElement).mapStrings(rewrite) as JsonObject

/** Every string value in the tree, in document order. */
internal fun JsonElement.strings(): Sequence<String> = when (this) {
    is JsonObject -> values.asSequence().flatMap { it.strings() }
    is JsonArray -> asSequence().flatMap { it.strings() }
    is JsonPrimitive -> if (isString) sequenceOf(content) else emptySequence()
    JsonNull -> emptySequence()
}

private val random = SecureRandom()
private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

/** Amp's id shape: a prefix and 22 characters from `[0-9A-Za-z]` (`spikes/protocol/lib.ts:96-100`). */
internal fun mintId(prefix: String): String = prefix + "-" + String(CharArray(22) { ALPHABET[random.nextInt(ALPHABET.length)] })

internal val AMP_ID = Regex("^(M|TU)-[0-9A-Za-z]{22}$")
