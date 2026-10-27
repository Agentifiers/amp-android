package com.agentifiers.amp.layout

import com.agentifiers.amp.model.DesktopTarget
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.SettingsPage
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadQuery

/** The arrangements of docs/design/layout.md, in shortcut order (Ctrl+Shift+1 to Ctrl+Shift+6). */
enum class Arrangement { Triage, Review, Terminal, Read, Puck, Focus }

/** What a column holds. Each role shows its base pane, or the destination pushed on top of it. */
enum class SlotRole { Nav, Thread, Machine, SecondMachine, Puck }

/** A destination pushed over a role's base pane, kept so Forward can put it back after Back. */
data class Pushed(val role: SlotRole, val dest: PaneDestination)

/**
 * One window's layout state for one window form (the cover and the inner display keep separate workspaces). The
 * base pane of each role derives from [thread] and the tabs, so a new thread never leaves a machine pane on the
 * old one. [pushed] holds the destinations stacked over a base pane; Back pops them. [restore] is where Collapse
 * and Exit Focus return. [dividersDp] are user-dragged divider positions for the current arrangement.
 */
data class Workspace(
    val arrangement: Arrangement = Arrangement.Triage,
    val restore: Arrangement? = null,
    val focus: SlotRole = SlotRole.Thread,
    val thread: ThreadId? = null,
    val machineTab: MachineTab = MachineTab.Changes(focusPath = null),
    val secondTab: MachineTab = MachineTab.Terminal(session = null),
    val pushed: Map<SlotRole, List<PaneDestination>> = emptyMap(),
    val forward: List<Pushed> = emptyList(),
    val dividersDp: List<Float> = emptyList(),
) {
    /** What [role]'s column shows; null is the role's empty state (no thread open yet). */
    fun top(role: SlotRole): PaneDestination? = pushed[role]?.lastOrNull() ?: base(role)

    fun base(role: SlotRole): PaneDestination? = when (role) {
        SlotRole.Nav -> PaneDestination.Sidebar
        SlotRole.Thread -> thread?.let(PaneDestination::Thread)
        SlotRole.Machine -> thread?.let { PaneDestination.Machine(it, machineTab) }
        SlotRole.SecondMachine -> thread?.let { PaneDestination.Machine(it, secondTab) }
        SlotRole.Puck -> PaneDestination.Puck(conversation = null)
    }

    fun tabOf(role: SlotRole): MachineTab = if (role == SlotRole.SecondMachine) secondTab else machineTab

    /** Every thread a visible column references, for viewer leases. */
    fun threadsIn(roles: Collection<SlotRole>): Set<ThreadId> = roles.mapNotNullTo(mutableSetOf()) { role ->
        when (val dest = top(role)) {
            is PaneDestination.Thread -> dest.id
            is PaneDestination.Machine -> dest.thread
            is PaneDestination.Puck -> dest.conversation?.thread
            else -> null
        }
    }

    companion object {
        /** A Compact window starts on the thread list, a wider one on the arrangement's columns. */
        val Compact = Workspace(focus = SlotRole.Nav)
        val Wide = Workspace()
    }
}

/**
 * Picks [arrangement] from the window bar, a shortcut, or the palette. On one-pane windows (Compact, Medium under
 * 720dp) an arrangement selects the pane it maps to in layout.md's Compact column, and Focus is not offered.
 */
fun Workspace.arrange(arrangement: Arrangement, onePane: Boolean): Workspace {
    if (arrangement == Arrangement.Focus) {
        if (onePane || this.arrangement == Arrangement.Focus) return this
        return copy(arrangement = Arrangement.Focus, restore = this.arrangement, dividersDp = emptyList())
    }
    val next = copy(arrangement = arrangement, restore = null, dividersDp = emptyList())
    return when (arrangement) {
        Arrangement.Review -> next.copy(focus = SlotRole.Machine, machineTab = machineTab.asChanges())
        Arrangement.Terminal -> next.copy(focus = SlotRole.Machine, machineTab = machineTab.asTerminal())
        Arrangement.Puck -> if (onePane) next.copy(focus = SlotRole.Puck) else next
        Arrangement.Triage, Arrangement.Read -> if (onePane) next.copy(focus = SlotRole.Thread) else next
        Arrangement.Focus -> error("handled above")
    }
}

/**
 * Expand on a machine pane (layout.md "Arrangements"): the two-panel arrangement for its tab, Terminal for the
 * terminal and Review for every other tab, which keeps its own tab selected.
 */
fun Workspace.expand(): Workspace {
    if (arrangement == Arrangement.Review || arrangement == Arrangement.Terminal) return this
    val target = if (machineTab is MachineTab.Terminal) Arrangement.Terminal else Arrangement.Review
    return copy(arrangement = target, restore = arrangement, focus = SlotRole.Machine, dividersDp = emptyList())
}

/** Collapse and Exit Focus: back to the arrangement the window used before, or Triage. */
fun Workspace.restored(): Workspace = copy(arrangement = restore ?: Arrangement.Triage, restore = null, dividersDp = emptyList())

/** Ctrl+Shift+E: Expand from a one-panel machine pane, Collapse from a two-panel one. */
fun Workspace.toggleExpand(): Workspace =
    if (arrangement == Arrangement.Review || arrangement == Arrangement.Terminal) restored() else expand()

/**
 * Where a destination goes (deep link, notification, event, tap). [from] is the column that asked, so a machine
 * pane's own tab strip changes that pane. [plan] says which roles the window can show.
 */
fun Workspace.place(dest: PaneDestination, plan: LayoutPlan, from: SlotRole? = null): Workspace {
    val preferred = when (dest) {
        PaneDestination.Sidebar, is PaneDestination.Activity, PaneDestination.Running -> SlotRole.Nav
        is PaneDestination.Settings -> if (plan.onePane) SlotRole.Nav else SlotRole.Thread
        is PaneDestination.Thread, is PaneDestination.NewThread -> SlotRole.Thread
        is PaneDestination.Machine -> if (from == SlotRole.SecondMachine) SlotRole.SecondMachine else SlotRole.Machine
        is PaneDestination.Puck -> SlotRole.Puck
        is PaneDestination.Web -> SlotRole.Machine
    }
    val shown = plan.reachable
    val arranged = when {
        preferred in shown -> this
        dest is PaneDestination.Thread && (arrangement == Arrangement.Review || arrangement == Arrangement.Terminal) -> restored()
        dest is PaneDestination.Puck -> copy(arrangement = Arrangement.Puck, restore = arrangement, dividersDp = emptyList())
        else -> this
    }
    val role = when {
        preferred in shown || arranged !== this || dest is PaneDestination.Machine -> preferred
        else -> listOf(SlotRole.Thread, SlotRole.Machine).firstOrNull { it in shown } ?: plan.panes.first()
    }
    val selected = when (dest) {
        is PaneDestination.Thread -> arranged.copy(thread = dest.id)
        is PaneDestination.Machine ->
            if (role == SlotRole.SecondMachine) arranged.copy(thread = dest.thread, secondTab = dest.tab)
            else arranged.copy(thread = dest.thread, machineTab = dest.tab)
        else -> arranged
    }
    val stack = selected.pushed[role].orEmpty()
    val nextStack = when (dest) {
        selected.base(role) -> emptyList()
        stack.lastOrNull() -> stack
        else -> stack.filterNot { it.route == dest.route } + dest
    }
    val visible = role in shown || arranged !== this
    return selected.copy(
        pushed = selected.pushed + (role to nextStack),
        forward = emptyList(),
        focus = if (visible) role else focus,
    )
}

/**
 * Back (Ctrl+[ and system back): pops the focused column's pushed destination. On a Compact window with nothing
 * to pop, a pane goes back to the thread list. Null means the window has nothing to go back to.
 */
fun Workspace.back(compact: Boolean): Workspace? {
    val stack = pushed[focus].orEmpty()
    if (stack.isNotEmpty()) {
        return copy(pushed = pushed + (focus to stack.dropLast(1)), forward = forward + Pushed(focus, stack.last()))
    }
    return if (compact && focus != SlotRole.Nav) copy(focus = SlotRole.Nav) else null
}

/** Close on Settings: takes Settings and what it opened off every column, so each shows what it showed before. */
fun Workspace.closeSettings(): Workspace = copy(
    pushed = pushed.mapValues { (_, stack) -> stack.takeWhile { it !is PaneDestination.Settings } },
    forward = emptyList(),
)

/** Forward (Ctrl+]): puts back what Back popped last. */
fun Workspace.goForward(): Workspace? {
    val last = forward.lastOrNull() ?: return null
    return copy(pushed = pushed + (last.role to pushed[last.role].orEmpty() + last.dest), forward = forward.dropLast(1), focus = last.role)
}

/** Ctrl+/: opens Puck, or closes it back to where the window was. */
fun Workspace.togglePuck(plan: LayoutPlan): Workspace = when {
    plan.onePane && focus == SlotRole.Puck -> copy(focus = SlotRole.Thread)
    plan.onePane -> copy(focus = SlotRole.Puck)
    arrangement == Arrangement.Puck -> restored()
    else -> place(PaneDestination.Puck(conversation = null), plan)
}

/** Ctrl+Shift+Left and Right: moves focus to the previous or next pane column, wrapping. */
fun Workspace.cycleFocus(plan: LayoutPlan, step: Int): Workspace {
    val panes = plan.panes
    if (panes.size < 2) return this
    val at = panes.indexOf(focus).takeIf { it >= 0 } ?: 0
    return copy(focus = panes[Math.floorMod(at + step, panes.size)])
}

/** Alt+1 to Alt+6: selects a machine tab in the focused machine column, or the main one. */
fun Workspace.selectTab(tab: MachineTab, plan: LayoutPlan): Workspace {
    val role = if (focus == SlotRole.SecondMachine) SlotRole.SecondMachine else SlotRole.Machine
    val thread = thread ?: return this
    return place(PaneDestination.Machine(thread, tab), plan, from = role)
}

/** Keeps focus on a pane [plan] shows, preferring the thread, after an arrangement change hides the focused one. */
fun Workspace.focusShown(plan: LayoutPlan): Workspace = when {
    focus in plan.reachable -> this
    SlotRole.Thread in plan.reachable -> copy(focus = SlotRole.Thread)
    else -> copy(focus = plan.panes.first())
}

/**
 * A thread dropped on a column (layout.md "Multiple windows", drag and drop): a machine column opens that thread's
 * machine pane on its own tab, any other column opens the thread.
 */
fun Workspace.drop(thread: ThreadId, onto: SlotRole, plan: LayoutPlan): Workspace = when (onto) {
    SlotRole.Machine, SlotRole.SecondMachine -> place(PaneDestination.Machine(thread, tabOf(onto)), plan, from = onto)
    SlotRole.Nav, SlotRole.Thread, SlotRole.Puck -> place(PaneDestination.Thread(thread), plan)
}

private val THREAD_ID_IN_TEXT = Regex("T-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** The first thread id in dropped text: a bare id, or a thread URL such as `https://ampcode.com/threads/T-…`. */
fun threadIn(text: CharSequence): ThreadId? = THREAD_ID_IN_TEXT.find(text)?.value?.let(ThreadId::parse)

/**
 * The Compact bottom bar and the Medium title tabs (layout.md "Layout by width class"). Terminal has its own item, so
 * Machine is every other machine tab: it opens the one already showing, or Changes when that was the Terminal.
 */
enum class NavItem { Threads, Thread, Machine, Terminal }

fun Workspace.select(item: NavItem): Workspace = when (item) {
    NavItem.Threads -> copy(focus = SlotRole.Nav)
    NavItem.Thread -> copy(focus = SlotRole.Thread)
    NavItem.Machine -> copy(focus = SlotRole.Machine, machineTab = machineTab.takeIf { it !is MachineTab.Terminal } ?: MachineTab.Changes(focusPath = null))
    NavItem.Terminal -> copy(focus = SlotRole.Machine, machineTab = machineTab.asTerminal())
}

fun Workspace.selected(): NavItem? = when (focus) {
    SlotRole.Nav -> NavItem.Threads
    SlotRole.Thread -> NavItem.Thread
    SlotRole.Machine, SlotRole.SecondMachine -> if (tabOf(focus) is MachineTab.Terminal) NavItem.Terminal else NavItem.Machine
    SlotRole.Puck -> null
}

/**
 * Folding (layout.md "Folding and unfolding keep your place"): the cover shows the destination of the pane that had
 * focus on the inner display, with the same thread.
 */
fun fold(inner: Workspace, cover: Workspace): Workspace {
    if (inner.thread == null) return cover
    val role = if (inner.focus == SlotRole.SecondMachine) SlotRole.Machine else inner.focus
    return cover.copy(thread = inner.thread, focus = role, machineTab = inner.tabOf(inner.focus), forward = emptyList())
}

/**
 * Unfolding restores the inner workspace with the cover's thread, and a machine tab that had focus on the cover
 * grows into its two-panel arrangement, as Expand would.
 */
fun unfold(inner: Workspace, cover: Workspace): Workspace {
    val withThread = inner.copy(thread = cover.thread ?: inner.thread, forward = emptyList())
    return if (cover.focus == SlotRole.Machine && cover.thread != null) withThread.copy(machineTab = cover.machineTab).expand() else withThread
}

private fun MachineTab.asChanges(): MachineTab = this as? MachineTab.Changes ?: MachineTab.Changes(focusPath = null)

private fun MachineTab.asTerminal(): MachineTab = this as? MachineTab.Terminal ?: MachineTab.Terminal(session = null)

/** The machine tab a route segment names, as a tab opens fresh. Desktop needs its target, so only `desktop` carries the orb. */
fun machineTabOf(key: String): MachineTab? = when (key) {
    "changes" -> MachineTab.Changes(focusPath = null)
    "files" -> MachineTab.Files(path = null)
    "terminal" -> MachineTab.Terminal(session = null)
    "portal" -> MachineTab.Portal(port = null)
    "desktop" -> MachineTab.Desktop(DesktopTarget.Orb)
    "space" -> MachineTab.Space
    else -> null
}

/**
 * Reads a saved [PaneDestination.route] back. Routes keep only the parts a window restores: an Activity query,
 * a Draft and web surfaces are not in the route, so Activity reopens unfiltered and New Thread empty, and web
 * surfaces are dropped.
 */
fun parseRoute(route: String): PaneDestination? {
    val parts = route.split('/')
    return when (parts.first()) {
        "sidebar" -> PaneDestination.Sidebar
        "activity" -> PaneDestination.Activity(ThreadQuery.parse(""))
        "running" -> PaneDestination.Running
        "thread" -> parts.getOrNull(1)?.let(ThreadId::parse)?.let(PaneDestination::Thread)
        "machine" -> {
            val thread = parts.getOrNull(1)?.let(ThreadId::parse)
            val tab = parts.getOrNull(2)?.let(::machineTabOf)
            if (thread != null && tab != null) PaneDestination.Machine(thread, tab) else null
        }
        "puck" -> PaneDestination.Puck(conversation = null)
        "new" -> PaneDestination.NewThread(prefill = null)
        "settings" -> parts.getOrNull(1)?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }?.let(PaneDestination::Settings)
        else -> null
    }
}
