package com.agentifiers.amp.debug

import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.terminal.TerminalTabNumber
import org.json.JSONObject

/** `terminal-type {threadId, tab?, text}`: sends `text` to the tab's PTY as typed input. Logs the length, never the text. */
object TerminalType : AppEventHandler {
    override val name = "terminal-type"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val target = TerminalTarget.parse(payload)
        val text = payload.requireString("text")
        target.scope(app).thread(target.thread).machine.terminal(target.tab.key).text(text)
        return "typed ${text.length} chars into ${target.thread.raw} tab ${target.tab.value}"
    }
}

/** The `{threadId, tab?}` part of the terminal events; tab is 1 to 5 and defaults to 1. */
internal class TerminalTarget(val thread: ThreadId, val tab: TerminalTabNumber) {
    /** The account the window shows, chosen as AmpRoot chooses it. */
    fun scope(app: AppEventContext): AccountScope {
        val roster = app.engines.roster.value as? Loadable.Ready ?: throw BadPayload("the account roster has not loaded")
        val account = roster.value.accounts.firstOrNull { it.auth == AuthHealth.Ok || it.auth == AuthHealth.Refreshing }
            ?: throw BadPayload("no account is signed in")
        return app.engines.scope(account.id)
    }

    companion object {
        fun parse(payload: JSONObject): TerminalTarget {
            val raw = payload.requireString("threadId")
            val thread = ThreadId.parse(raw) ?: throw BadPayload("\"$raw\" is not a thread id")
            val number = if (payload.has("tab")) payload.optInt("tab", 0) else 1
            val tab = TerminalTabNumber.of(number) ?: throw BadPayload("tab must be 1 to ${TerminalTabNumber.MAX}")
            return TerminalTarget(thread, tab)
        }
    }
}
