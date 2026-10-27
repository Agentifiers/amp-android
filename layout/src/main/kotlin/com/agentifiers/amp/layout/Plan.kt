package com.agentifiers.amp.layout

import com.agentifiers.amp.model.PaneDestination
import kotlin.math.abs

/** One column of the window in window dp. A [rail] column is the 72dp collapsed Sidebar. */
data class Column(val role: SlotRole, val startDp: Float, val widthDp: Float, val rail: Boolean = false) {
    val endDp: Float get() = startDp + widthDp
}

/**
 * A draggable boundary between two pane columns. [index] is its slot in [Workspace.dividersDp]; a release snaps
 * inside ([lowerDp], [upperDp]), the outer edges of the two columns it separates.
 */
data class Divider(val index: Int, val xDp: Float, val lowerDp: Float, val upperDp: Float)

/** The window bar (layout.md): the arrangements this window offers, the selected one, and Exit Focus in Focus. */
data class WindowBar(val arrangements: List<Arrangement>, val selected: Arrangement, val exitFocus: Boolean)

/** How a one-pane window switches panes: a bottom bar on Compact, tabs over the pane on Medium under 720dp. */
sealed interface Switcher {
    val items: List<NavItem>

    data class BottomBar(override val items: List<NavItem>) : Switcher
    data class TitleTabs(override val items: List<NavItem>) : Switcher
}

data class LayoutPlan(
    val widthClass: WidthClass,
    val arrangement: Arrangement,
    /** Medium and wider; null on Compact. */
    val windowBar: WindowBar?,
    val columns: List<Column>,
    val dividers: List<Divider>,
    val switcher: Switcher?,
    /** The creases pane edges sit on; empty when the window lays out by width class alone. */
    val creasesDp: List<Float>,
) {
    /** Pane columns in order, without the rail. */
    val panes: List<SlotRole> get() = columns.filterNot { it.rail }.map { it.role }

    /** Shows one pane at a time with a switcher (Compact, Medium under 720dp); focusing a role shows it. */
    val onePane: Boolean get() = switcher != null

    /** Roles the window can show now. Focus shows whichever role has focus, as a one-pane window does. */
    val reachable: Set<SlotRole> get() = if (onePane || arrangement == Arrangement.Focus) SlotRole.entries.toSet() else panes.toSet()

    /** Positions for [Workspace.dividersDp] after divider [index] is released at [dropDp]. */
    fun release(index: Int, dropDp: Float): List<Float> {
        val divider = dividers[index]
        val snapped = snap(dropDp, divider.lowerDp, divider.upperDp, creasesDp)
        return dividers.map { if (it.index == index) snapped else it.xDp }
    }

    fun describe(): String =
        "$widthClass $arrangement · " + columns.joinToString(" | ") { "${if (it.rail) "Rail" else it.role.name} ${it.startDp.fmt()}+${it.widthDp.fmt()}" } +
            (if (creasesDp.isEmpty()) "" else " · creases ${creasesDp.joinToString(",") { it.fmt() }}")
}

/** A machine pane's window controls, in the order its header offers them. */
enum class PaneControl { Expand, Collapse, Focus, NewWindow }

/** Expand or Collapse and Focus need a window that shows more than one pane; New Window works everywhere. */
val LayoutPlan.paneControls: List<PaneControl>
    get() = buildList {
        if (!onePane && arrangement != Arrangement.Focus) {
            add(if (arrangement == Arrangement.Review || arrangement == Arrangement.Terminal) PaneControl.Collapse else PaneControl.Expand)
            add(PaneControl.Focus)
        }
        add(PaneControl.NewWindow)
    }

/**
 * Collapse on the Sidebar frees a panel only in three-panel Triage, where it turns the Sidebar into the rail beside
 * the Thread and gives the machine pane two panels, as Expand does. Everywhere else the Sidebar's column would keep
 * its width, so the Sidebar offers no Collapse.
 */
val LayoutPlan.sidebarCollapses: Boolean
    get() = creasesDp.isNotEmpty() && columns.map { it.role to it.rail } == listOf(SlotRole.Nav to false, SlotRole.Thread to false, SlotRole.Machine to false)

/** The rail of a two-panel machine arrangement on the panels offers Expand, back to the Sidebar. */
val LayoutPlan.sidebarExpands: Boolean
    get() = creasesDp.isNotEmpty() && columns.any { it.rail }

private fun Float.fmt(): String = if (this % 1f == 0f) toInt().toString() else toString()

const val RAIL_DP = 72f
const val MIN_PANE_DP = 280f
const val SNAP_THRESHOLD_DP = 48f
private const val MIN_PANEL_DP = 300f
private const val MEDIUM_COLUMNS_MIN_DP = 720f
private const val SIDEBAR_DP = 300f
private const val SIDE_PANE_DP = 380f
private const val EXPANDED_THREAD_DP = 360f

/**
 * Divider snap (layout.md "Dividers snap to creases"). With creases, a release goes to the nearest crease that
 * keeps both neighbors at least one panel wide, whatever the distance. Without creases, a release within 48dp of
 * the point that splits the two neighbors equally snaps to it; otherwise it stays, kept 280dp from either edge.
 */
fun snap(dropDp: Float, lowerDp: Float, upperDp: Float, creasesDp: List<Float>): Float {
    val inside = creasesDp.filter { it > lowerDp + 1f && it < upperDp - 1f }
    if (creasesDp.isNotEmpty()) return inside.minByOrNull { abs(it - dropDp) } ?: ((lowerDp + upperDp) / 2f)
    val mid = (lowerDp + upperDp) / 2f
    if (abs(dropDp - mid) <= SNAP_THRESHOLD_DP) return mid
    val min = lowerDp + MIN_PANE_DP
    val max = upperDp - MIN_PANE_DP
    return if (min > max) mid else dropDp.coerceIn(min, max)
}

private sealed interface Width {
    data object Rail : Width
    data class Fixed(val dp: Float) : Width
    data class Fill(val weight: Int = 1) : Width
    data class Panels(val count: Int) : Width
}

private data class Spec(val role: SlotRole, val width: Width)

private fun rail() = Spec(SlotRole.Nav, Width.Rail)
private fun SlotRole.panels(count: Int) = Spec(this, Width.Panels(count))
private fun SlotRole.fill(weight: Int = 1) = Spec(this, Width.Fill(weight))
private fun SlotRole.fixed(dp: Float) = Spec(this, Width.Fixed(dp))

/**
 * The columns of each arrangement per window class (layout.md "Arrangements by window class"). Focus and the
 * one-pane classes are planned in [planLayout] directly.
 */
private fun specs(arrangement: Arrangement, mode: Mode): List<Spec> {
    val nav = SlotRole.Nav
    val thread = SlotRole.Thread
    val machine = SlotRole.Machine
    val puck = SlotRole.Puck
    return when (mode) {
        Mode.Panels -> when (arrangement) {
            Arrangement.Triage -> listOf(nav.panels(1), thread.panels(1), machine.panels(1))
            Arrangement.Review, Arrangement.Terminal -> listOf(rail(), thread.panels(1), machine.panels(2))
            Arrangement.Read -> listOf(nav.panels(1), thread.panels(2))
            Arrangement.Puck -> listOf(nav.panels(1), thread.panels(1), puck.panels(1))
            Arrangement.Focus -> error("Focus has one column")
        }
        Mode.MediumColumns -> when (arrangement) {
            Arrangement.Triage -> listOf(rail(), thread.fill(), machine.fill())
            Arrangement.Review, Arrangement.Terminal -> listOf(rail(), machine.fill())
            Arrangement.Read -> listOf(rail(), thread.fill())
            Arrangement.Puck -> listOf(rail(), thread.fill(), puck.fill())
            Arrangement.Focus -> error("Focus has one column")
        }
        Mode.Expanded -> when (arrangement) {
            Arrangement.Triage -> listOf(rail(), thread.fill(), machine.fixed(SIDE_PANE_DP))
            Arrangement.Review, Arrangement.Terminal -> listOf(rail(), thread.fixed(EXPANDED_THREAD_DP), machine.fill())
            Arrangement.Read -> listOf(rail(), thread.fill())
            Arrangement.Puck -> listOf(rail(), thread.fill(), puck.fixed(SIDE_PANE_DP))
            Arrangement.Focus -> error("Focus has one column")
        }
        Mode.Large -> when (arrangement) {
            Arrangement.Triage -> listOf(nav.fixed(SIDEBAR_DP), thread.fill(), machine.fill(), SlotRole.SecondMachine.fill())
            Arrangement.Review, Arrangement.Terminal -> listOf(nav.fixed(SIDEBAR_DP), thread.fill(), machine.fill(2))
            Arrangement.Read -> listOf(nav.fixed(SIDEBAR_DP), thread.fill())
            Arrangement.Puck -> listOf(nav.fixed(SIDEBAR_DP), thread.fill(), puck.fill(), machine.fill())
            Arrangement.Focus -> error("Focus has one column")
        }
    }
}

private enum class Mode { Panels, MediumColumns, Expanded, Large }

private val ONE_PANE_ITEMS = listOf(NavItem.Threads, NavItem.Thread, NavItem.Machine, NavItem.Terminal)
private val TITLE_TABS = listOf(NavItem.Thread, NavItem.Machine, NavItem.Terminal)

/**
 * Lays [workspace] out in [geometry] (layout.md "Layout by width class" and "Arrangements by window class").
 * Compact windows show the focused pane with a bottom bar. Medium windows under 720dp show the rail and the
 * focused pane. Wider windows show the arrangement's columns, on the panels between two creases when the window
 * has them; user-dragged dividers are re-snapped to the current geometry.
 */
fun planLayout(geometry: WindowGeometry, workspace: Workspace): LayoutPlan {
    val width = geometry.widthDp
    val widthClass = geometry.widthClass
    if (widthClass == WidthClass.Compact) {
        return LayoutPlan(
            widthClass, workspace.arrangement, windowBar = null,
            columns = listOf(Column(workspace.focus, 0f, width)),
            dividers = emptyList(), switcher = Switcher.BottomBar(ONE_PANE_ITEMS), creasesDp = emptyList(),
        )
    }
    if (widthClass == WidthClass.Medium && width < MEDIUM_COLUMNS_MIN_DP) {
        return LayoutPlan(
            widthClass, workspace.arrangement,
            windowBar = WindowBar(Arrangement.entries - Arrangement.Focus, workspace.arrangement, exitFocus = false),
            columns = listOf(Column(SlotRole.Nav, 0f, RAIL_DP, rail = true), Column(workspace.focus, RAIL_DP, width - RAIL_DP)),
            dividers = emptyList(), switcher = Switcher.TitleTabs(TITLE_TABS), creasesDp = emptyList(),
        )
    }
    val bar = WindowBar(Arrangement.entries, workspace.arrangement, exitFocus = workspace.arrangement == Arrangement.Focus)
    if (workspace.arrangement == Arrangement.Focus) {
        return LayoutPlan(widthClass, Arrangement.Focus, bar, listOf(Column(workspace.focus, 0f, width)), emptyList(), null, emptyList())
    }
    val panelEdges = panelEdges(width, geometry.creases.positionsDp)
    val mode = when {
        panelEdges != null -> Mode.Panels
        widthClass == WidthClass.Medium -> Mode.MediumColumns
        widthClass == WidthClass.Expanded -> Mode.Expanded
        else -> Mode.Large
    }
    val creases = panelEdges?.let { it.subList(1, it.size - 1) }.orEmpty()
    // Settings needs room for its index beside a page, so it and the web pages it opens borrow Read's wide Thread column.
    val planned = if (workspace.pushed[SlotRole.Thread].orEmpty().any { it is PaneDestination.Settings }) Arrangement.Read else workspace.arrangement
    val columns = resolve(specs(planned, mode), width, panelEdges)
    val (placed, dividers) = applyDividers(columns, workspace.dividersDp, creases)
    return LayoutPlan(widthClass, workspace.arrangement, bar, placed, dividers, switcher = null, creasesDp = creases)
}

/** The panel edges between two creases, when the window spans them and every panel is wide enough to hold a pane. */
private fun panelEdges(width: Float, creases: List<Float>): List<Float>? {
    if (creases.size != 2) return null
    val edges = listOf(0f) + creases.sorted() + width
    return edges.takeIf { e -> e.zipWithNext().all { (start, end) -> end - start >= MIN_PANEL_DP } }
}

private fun resolve(specs: List<Spec>, width: Float, edges: List<Float>?): List<Column> {
    val fixed = specs.sumOf { spec ->
        when (val w = spec.width) {
            Width.Rail -> RAIL_DP.toDouble()
            is Width.Fixed -> w.dp.toDouble()
            else -> 0.0
        }
    }.toFloat()
    val weights = specs.sumOf { (it.width as? Width.Fill)?.weight ?: 0 }
    var x = 0f
    var panel = 0
    return specs.map { spec ->
        val w = when (val kind = spec.width) {
            Width.Rail -> RAIL_DP
            is Width.Fixed -> kind.dp
            is Width.Fill -> (width - fixed) * kind.weight / weights
            is Width.Panels -> {
                panel += kind.count
                requireNotNull(edges)[panel] - x
            }
        }
        Column(spec.role, x, w, rail = spec.width == Width.Rail).also { x += w }
    }
}

/** Places user-dragged dividers, snapped to this geometry, when the saved list matches this arrangement's dividers. */
private fun applyDividers(columns: List<Column>, saved: List<Float>, creases: List<Float>): Pair<List<Column>, List<Divider>> {
    val pairs = columns.indices.zipWithNext().filter { (a, b) -> !columns[a].rail && !columns[b].rail }
    val placed = columns.toMutableList()
    val dividers = pairs.mapIndexed { index, (a, b) ->
        val lower = placed[a].startDp
        val upper = placed[b].endDp
        val x = saved.takeIf { it.size == pairs.size }?.let { snap(it[index], lower, upper, creases) } ?: placed[b].startDp
        placed[a] = placed[a].copy(widthDp = x - lower)
        placed[b] = Column(placed[b].role, x, upper - x, placed[b].rail)
        Divider(index, x, lower, upper)
    }
    return placed to dividers
}
