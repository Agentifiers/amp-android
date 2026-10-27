package com.agentifiers.amp.debug

import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import org.json.JSONObject

/** `open-thread {threadId, pane?}`: opens a thread, optionally on a machine tab (changes, files, terminal, portal, space, desktop). */
object OpenThread : AppEventHandler {
    override val name = "open-thread"

    private val tabs: Map<String, MachineTab> = mapOf(
        "changes" to MachineTab.Changes(focusPath = null),
        "files" to MachineTab.Files(path = null),
        "terminal" to MachineTab.Terminal(session = null),
        "portal" to MachineTab.Portal(port = null),
        "space" to MachineTab.Space,
        "desktop" to MachineTab.Desktop(DesktopTarget.Orb),
    )

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val raw = payload.requireString("threadId")
        val thread = ThreadId.parse(raw) ?: throw BadPayload("\"$raw\" is not a thread id")
        val pane = payload.optString("pane").takeIf { it.isNotEmpty() }
        val dest = if (pane == null) {
            PaneDestination.Thread(thread)
        } else {
            val tab = tabs[pane] ?: throw BadPayload("unknown pane \"$pane\"; known: ${tabs.keys.joinToString()}")
            PaneDestination.Machine(thread, tab)
        }
        app.navigator.open(dest)
        return "opening ${dest.route}"
    }
}
