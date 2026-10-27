package com.agentifiers.amp.debug

import com.agentifiers.amp.sync.api.DebugSocket
import com.agentifiers.amp.sync.api.dropConnections
import org.json.JSONObject

/** `drop-connection {target}` with target `thread` or `user`: closes those sockets so reconnect and resume run. */
object DropConnection : AppEventHandler {
    override val name = "drop-connection"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val target = when (val raw = payload.requireString("target")) {
            "thread" -> DebugSocket.Thread
            "user" -> DebugSocket.User
            else -> throw BadPayload("target must be thread or user, not \"$raw\"")
        }
        return "dropped ${app.engines.dropConnections(target)} ${target.name.lowercase()} socket(s)"
    }
}
