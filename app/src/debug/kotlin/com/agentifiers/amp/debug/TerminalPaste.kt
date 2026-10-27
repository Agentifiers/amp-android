package com.agentifiers.amp.debug

import com.agentifiers.amp.terminal.TerminalPasteRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `terminal-paste {threadId, tab?, text}`: runs `text` through the visible pane's paste path, bracketed paste and the
 * multi-line dialog included. agent-device has no Android clipboard command. Logs the length, never the text.
 */
object TerminalPaste : AppEventHandler {
    override val name = "terminal-paste"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val target = TerminalTarget.parse(payload)
        val text = payload.requireString("text")
        val pasted = withContext(Dispatchers.Main) { TerminalPasteRequests.paste(target.thread, target.tab, text) }
        val where = "${target.thread.raw} tab ${target.tab.value}"
        return if (pasted) "ran the paste path with ${text.length} chars on $where" else "rejected: no visible pane shows $where"
    }
}
