package com.agentifiers.amp.debug

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.sync.api.Loadable
import org.json.JSONObject

/**
 * `run-plugin-command {threadId, plugin, commandId}`: dispatches ThreadIntent.RunPluginCommand on the signed-in
 * account's thread, as the command palette does, so flows reach plugin dialogs without the palette.
 */
object RunPluginCommand : AppEventHandler {
    override val name = "run-plugin-command"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val raw = payload.requireString("threadId")
        val thread = ThreadId.parse(raw) ?: throw BadPayload("\"$raw\" is not a thread id")
        val plugin = payload.requireString("plugin")
        val command = payload.requireString("commandId")
        val roster = (app.engines.roster.value as? Loadable.Ready)?.value ?: return "refused: no account roster yet"
        val account = roster.lastUsed ?: roster.accounts.firstOrNull()?.id ?: return "refused: no account is signed in"
        val intent = app.engines.scope(account).thread(thread).dispatch(ThreadIntent.RunPluginCommand(plugin, command))
        return "dispatched $plugin/$command on ${thread.raw} as ${intent.raw}"
    }
}
