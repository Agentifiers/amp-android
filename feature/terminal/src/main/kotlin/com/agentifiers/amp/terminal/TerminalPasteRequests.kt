package com.agentifiers.amp.terminal

import com.agentifiers.amp.model.ThreadId

/**
 * Pastes from outside the pane (the debug `terminal-paste` event) run the visible pane's paste path, multi-line
 * dialog included. A pane registers while it is composed; when several windows show the same tab, the most recently
 * registered one gets the paste.
 */
object TerminalPasteRequests {
    private data class Target(val thread: ThreadId, val tab: TerminalTabNumber)

    private val panes = HashMap<Target, ArrayDeque<(String) -> Unit>>()

    /** False when no pane shows that tab, so nothing was pasted. */
    fun paste(thread: ThreadId, tab: TerminalTabNumber, text: String): Boolean {
        val pane = synchronized(panes) { panes[Target(thread, tab)]?.lastOrNull() } ?: return false
        pane(text)
        return true
    }

    internal fun register(thread: ThreadId, tab: TerminalTabNumber, paste: (String) -> Unit): AutoCloseable {
        val target = Target(thread, tab)
        synchronized(panes) { panes.getOrPut(target, ::ArrayDeque).addLast(paste) }
        return AutoCloseable {
            synchronized(panes) {
                val stack = panes[target] ?: return@synchronized
                stack.remove(paste)
                if (stack.isEmpty()) panes.remove(target)
            }
        }
    }
}
