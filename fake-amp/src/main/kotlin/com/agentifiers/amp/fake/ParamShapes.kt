package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The thread actor's params schemas for the methods the fake checks. A request whose params fail its schema gets
 * `-32602 Invalid method params`, as Amp answers it (`spikes/protocol/captures/s20-changes-params.jsonl:1017`).
 * Like zod objects, unknown keys pass.
 */
internal object ParamShapes {
    private val SCHEMAS: Map<String, (JsonObject) -> Boolean> = mapOf(
        "client_changes_file_order" to ::fileOrder,
    )

    fun accepts(method: String, params: JsonObject): Boolean = SCHEMAS[method]?.invoke(params) ?: true

    private val CHANGE_TYPES = setOf("added", "modified", "deleted", "renamed", "copied", "type_changed", "unmerged", "untracked")

    /** `client_changes_file_order` per the web bundle's `Ln`/`In` and the CLI's `IEi`/`TEi` (2026-10-26). */
    private fun fileOrder(params: JsonObject): Boolean {
        if (params.text("requestId", 1..Int.MAX_VALUE) == null) return false
        val sections = params["sections"] as? JsonArray ?: return false
        return sections.size in 1..42 && sections.all { (it as? JsonObject)?.let(::orderSection) == true }
    }

    private fun orderSection(section: JsonObject): Boolean {
        val files = section["files"]
        return section.text("sectionID", 1..128) != null &&
            section.text("diffHash", 1..256) != null &&
            (section["repositoryRoot"] == null || section.text("repositoryRoot", 1..Int.MAX_VALUE) != null) &&
            section.text("baseRevision", 1..Int.MAX_VALUE) != null &&
            section.text("headRevision", 1..Int.MAX_VALUE) != null &&
            (section["includeUntracked"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null &&
            (files == null || files is JsonArray && files.size <= 500 && files.all { (it as? JsonObject)?.let(::orderFile) == true })
    }

    private fun orderFile(file: JsonObject): Boolean =
        file.text("path", 1..Int.MAX_VALUE) != null && file.string("changeType") in CHANGE_TYPES && file.text("diff", 0..20_000) != null

    private fun JsonObject.text(key: String, length: IntRange): String? = string(key)?.takeIf { it.length in length }
}
