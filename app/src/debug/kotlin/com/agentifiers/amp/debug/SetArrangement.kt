package com.agentifiers.amp.debug

import com.agentifiers.amp.layout.Arrangement
import org.json.JSONObject

/**
 * `set-arrangement {name}`, one of layout.md's arrangements, any case. The window in front applies it as the
 * window bar would, mapped to its window class; a Compact window ignores Focus.
 */
object SetArrangement : AppEventHandler {
    override val name = "set-arrangement"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val requested = payload.requireString("name")
        val arrangement = Arrangement.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) }
            ?: throw BadPayload("unknown arrangement \"$requested\"; known: ${Arrangement.entries.joinToString()}")
        app.overrides.arrangement.value = arrangement
        return "requested $arrangement"
    }
}
