package com.agentifiers.amp.sync.internal.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * devalue's `unflatten`, which SvelteKit uses for page data (web chunk `bd6913a5.js`, `function at`): slot 0 is the
 * root, containers hold slot indices, and negative indices are -1 undefined, -2 an array hole, -3 NaN, -4 Infinity,
 * -5 -Infinity, -6 -0. A string-tagged array is a typed value; `Date`, `BigInt`, `RegExp`, and `Object` become their
 * string or primitive, `Set` an array, `Map` an array of pairs, `null` an object. Values JSON cannot hold, an unknown
 * tag such as `Promise`, and a cycle read as JSON null.
 */
internal object Devalue {
    fun unflatten(values: JsonArray): JsonElement = Hydrator(values).hydrate(0)

    private class Hydrator(private val values: JsonArray) {
        private val done = HashMap<Int, JsonElement>()
        private val open = HashSet<Int>()

        fun hydrate(index: Int): JsonElement = when {
            index == NEGATIVE_ZERO -> JsonPrimitive(0)
            index < 0 || index >= values.size -> JsonNull
            else -> done[index] ?: if (!open.add(index)) JsonNull else slot(values[index]).also { done[index] = it; open.remove(index) }
        }

        private fun slot(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (_, at) -> at.index()?.let(::hydrate) ?: JsonNull })
            is JsonArray -> typed(value) ?: JsonArray(value.map { at -> at.index()?.let(::hydrate) ?: JsonNull })
            else -> value
        }

        private fun typed(value: JsonArray): JsonElement? {
            val tag = (value.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val rest = value.drop(1)
            return when (tag) {
                "Date", "BigInt", "RegExp", "Object" -> rest.firstOrNull() as? JsonPrimitive ?: JsonNull
                "Set" -> JsonArray(rest.map { it.index()?.let(::hydrate) ?: JsonNull })
                "Map" -> JsonArray(rest.chunked(2).map { pair -> JsonArray(pair.map { it.index()?.let(::hydrate) ?: JsonNull }) })
                "null" -> JsonObject(rest.chunked(2).filter { it.size == 2 }.mapNotNull { (key, at) -> (key as? JsonPrimitive)?.content?.let { it to (at.index()?.let(::hydrate) ?: JsonNull) } }.toMap())
                else -> JsonNull
            }
        }

        private fun JsonElement.index(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    }

    private const val NEGATIVE_ZERO = -6
}
