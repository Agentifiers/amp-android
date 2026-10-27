package com.agentifiers.amp.sync.internal.portals

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.McpApps
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The web app's MCP App bridge (ampcode.com's web bundle, `Ka`): it sends `toolName` only when it has one,
 * reads `result.text` as the page, and hands `result` of a tool call to the page as is. Neither call names the thread.
 */
internal class McpAppsEngine(private val http: AmpHttp) : McpApps {
    override suspend fun readResource(thread: ThreadId, serverId: String, uri: String, toolName: String?): Outcome<String> {
        val params = buildJsonObject {
            put("serverID", serverId)
            put("uri", uri)
            toolName?.let { put("toolName", it) }
        }
        return http.rpc("mcpAppReadResource", params) { html(it) }
    }

    override suspend fun callTool(thread: ThreadId, serverId: String, name: String, argumentsJson: String): Outcome<String> {
        val arguments = jsonObject(argumentsJson) ?: return Outcome.Refused("The page sent tool arguments that are not a JSON object.")
        val params = buildJsonObject {
            put("serverID", serverId)
            put("name", name)
            put("arguments", arguments)
        }
        return http.rpc("mcpAppCallTool", params) { result -> result.takeUnless { it is JsonNull }?.toString() }
    }

    private companion object {
        fun jsonObject(text: String): JsonObject? = try {
            AmpJson.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

        /** `result.text` as the web app reads it, or the MCP `contents[0].text` and `html` shapes. */
        fun html(result: JsonElement): String? {
            val resource = result as? JsonObject ?: return null
            return resource.string("text")
                ?: (resource.array("contents")?.firstOrNull() as? JsonObject)?.string("text")
                ?: resource.string("html")
        }
    }
}
