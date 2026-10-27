package com.agentifiers.amp.window

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.agentifiers.amp.layout.Arrangement
import com.agentifiers.amp.layout.SlotRole
import com.agentifiers.amp.layout.WidthClass
import com.agentifiers.amp.layout.Workspace
import com.agentifiers.amp.layout.fold
import com.agentifiers.amp.layout.machineTabOf
import com.agentifiers.amp.layout.parseRoute
import com.agentifiers.amp.layout.unfold
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.store.WorkspaceEntry
import org.json.JSONArray
import org.json.JSONObject

/** The cover and the inner display keep separate workspaces (layout.md); a window's width class picks one. */
enum class WindowForm(val key: String) {
    Compact("compact"), Wide("wide");

    companion object {
        fun of(widthClass: WidthClass): WindowForm = if (widthClass == WidthClass.Compact) Compact else Wide
    }
}

/** What a New Window intent asks the new window to show first. [alone] shows the pane by itself, in Focus. */
data class WindowOpening(val dest: PaneDestination, val alone: Boolean)

/**
 * One window's layout state. It lives in the activity's ViewModel, so it outlives configuration changes. Each account
 * the window shows keeps its own [AccountWorkspaces], because a workspace names that account's threads
 * (auth-and-security.md, "Each window shows one account at a time").
 */
class WindowState(val windowKey: String, opening: WindowOpening?) {
    var opening: WindowOpening? by mutableStateOf(opening)
    var paletteOpen by mutableStateOf(false)
    var textScale by mutableFloatStateOf(1f)

    /** Set by the shell while it is composed; MainActivity offers it every key before the views see it. */
    var keys: ((KeyEvent) -> Boolean)? = null

    private val accounts = HashMap<String, AccountWorkspaces>()

    fun of(accountId: String): AccountWorkspaces = accounts.getOrPut(accountId) { AccountWorkspaces(windowKey, accountId) }

    /** Drops the workspaces of accounts that left the roster, so signing in again starts fresh. */
    fun retain(accountIds: Set<String>) {
        accounts.keys.retainAll(accountIds)
    }
}

/** One account's workspaces in one window, saved to `workspaces.pb` so the window restores them after the process dies. */
class AccountWorkspaces(private val windowKey: String, private val accountId: String) {
    var wide by mutableStateOf(Workspace.Wide)
        private set
    var compact by mutableStateOf(Workspace.Compact)
        private set
    private var form: WindowForm? = null

    /** Restored from disk at most once, and never over a change the user already made. */
    private var restoreDone = false

    fun workspace(form: WindowForm): Workspace = if (form == WindowForm.Compact) compact else wide

    fun update(form: WindowForm, change: (Workspace) -> Workspace) {
        restoreDone = true
        if (form == WindowForm.Compact) compact = change(compact) else wide = change(wide)
    }

    /** Folding and unfolding carry the thread and the focused pane across (layout.md "Folding and unfolding"). */
    fun enter(next: WindowForm) {
        when {
            form == WindowForm.Wide && next == WindowForm.Compact -> compact = fold(wide, compact)
            form == WindowForm.Compact && next == WindowForm.Wide -> wide = unfold(wide, compact)
        }
        form = next
    }

    fun restore(saved: List<WorkspaceEntry>) {
        if (restoreDone) return
        restoreDone = true
        saved.firstOrNull { it.windowKey == entryKey(WindowForm.Wide) && it.accountId == accountId }?.let { wide = decode(it) }
        saved.firstOrNull { it.windowKey == entryKey(WindowForm.Compact) && it.accountId == accountId }?.let { compact = decode(it) }
    }

    fun entry(form: WindowForm): WorkspaceEntry = encode(workspace(form), entryKey(form), accountId)

    private fun entryKey(form: WindowForm): String = "$windowKey/${form.key}"
}

class WindowModel : ViewModel() {
    var state: WindowState? = null
}

private fun encode(workspace: Workspace, key: String, accountId: String): WorkspaceEntry {
    val stacks = JSONObject()
    workspace.pushed.filterValues { it.isNotEmpty() }.forEach { (role, routes) -> stacks.put(role.name, JSONArray(routes.map { it.route })) }
    return WorkspaceEntry(
        windowKey = key,
        accountId = accountId,
        arrangement = workspace.arrangement.name,
        stacksJson = stacks.toString(),
        dividerOffsetsDp = workspace.dividersDp,
        restore = workspace.restore?.name,
        focus = workspace.focus.name,
        thread = workspace.thread?.raw,
        machineTab = workspace.machineTab.key,
        secondTab = workspace.secondTab.key,
    )
}

/** Saved entries are external data: unknown names fall back to the defaults instead of failing the window. */
private fun decode(entry: WorkspaceEntry): Workspace {
    val defaults = Workspace()
    val stacks = runCatching { JSONObject(entry.stacksJson) }.getOrNull()
    val pushed = SlotRole.entries.associateWith { role ->
        val routes = stacks?.optJSONArray(role.name) ?: JSONArray()
        List(routes.length()) { routes.optString(it) }.mapNotNull(::parseRoute)
    }.filterValues { it.isNotEmpty() }
    return Workspace(
        arrangement = enumOrNull<Arrangement>(entry.arrangement) ?: defaults.arrangement,
        restore = entry.restore?.let { enumOrNull<Arrangement>(it) },
        focus = enumOrNull<SlotRole>(entry.focus) ?: defaults.focus,
        thread = entry.thread?.let(ThreadId::parse),
        machineTab = machineTabOf(entry.machineTab) ?: defaults.machineTab,
        secondTab = machineTabOf(entry.secondTab) ?: defaults.secondTab,
        pushed = pushed,
        dividersDp = entry.dividerOffsetsDp,
    )
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
