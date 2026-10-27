package com.agentifiers.amp.layout

import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.SettingsPage
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.model.ThreadId
import org.junit.Assert.assertEquals
import org.junit.Test

private val X = requireNotNull(ThreadId.parse("T-0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"))
private val Y = requireNotNull(ThreadId.parse("T-11111111-2222-4333-8444-555555555555"))

private val TriageInner = planLayout(InnerLandscape, Workspace.Wide)
private val NotificationSettings = PaneDestination.Settings(SettingsPage.Notifications)

class WorkspaceTest {
    @Test
    fun placingAThreadSelectsItAndTheMachineColumnFollows() {
        val placed = Workspace(focus = SlotRole.Nav).place(PaneDestination.Thread(X), TriageInner)

        assertEquals(X, placed.thread)
        assertEquals(SlotRole.Thread, placed.focus)
        assertEquals(PaneDestination.Thread(X), placed.top(SlotRole.Thread))
        assertEquals(PaneDestination.Machine(X, MachineTab.Changes(focusPath = null)), placed.top(SlotRole.Machine))
    }

    @Test
    fun settingsOnTriageGoesOnTheThreadStackAndBackAndForwardWalkIt() {
        val placed = Workspace(thread = X).place(NotificationSettings, TriageInner)
        val back = requireNotNull(placed.back(compact = false))
        val forward = requireNotNull(back.goForward())

        assertEquals(
            listOf(NotificationSettings, PaneDestination.Thread(X), NotificationSettings),
            listOf(placed.top(SlotRole.Thread), back.top(SlotRole.Thread), forward.top(SlotRole.Thread)),
        )
        assertEquals(PaneDestination.Sidebar, placed.top(SlotRole.Nav))
        assertEquals(SlotRole.Thread, placed.focus)
    }

    @Test
    fun settingsOnThreePanelsKeepsTheSidebarAndSpansTheOtherTwoPanels() {
        val placed = Workspace(thread = X).place(NotificationSettings, TriageInner)
        val plan = planLayout(InnerLandscape, placed)

        assertEquals(listOf(Column(SlotRole.Nav, 0f, 335f), Column(SlotRole.Thread, 335f, 745f)), plan.columns)
        assertEquals(Arrangement.Triage, plan.arrangement)
    }

    @Test
    fun closeSettingsGivesBackTheThreadAndTheTriageColumns() {
        val closed = Workspace(thread = X).place(NotificationSettings, TriageInner).closeSettings()

        assertEquals(PaneDestination.Thread(X), closed.top(SlotRole.Thread))
        assertEquals(
            listOf(Column(SlotRole.Nav, 0f, 335f), Column(SlotRole.Thread, 335f, 382f), Column(SlotRole.Machine, 717f, 363f)),
            planLayout(InnerLandscape, closed).columns,
        )
    }

    @Test
    fun aWebPageFromSettingsStaysWideAndCloseTakesBothAway() {
        val settings = Workspace(thread = X).place(NotificationSettings, TriageInner)
        val web = PaneDestination.Web(WebSurface.AccountPage(AccountWebPage.Account, AccountId("A-1"), suffix = null))
        val opened = settings.place(web, planLayout(InnerLandscape, settings))

        assertEquals(web, opened.top(SlotRole.Thread))
        assertEquals(listOf(Column(SlotRole.Nav, 0f, 335f), Column(SlotRole.Thread, 335f, 745f)), planLayout(InnerLandscape, opened).columns)
        assertEquals(PaneDestination.Thread(X), opened.closeSettings().top(SlotRole.Thread))
    }

    @Test
    fun settingsInReviewOnTheInnerDisplayGoesToTheThreadColumn() {
        val review = Workspace(thread = X).arrange(Arrangement.Review, onePane = false)
        val placed = review.place(NotificationSettings, planLayout(InnerLandscape, review))

        assertEquals(NotificationSettings, placed.top(SlotRole.Thread))
        assertEquals(PaneDestination.Sidebar, placed.top(SlotRole.Nav))
        assertEquals(SlotRole.Thread, placed.focus)
    }

    @Test
    fun settingsOnCompactGoesOnTheNavStackAndFocusesIt() {
        val onThread = Workspace.Compact.arrange(Arrangement.Triage, onePane = true)
        val placed = onThread.place(NotificationSettings, planLayout(Cover, onThread))

        assertEquals(NotificationSettings, placed.top(SlotRole.Nav))
        assertEquals(SlotRole.Nav, placed.focus)
    }

    @Test
    fun newThreadPushesOverTheThreadAndOpeningAThreadClearsIt() {
        val drafting = Workspace(thread = X).place(PaneDestination.NewThread(prefill = null), TriageInner)
        val opened = drafting.place(PaneDestination.Thread(Y), TriageInner)

        assertEquals(PaneDestination.NewThread(prefill = null), drafting.top(SlotRole.Thread))
        assertEquals(PaneDestination.Thread(Y), opened.top(SlotRole.Thread))
    }

    @Test
    fun puckFromTriageSwitchesToThePuckArrangementAndToggleReturns() {
        val puck = Workspace.Wide.place(PaneDestination.Puck(conversation = null), TriageInner)
        val closed = puck.togglePuck(planLayout(InnerLandscape, puck))

        assertEquals(Arrangement.Puck to Arrangement.Triage, puck.arrangement to puck.restore)
        assertEquals(Arrangement.Triage to null, closed.arrangement to closed.restore)
    }

    @Test
    fun aThreadOnMediumReviewRestoresThePreviousArrangement() {
        val review = Workspace.Wide.arrange(Arrangement.Review, onePane = false)
        val placed = review.place(PaneDestination.Thread(X), planLayout(InnerPortrait, review))

        assertEquals(Arrangement.Triage, placed.arrangement)
        assertEquals(PaneDestination.Thread(X), placed.top(SlotRole.Thread))
    }

    @Test
    fun aTerminalInReadKeepsRead() {
        val read = Workspace(thread = X).arrange(Arrangement.Read, onePane = false)
        val placed = read.place(PaneDestination.Machine(X, MachineTab.Terminal(session = null)), planLayout(InnerLandscape, read))

        assertEquals(Arrangement.Read, placed.arrangement)
        assertEquals(PaneDestination.Machine(X, MachineTab.Terminal(session = null)), placed.top(SlotRole.Machine))
    }

    @Test
    fun arrangingOnAOnePaneWindowSelectsThePaneItMapsTo() {
        val cover = Workspace(focus = SlotRole.Nav, machineTab = MachineTab.Files(path = null))

        assertEquals(
            listOf(
                Workspace(focus = SlotRole.Thread, machineTab = MachineTab.Files(path = null)),
                Workspace(arrangement = Arrangement.Review, focus = SlotRole.Machine, machineTab = MachineTab.Changes(focusPath = null)),
                Workspace(arrangement = Arrangement.Terminal, focus = SlotRole.Machine, machineTab = MachineTab.Terminal(session = null)),
                Workspace(arrangement = Arrangement.Read, focus = SlotRole.Thread, machineTab = MachineTab.Files(path = null)),
                Workspace(arrangement = Arrangement.Puck, focus = SlotRole.Puck, machineTab = MachineTab.Files(path = null)),
                Workspace(focus = SlotRole.Nav, machineTab = MachineTab.Files(path = null)),
            ),
            Arrangement.entries.map { cover.arrange(it, onePane = true) },
        )
    }

    @Test
    fun theMachineItemKeepsANonTerminalTabAndMarksItSelected() {
        val onFiles = Workspace(focus = SlotRole.Thread, machineTab = MachineTab.Files(path = null)).select(NavItem.Machine)
        val fromTerminal = Workspace(focus = SlotRole.Machine, machineTab = MachineTab.Terminal(session = null)).select(NavItem.Machine)

        assertEquals(Workspace(focus = SlotRole.Machine, machineTab = MachineTab.Files(path = null)), onFiles)
        assertEquals(Workspace(focus = SlotRole.Machine, machineTab = MachineTab.Changes(focusPath = null)), fromTerminal)
        assertEquals(
            listOf(NavItem.Machine, NavItem.Machine, NavItem.Terminal),
            listOf(onFiles, fromTerminal, onFiles.select(NavItem.Terminal)).map { it.selected() },
        )
    }

    @Test
    fun expandGrowsTheMachineTabIntoItsArrangementAndRestoredReturns() {
        val terminal = Workspace(machineTab = MachineTab.Terminal(session = null)).expand()
        val files = Workspace(machineTab = MachineTab.Files(path = null)).expand()

        assertEquals(
            Workspace(Arrangement.Terminal, Arrangement.Triage, SlotRole.Machine, machineTab = MachineTab.Terminal(session = null)),
            terminal,
        )
        assertEquals(Workspace(Arrangement.Review, Arrangement.Triage, SlotRole.Machine, machineTab = MachineTab.Files(path = null)), files)
        assertEquals(Workspace(Arrangement.Triage, focus = SlotRole.Machine, machineTab = MachineTab.Terminal(session = null)), terminal.restored())
    }

    @Test
    fun toggleExpandAlternatesBetweenExpandAndCollapse() {
        val start = Workspace(machineTab = MachineTab.Terminal(session = null))
        val once = start.toggleExpand()
        val twice = once.toggleExpand()

        assertEquals(listOf(Arrangement.Terminal, Arrangement.Triage), listOf(once.arrangement, twice.arrangement))
    }

    @Test
    fun focusRemembersTheArrangementItCameFrom() {
        val focused = Workspace(Arrangement.Review, focus = SlotRole.Machine).arrange(Arrangement.Focus, onePane = false)

        assertEquals(Workspace(Arrangement.Focus, Arrangement.Review, SlotRole.Machine), focused)
        assertEquals(Workspace(Arrangement.Review, focus = SlotRole.Machine), focused.restored())
    }

    @Test
    fun cycleFocusWalksTheTriageColumnsAndWraps() {
        val machine = Workspace.Wide.cycleFocus(TriageInner, 1)
        val nav = machine.cycleFocus(TriageInner, 1)

        assertEquals(listOf(SlotRole.Machine, SlotRole.Nav), listOf(machine.focus, nav.focus))
    }

    @Test
    fun foldingShowsTheFocusedPaneOfTheInnerDisplayOnTheCover() {
        val inner = Workspace(focus = SlotRole.Machine, thread = X, machineTab = MachineTab.Terminal(session = null))

        assertEquals(
            Workspace(focus = SlotRole.Machine, thread = X, machineTab = MachineTab.Terminal(session = null)),
            fold(inner, Workspace.Compact),
        )
    }

    @Test
    fun unfoldingAFocusedTerminalGrowsItIntoTheTerminalArrangement() {
        val cover = Workspace(focus = SlotRole.Machine, thread = Y, machineTab = MachineTab.Terminal(session = null))

        assertEquals(
            Workspace(Arrangement.Terminal, Arrangement.Triage, SlotRole.Machine, Y, MachineTab.Terminal(session = null)),
            unfold(Workspace.Wide, cover),
        )
    }

    @Test
    fun unfoldingAFocusedThreadKeepsTheInnerArrangement() {
        val cover = Workspace(focus = SlotRole.Thread, thread = Y)

        assertEquals(Workspace(Arrangement.Read, thread = Y), unfold(Workspace(Arrangement.Read), cover))
    }

    @Test
    fun parseRouteReadsSavedRoutesAndDropsWebSurfaces() {
        assertEquals(
            PaneDestination.Machine(X, MachineTab.Terminal(session = null)),
            parseRoute("machine/T-0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b/terminal"),
        )
        assertEquals(PaneDestination.Settings(SettingsPage.Layout), parseRoute("settings/Layout"))
        assertEquals(null, parseRoute("web/account"))
    }

    @Test
    fun parseRouteRoundTripsRestorableDestinations() {
        val destinations = listOf(
            PaneDestination.Sidebar,
            PaneDestination.Thread(X),
            PaneDestination.Machine(X, MachineTab.Changes(focusPath = null)),
            PaneDestination.Running,
            PaneDestination.NewThread(prefill = null),
        )

        assertEquals(destinations, destinations.map { parseRoute(it.route) })
    }

    @Test
    fun threadsInListsTheThreadTheTriageColumnsShow() {
        assertEquals(setOf(X), Workspace(thread = X).threadsIn(TriageInner.panes))
    }

    @Test
    fun aThreadLinkDroppedOnTheMachineColumnOpensThatThreadsMachinePaneOnTheSameTab() {
        val terminal = Workspace(thread = X, machineTab = MachineTab.Terminal(session = null))
        val dropped = threadIn("see https://ampcode.com/threads/T-11111111-2222-4333-8444-555555555555 please")

        assertEquals(Y, dropped)
        val placed = terminal.drop(requireNotNull(dropped), SlotRole.Machine, TriageInner)
        assertEquals(PaneDestination.Machine(Y, MachineTab.Terminal(session = null)), placed.top(SlotRole.Machine))
        assertEquals(PaneDestination.Thread(Y), placed.top(SlotRole.Thread))
    }

    @Test
    fun aThreadDroppedOnTheSidebarOpensItInTheThreadColumnAndTextWithoutAThreadIdIsNotADrop() {
        val placed = Workspace(thread = X).drop(Y, SlotRole.Nav, TriageInner)

        assertEquals(PaneDestination.Thread(Y), placed.top(SlotRole.Thread))
        assertEquals(SlotRole.Thread, placed.focus)
        assertEquals(null, threadIn("https://ampcode.com/threads/not-a-thread"))
    }

    @Test
    fun closingPuckMovesFocusFromTheHiddenPuckColumnToTheThread() {
        val puck = Workspace(thread = X).place(PaneDestination.Puck(conversation = null), TriageInner)
        val closed = puck.togglePuck(planLayout(InnerLandscape, puck))

        assertEquals(SlotRole.Puck, puck.focus)
        assertEquals(SlotRole.Thread, closed.focusShown(planLayout(InnerLandscape, closed)).focus)
        val mediumReview = Workspace(thread = X, focus = SlotRole.Nav, arrangement = Arrangement.Review)
        assertEquals(SlotRole.Machine, mediumReview.focusShown(planLayout(InnerPortrait, mediumReview)).focus)
    }
}
