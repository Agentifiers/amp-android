package com.agentifiers.amp.layout

import com.agentifiers.amp.layout.Arrangement.Focus
import com.agentifiers.amp.layout.Arrangement.Puck
import com.agentifiers.amp.layout.Arrangement.Read
import com.agentifiers.amp.layout.Arrangement.Review
import com.agentifiers.amp.layout.Arrangement.Terminal
import com.agentifiers.amp.layout.Arrangement.Triage
import com.agentifiers.amp.layout.SlotRole.Machine
import com.agentifiers.amp.layout.SlotRole.Nav
import com.agentifiers.amp.layout.SlotRole.SecondMachine
import com.agentifiers.amp.layout.SlotRole.Thread
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector

internal val InnerLandscape = WindowGeometry(1080f, 792f, resolveCreases("SM-F968U1", 1080f, DisplayRotation.Natural, listOf(540f)))
internal val InnerUpsideDown = WindowGeometry(1080f, 792f, resolveCreases("SM-F968U1", 1080f, DisplayRotation.Half, listOf(540f)))
internal val InnerPortrait = WindowGeometry(792f, 1080f, Creases.None)
internal val Cover = WindowGeometry(411f, 960f, Creases.None)
internal val Freeform = WindowGeometry(304f, 600f, Creases.None)
internal val DexMonitor = WindowGeometry(1920f, 1080f, Creases.None)
internal val MediumNarrow = WindowGeometry(700f, 900f, Creases.None)
internal val ExpandedNoCreases = WindowGeometry(1080f, 792f, resolveCreases("Pixel 9 Pro Fold", 1080f, DisplayRotation.Natural, listOf(540f)))

internal val Rail = Column(Nav, 0f, 72f, rail = true)

private val AllArrangements = listOf(Triage, Review, Terminal, Read, Puck, Focus)
private val BottomBar = Switcher.BottomBar(listOf(NavItem.Threads, NavItem.Thread, NavItem.Machine, NavItem.Terminal))

private data class Shown(val columns: List<Column>, val windowBar: WindowBar?, val switcher: Switcher?)

private fun LayoutPlan.shown() = Shown(columns, windowBar, switcher)

class PlanLayoutTest {
    @get:Rule
    val errors = ErrorCollector()

    private fun expect(name: String, geometry: WindowGeometry, workspace: Workspace, expected: Shown) {
        errors.checkSucceeds { assertEquals(name, expected, planLayout(geometry, workspace).shown()) }
    }

    private fun wide(geometry: WindowGeometry, arrangement: Arrangement, vararg columns: Column) = expect(
        "${geometry.widthDp} $arrangement",
        geometry,
        Workspace.Wide.arrange(arrangement, onePane = false),
        Shown(columns.toList(), WindowBar(AllArrangements, arrangement, exitFocus = arrangement == Focus), switcher = null),
    )

    private fun compact(geometry: WindowGeometry, arrangement: Arrangement, column: Column) = expect(
        "${geometry.widthDp} $arrangement",
        geometry,
        Workspace.Compact.arrange(arrangement, onePane = true),
        Shown(listOf(column), windowBar = null, switcher = BottomBar),
    )

    @Test
    fun innerLandscapePutsPanesOnTheMeasuredPanels() {
        wide(InnerLandscape, Triage, Column(Nav, 0f, 335f), Column(Thread, 335f, 382f), Column(Machine, 717f, 363f))
        wide(InnerLandscape, Review, Rail, Column(Thread, 72f, 263f), Column(Machine, 335f, 745f))
        wide(InnerLandscape, Terminal, Rail, Column(Thread, 72f, 263f), Column(Machine, 335f, 745f))
        wide(InnerLandscape, Read, Column(Nav, 0f, 335f), Column(Thread, 335f, 745f))
        wide(InnerLandscape, Puck, Column(Nav, 0f, 335f), Column(Thread, 335f, 382f), Column(SlotRole.Puck, 717f, 363f))
        wide(InnerLandscape, Focus, Column(Thread, 0f, 1080f))
    }

    @Test
    fun innerLandscapeDividersSitOnTheCreases() {
        val triage = planLayout(InnerLandscape, Workspace.Wide.arrange(Triage, onePane = false))
        val review = planLayout(InnerLandscape, Workspace.Wide.arrange(Review, onePane = false))

        assertEquals(listOf(listOf(335f, 717f), listOf(335f)), listOf(triage.dividers.map { it.xDp }, review.dividers.map { it.xDp }))
    }

    @Test
    fun innerTurnedUpsideDownMirrorsThePanels() {
        wide(InnerUpsideDown, Triage, Column(Nav, 0f, 363f), Column(Thread, 363f, 382f), Column(Machine, 745f, 335f))
    }

    @Test
    fun innerPortraitSplitsTheWidthEquallyBesideTheRail() {
        wide(InnerPortrait, Triage, Rail, Column(Thread, 72f, 360f), Column(Machine, 432f, 360f))
        wide(InnerPortrait, Review, Rail, Column(Machine, 72f, 720f))
        wide(InnerPortrait, Terminal, Rail, Column(Machine, 72f, 720f))
        wide(InnerPortrait, Read, Rail, Column(Thread, 72f, 720f))
        wide(InnerPortrait, Puck, Rail, Column(Thread, 72f, 360f), Column(SlotRole.Puck, 432f, 360f))
        wide(InnerPortrait, Focus, Column(Thread, 0f, 792f))
    }

    @Test
    fun expandedWithoutCreasesUsesFixedSidePanes() {
        wide(ExpandedNoCreases, Triage, Rail, Column(Thread, 72f, 628f), Column(Machine, 700f, 380f))
        wide(ExpandedNoCreases, Review, Rail, Column(Thread, 72f, 360f), Column(Machine, 432f, 648f))
        wide(ExpandedNoCreases, Terminal, Rail, Column(Thread, 72f, 360f), Column(Machine, 432f, 648f))
        wide(ExpandedNoCreases, Read, Rail, Column(Thread, 72f, 1008f))
        wide(ExpandedNoCreases, Puck, Rail, Column(Thread, 72f, 628f), Column(SlotRole.Puck, 700f, 380f))
        wide(ExpandedNoCreases, Focus, Column(Thread, 0f, 1080f))
    }

    @Test
    fun dexMonitorShowsFourColumns() {
        wide(DexMonitor, Triage, Column(Nav, 0f, 300f), Column(Thread, 300f, 540f), Column(Machine, 840f, 540f), Column(SecondMachine, 1380f, 540f))
        wide(DexMonitor, Review, Column(Nav, 0f, 300f), Column(Thread, 300f, 540f), Column(Machine, 840f, 1080f))
        wide(DexMonitor, Terminal, Column(Nav, 0f, 300f), Column(Thread, 300f, 540f), Column(Machine, 840f, 1080f))
        wide(DexMonitor, Read, Column(Nav, 0f, 300f), Column(Thread, 300f, 1620f))
        wide(DexMonitor, Puck, Column(Nav, 0f, 300f), Column(Thread, 300f, 540f), Column(SlotRole.Puck, 840f, 540f), Column(Machine, 1380f, 540f))
        wide(DexMonitor, Focus, Column(Thread, 0f, 1920f))
    }

    @Test
    fun coverShowsThePaneEachArrangementMapsToWithABottomBar() {
        compact(Cover, Triage, Column(Thread, 0f, 411f))
        compact(Cover, Review, Column(Machine, 0f, 411f))
        compact(Cover, Terminal, Column(Machine, 0f, 411f))
        compact(Cover, Read, Column(Thread, 0f, 411f))
        compact(Cover, Puck, Column(SlotRole.Puck, 0f, 411f))
        compact(Cover, Focus, Column(Nav, 0f, 411f))
    }

    @Test
    fun freeformAt304ShowsThePaneEachArrangementMapsToWithABottomBar() {
        compact(Freeform, Triage, Column(Thread, 0f, 304f))
        compact(Freeform, Review, Column(Machine, 0f, 304f))
        compact(Freeform, Terminal, Column(Machine, 0f, 304f))
        compact(Freeform, Read, Column(Thread, 0f, 304f))
        compact(Freeform, Puck, Column(SlotRole.Puck, 0f, 304f))
        compact(Freeform, Focus, Column(Nav, 0f, 304f))
    }

    @Test
    fun mediumUnder720ShowsTheRailAndTheFocusedPaneWithTitleTabs() {
        val bar = { a: Arrangement -> WindowBar(listOf(Triage, Review, Terminal, Read, Puck), a, exitFocus = false) }
        val tabs = Switcher.TitleTabs(listOf(NavItem.Thread, NavItem.Machine, NavItem.Terminal))

        expect("700 Triage", MediumNarrow, Workspace.Wide, Shown(listOf(Rail, Column(Thread, 72f, 628f)), bar(Triage), tabs))
        expect(
            "700 Review",
            MediumNarrow,
            Workspace.Wide.arrange(Review, onePane = true),
            Shown(listOf(Rail, Column(Machine, 72f, 628f)), bar(Review), tabs),
        )
    }

    @Test
    fun theTriFoldTableOutranksTheReportedFoldAndASingleFoldMakesNoPanels() {
        assertEquals(Creases(listOf(335f, 717f), CreaseSource.DeviceTable, listOf(540f)), InnerLandscape.creases)
        assertEquals(Creases(listOf(363f, 745f), CreaseSource.DeviceTable, listOf(540f)), InnerUpsideDown.creases)
        assertEquals(Creases(listOf(540f), CreaseSource.Reported, listOf(540f)), ExpandedNoCreases.creases)
        assertEquals(
            listOf(listOf(335f, 717f), emptyList()),
            listOf(planLayout(InnerLandscape, Workspace.Wide).creasesDp, planLayout(ExpandedNoCreases, Workspace.Wide).creasesDp),
        )
    }

    @Test
    fun aStandInRowGivesAnEmulatorTheDevicesCreasesOnlyAtItsWidthAndOnlyWhenPassed() {
        val avd = listOf(CreaseTableEntry("sdk_gphone", 1080f, listOf(335f, 717f)))

        assertEquals(Creases(listOf(335f, 717f), CreaseSource.DeviceTable, emptyList()), resolveCreases("sdk_gphone64_arm64", 1080f, DisplayRotation.Natural, emptyList(), standIns = avd))
        assertEquals(Creases.None, resolveCreases("sdk_gphone64_arm64", 792f, DisplayRotation.Natural, emptyList(), standIns = avd))
        assertEquals(Creases.None, resolveCreases("sdk_gphone64_arm64", 1080f, DisplayRotation.Natural, emptyList()))
    }

    @Test
    fun calibratedCreasesOutrankTheTableOnlyForTheirWindowWidth() {
        val calibration = CreaseCalibration(1080f, listOf(352f, 720f))
        val calibrated = resolveCreases("SM-F968U1", 1080f, DisplayRotation.Natural, listOf(540f), calibration)

        assertEquals(Creases(listOf(352f, 720f), CreaseSource.Calibrated, listOf(540f)), calibrated)
        assertEquals(
            listOf(Column(Nav, 0f, 352f), Column(Thread, 352f, 368f), Column(Machine, 720f, 360f)),
            planLayout(WindowGeometry(1080f, 792f, calibrated), Workspace.Wide).columns,
        )
        assertEquals(Creases.None, resolveCreases("SM-F968U1", 792f, DisplayRotation.Natural, emptyList(), calibration))
    }

    @Test
    fun withCreasesAReleaseSnapsToTheNearestCreaseBetweenTheNeighbors() {
        val review = Workspace.Wide.arrange(Review, onePane = false)
        val plan = planLayout(InnerLandscape, review)
        val at717 = plan.release(0, 600f)

        assertEquals(listOf(717f), at717)
        assertEquals(listOf(335f), plan.release(0, 400f))
        assertEquals(
            listOf(Rail, Column(Thread, 72f, 645f), Column(Machine, 717f, 363f)),
            planLayout(InnerLandscape, review.copy(dividersDp = at717)).columns,
        )
        assertEquals(listOf(335f, 717f), planLayout(InnerLandscape, Workspace.Wide.arrange(Triage, onePane = false)).release(0, 600f))
    }

    @Test
    fun withoutCreasesAReleaseSnapsToTheMidpointOrStaysClampedFromTheEdges() {
        val triage = Workspace.Wide.arrange(Triage, onePane = false)
        val plan = planLayout(ExpandedNoCreases, triage)

        assertEquals(
            listOf(listOf(576f), listOf(800f), listOf(800f)),
            listOf(plan.release(0, 600f), plan.release(0, 800f), plan.release(0, 1000f)),
        )
        assertEquals(
            listOf(Rail, Column(Thread, 72f, 728f), Column(Machine, 800f, 280f)),
            planLayout(ExpandedNoCreases, triage.copy(dividersDp = listOf(800f))).columns,
        )
    }

    @Test
    fun savedDividersResnapWhenTheWindowTurnsUpsideDown() {
        val review = Workspace.Wide.arrange(Review, onePane = false).copy(dividersDp = listOf(717f))
        val plan = planLayout(InnerUpsideDown, review)

        assertEquals(listOf(745f), plan.dividers.map { it.xDp })
        assertEquals(listOf(Rail, Column(Thread, 72f, 673f), Column(Machine, 745f, 335f)), plan.columns)
    }

    @Test
    fun arrangingClearsSavedDividers() {
        val dragged = Workspace(arrangement = Review, focus = Machine, dividersDp = listOf(717f))
        val triage = dragged.arrange(Triage, onePane = false)

        assertEquals(Workspace(arrangement = Triage, focus = Machine), triage)
        assertEquals(listOf(335f, 717f), planLayout(InnerLandscape, triage).dividers.map { it.xDp })
    }

    @Test
    fun machinePaneControlsFollowTheArrangement() {
        fun controls(geometry: WindowGeometry, arrangement: Arrangement) =
            planLayout(geometry, Workspace.Wide.arrange(arrangement, onePane = false)).paneControls

        assertEquals(listOf(PaneControl.Expand, PaneControl.Focus, PaneControl.NewWindow), controls(InnerLandscape, Triage))
        assertEquals(listOf(PaneControl.Collapse, PaneControl.Focus, PaneControl.NewWindow), controls(InnerLandscape, Review))
        assertEquals(listOf(PaneControl.NewWindow), controls(InnerLandscape, Focus))
        assertEquals(listOf(PaneControl.NewWindow), planLayout(Cover, Workspace.Compact).paneControls)
    }

    @Test
    fun theSidebarCollapsesOnlyWhereItFreesAPanel() {
        fun collapses(geometry: WindowGeometry, arrangement: Arrangement) =
            planLayout(geometry, Workspace.Wide.arrange(arrangement, onePane = false)).sidebarCollapses

        assertEquals(true, collapses(InnerLandscape, Triage))
        assertEquals(
            listOf(false, false, false, false),
            listOf(collapses(InnerLandscape, Read), collapses(InnerLandscape, Puck), collapses(DexMonitor, Triage), planLayout(Cover, Workspace.Compact).sidebarCollapses),
        )
    }

    @Test
    fun collapsingTheSidebarInTriageLeavesTheRailBesideTheThreadAndExpandReturns() {
        val collapsed = Workspace.Wide.expand()
        val plan = planLayout(InnerLandscape, collapsed)

        assertEquals(listOf(Rail, Column(Thread, 72f, 263f), Column(Machine, 335f, 745f)), plan.columns)
        assertEquals(true, plan.sidebarExpands)
        assertEquals(listOf(Column(Nav, 0f, 335f), Column(Thread, 335f, 382f), Column(Machine, 717f, 363f)), planLayout(InnerLandscape, collapsed.restored()).columns)
        assertEquals(false, planLayout(InnerPortrait, Workspace.Wide).sidebarExpands)
    }
}
