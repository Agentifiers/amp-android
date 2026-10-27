// :layout  package amp.layout
// The layout model is data plus one pure function:
//     planLayout(geometry: WindowGeometry, workspace: Workspace): LayoutPlan
// Snapshot-tested over a device matrix (TriFold cover 411, inner landscape 1080, inner portrait 792,
// DeX freeform 304..., DeX monitor Large/XL, three-app split ~355, Pixel Fold, phone). Compose only
// hosts the result. Features never see geometry; they render inside a slot's constraints.
// Names follow docs/design/layout.md: arrangements Triage, Review, Terminal, Read, Puck, Focus.
package amp.layout

import amp.model.MachineTab
import amp.model.PaneDestination
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap

// ── Inputs ────────────────────────────────────────────────────────────────────

data class WindowGeometry(
    val widthDp: Float,
    val heightDp: Float,
    val width: WidthClass,
    val height: HeightClass,
    val creases: PersistentList<Crease>,
    val creaseSource: CreaseSource,
    val posture: Posture,
    val display: DisplayKind,
    val input: InputProfile,
    val captionBarDp: Float,            // DeX/desktop windowing caption inset
)

/** M3 breakpoints: Compact <600, Medium 600–839, Expanded 840–1199, Large 1200–1599, ExtraLarge ≥1600. */
enum class WidthClass { Compact, Medium, Expanded, Large, ExtraLarge }
enum class HeightClass { Compact, Medium, Expanded }

/** A physical fold line in window coordinates. Vertical creases split panels; horizontal ones become avoidBands. */
data class Crease(val positionDp: Float, val orientation: CreaseOrientation, val widthDp: Float, val occluding: Boolean)
enum class CreaseOrientation { Vertical, Horizontal }

/**
 * Where creases came from. The emulator reported zero FoldingFeatures (logcat-LayoutProto.txt:2,
 * measured), and the real TriFold report is unknown (device.md:389). Resolution order:
 *   1. WindowInfoTracker FoldingFeatures with isSeparating or FLAT bounds (any count, not firstOrNull)
 *   2. Fallback for a 1080dp-wide inner window with no features: thirds, per layout.md [guess until measured]
 *   3. None
 */
enum class CreaseSource { Reported, Thirds, None }

/** TriFold has no app-visible half-open posture (device.md:379); the model still carries it for Fold/Flip. */
sealed interface Posture {
    data object Flat : Posture
    data class Book(val hinge: Crease) : Posture
    data class Tabletop(val hinge: Crease) : Posture
}

enum class DisplayKind { Phone, FoldableCover, FoldableInner, TriFoldInner, Tablet, DesktopWindow, ExternalMonitor }
data class InputProfile(val touch: Boolean, val hardwareKeyboard: Boolean, val pointer: Boolean)

// ── Arrangements (layout.md) ──────────────────────────────────────────────────

/** What a column holds. Each role owns one PaneStack in the Workspace. */
enum class SlotRole { Nav, Thread, Machine, SecondMachine, Puck, Focused }

/**
 * One column of an arrangement on the full inner display: a role and how many panels it covers.
 * panels = 0 is the 72dp rail, which sits inside the next column's panel (layout.md "Rail and Thread").
 * [tab] is the machine tab the column opens with when the arrangement is picked from the window bar.
 */
data class ColumnSpec(val role: SlotRole, val panels: Int, val nav: NavForm? = null, val tab: MachineTab? = null)

/**
 * An arrangement is an ordered list of columns, each spanning one, two or three panels (layout.md).
 * `userWide` marks the two arrangements the user picks to read across a crease (Read, Focus): the
 * planner never enters them on its own, and only they may put a non-SPANNABLE pane across a crease.
 */
enum class Arrangement(val columns: List<ColumnSpec>, val userWide: Boolean) {
    /** Default. Every pane inside one panel, so no text crosses a crease (prototype V1-inner.png). */
    Triage(listOf(ColumnSpec(SlotRole.Nav, 1, NavForm.Sidebar), ColumnSpec(SlotRole.Thread, 1), ColumnSpec(SlotRole.Machine, 1)), userWide = false),
    /** Rail and Thread share panel 1; Changes spans 2 and 3 (V3-inner-swapped.png). */
    Review(listOf(ColumnSpec(SlotRole.Nav, 0, NavForm.Rail), ColumnSpec(SlotRole.Thread, 1), ColumnSpec(SlotRole.Machine, 2, tab = MachineTab.Changes(focusPath = null))), userWide = false),
    /** As Review with the Terminal tab: 98 columns at 12sp in two panels versus 48 in one (measured). */
    Terminal(listOf(ColumnSpec(SlotRole.Nav, 0, NavForm.Rail), ColumnSpec(SlotRole.Thread, 1), ColumnSpec(SlotRole.Machine, 2, tab = MachineTab.Terminal(session = null))), userWide = false),
    Read(listOf(ColumnSpec(SlotRole.Nav, 1, NavForm.Sidebar), ColumnSpec(SlotRole.Thread, 2)), userWide = true),
    Puck(listOf(ColumnSpec(SlotRole.Nav, 1, NavForm.Sidebar), ColumnSpec(SlotRole.Thread, 1), ColumnSpec(SlotRole.Puck, 1)), userWide = false),
    /** Any one pane across all three panels. Offered on Desktop and Portal tabs, never entered automatically. */
    Focus(listOf(ColumnSpec(SlotRole.Focused, 3)), userWide = true),
}

/**
 * Roles whose pane may span a crease without the user asking (C's SPANNABLE rule). Machine panes
 * are diffs, terminals, desktops and portals, which gain from width. A transcript cut by a crease
 * is harder to read, so Thread, Nav and Puck span only inside a userWide arrangement.
 */
val SPANNABLE: Set<SlotRole> = setOf(SlotRole.Machine, SlotRole.SecondMachine)

/**
 * Per-window user state. Each window (multi-instance, DeX, cover vs inner) has its own Workspace,
 * saved under its windowKey with the window's account (store/Persistence.kt WorkspaceEntry). The cover
 * and the inner display keep separate saved workspaces. Two windows on the same thread share the
 * ThreadEngine, not the layout.
 */
data class Workspace(
    val arrangement: Arrangement,
    val stacks: PersistentMap<SlotRole, PaneStack>,
    val focus: SlotRole,
    val dividerOffsetsDp: PersistentList<Float>,     // user-dragged, re-snapped on geometry change
)
data class PaneStack(val entries: PersistentList<PaneDestination>) { val top: PaneDestination get() = entries.last() }

// ── Output ────────────────────────────────────────────────────────────────────

data class LayoutPlan(
    val chrome: Chrome,
    /** Window chrome outside every pane, Medium and wider; null on Compact. */
    val windowBar: WindowBar?,
    val slots: PersistentList<Slot>,
    val dividers: PersistentList<Divider>,
    /**
     * Horizontal bands (window y, dp) where interactive chrome must not sit: each horizontal crease
     * ± 12dp, as in inner portrait (creases near y 252 and 612 in the prototype). Panes ignore them
     * and are never split by them (layout.md); sheets, the composer, snackbars and the bottom bar
     * move off them.
     */
    val avoidBands: PersistentList<ClosedFloatingPointRange<Float>>,
    /** Roles that didn't get a slot are reachable via chrome (bottom bar / rail / More menu). */
    val offscreen: PersistentList<SlotRole>,
    val debug: String,                 // e.g. "1080x792 Expanded · Triage · 360/360/360 · creases@360,720 (Thirds)"
)

/**
 * The window bar (layout.md): the arrangement control (`window-arrangement`, items `arrangement-<name>`),
 * the command palette button (`window-palette`), and in Focus an exit button (`window-exit-focus`), so a
 * touch user can always leave Focus. Arrangements are listed only where layout.md maps them for this
 * window class.
 */
data class WindowBar(val arrangements: PersistentList<Arrangement>, val selected: Arrangement, val exitFocus: Boolean)

sealed interface Chrome {
    data object None : Chrome
    data class Rail(val widthDp: Float) : Chrome                     // 72dp
    data class BottomBar(val items: PersistentList<NavItem>) : Chrome // Compact
    data class TopTabs(val items: PersistentList<NavItem>) : Chrome   // Compact + short height (cover landscape, DeX tiny)
    /** Medium: machine panes open as tabs in the Thread title bar and replace the transcript while open. */
    data class ThreadTitleTabs(val items: PersistentList<NavItem>) : Chrome
}
/** layout.md Compact bottom bar: Threads, Thread, Changes, Terminal. Files, Portal, Desktop, Space open from More. */
enum class NavItem { Threads, Thread, Changes, Terminal }

/** A slot is a rectangle of the window bound to one PaneStack. `panels` = which physical panels it covers. */
data class Slot(
    val role: SlotRole,
    val xDp: ClosedFloatingPointRange<Float>,
    val panels: IntRange,             // 0..2 on TriFold inner landscape; 0..0 when no creases
    val stack: PaneStack,
    val testTag: String,              // "slot-thread" etc.
) { val spansCrease: Boolean get() = panels.first != panels.last }

data class Divider(val between: Pair<SlotRole, SlotRole>, val xDp: Float, val snapTargetsDp: PersistentList<Float>, val draggable: Boolean)

// ── The function ──────────────────────────────────────────────────────────────

/**
 * Rules (layout.md "Layout by width class" and "Arrangements by window class"):
 *  Compact (<600: cover 411, DeX freeform ≥304, two- and three-app splits):
 *      one slot = focused stack; BottomBar when height ≥ Medium else TopTabs.
 *  Medium ≥720 (TriFold inner portrait 792): Rail 72 | Thread | Machine, split equally (72 + 360 + 360).
 *      Horizontal creases → avoidBands.
 *  Medium <720 (Fold inner portrait): Rail | Thread; Chrome.ThreadTitleTabs, machine panes replace the transcript.
 *  Expanded with vertical creases (TriFold inner landscape 1080): workspace.arrangement over the panels;
 *      slot edges sit on creases.
 *  Expanded without creases: Rail | Thread | Machine at 380dp.
 *  Large and ExtraLarge (≥1200, DeX monitor): Sidebar 300 | Thread | Machine | SecondMachine.
 *  Every class maps each Arrangement to its own shape (layout.md table), so the window bar works everywhere.
 *  Book posture on Fold-class devices: slot edge = hinge; Tabletop: transcript top, composer bottom.
 *
 * Invariant (checked by an assertion in every snapshot test): for every slot with spansCrease,
 * slot.role in SPANNABLE or workspace.arrangement.userWide. No slot edge falls inside an occluding crease.
 */
fun planLayout(geometry: WindowGeometry, workspace: Workspace): LayoutPlan = TODO()

/**
 * Divider snap (layout.md "Dividers snap to creases"). With creases, a release snaps to the nearest
 * crease, whatever the distance, so no slot edge ends inside a panel. With no creases, a release within
 * 48dp of the midpoint snaps to it. Prototype: 684 → 720 (crease).
 * Minimum slot width 280dp (transcript) / 320dp (machine) or the divider stops.
 */
fun snap(dropDp: Float, creasesDp: List<Float>, midpointDp: Float, thresholdDp: Float = 48f): Float = TODO()

/**
 * Expand on a machine pane: Triage → Terminal for the Terminal tab, and → the Review shape for every
 * other tab (Changes, Files, Portal, Desktop, Space), keeping that tab. Collapse goes back.
 * Opening a diff from a tool card selects the Changes tab and does not change the arrangement.
 */
fun expand(workspace: Workspace, tab: MachineTab): Workspace = TODO()
fun collapse(workspace: Workspace): Workspace = TODO()

/**
 * Unfold: restore the inner workspace, open the cover's current thread in it, and when a machine tab
 * had focus on the cover, select the two-panel arrangement for that tab (Terminal → Terminal, others → Review shape).
 */
fun unfold(inner: Workspace, cover: Workspace): Workspace = TODO()

/** Where a new destination goes (deep link, notification tap, Puck navigation, user tap). */
fun place(workspace: Workspace, geometry: WindowGeometry, dest: PaneDestination, from: SlotRole?): Workspace = TODO(
    "Thread → Thread; Machine(same thread) → Machine; Puck → Puck slot or push onto Thread; " +
    "Web(AccountPage) → Machine slot on Expanded and wider (side pane), the single stack on Compact; Compact: push onto the single stack"
)

/** Geometry from WindowManager + Compose: currentWindowAdaptiveInfo() + WindowInfoTracker. */
interface GeometrySource { val geometry: kotlinx.coroutines.flow.StateFlow<WindowGeometry> }
