# Layout and form factors

This document defines how the app divides a window into panes on every Android form factor, with the Samsung Galaxy Z TriFold inner display as the primary target. The numbers come from the physical device (the TriFold's published specs section 2). The pane arrangements come from a layout prototype on a TriFold-sized emulator.

## Windows are measured in panels

A panel is one physical section of the TriFold inner display. The inner display is 1080x792dp in landscape, and its three panels are not equal. In natural orientation they are 335dp, 382dp, and 363dp wide (670, 764, and 726 px). The user measured them on the physical device by dragging the debug crease lines onto the hinges. The app treats panels as the unit of layout whenever its window spans the full inner display.

The app takes crease positions from a device table first, then from Jetpack WindowManager. The physical TriFold (SM-F968U1, Android 17) reports one `FoldingFeature` at 540dp, `FLAT`, not separating, in a 1080x792dp window. The device has two hinges, so that single centered fold is not a physical crease. For models starting `SM-F968`, a 1080dp-wide window gets creases at 335dp and 717dp from the table, and the reported fold is ignored. The table is in natural orientation. When the display is turned upside down, the app mirrors it to 363dp and 745dp. A half turn changes no configuration, so the app reads the rotation from a display listener. On other devices, every vertical `FoldingFeature` becomes a crease. The emulator reported zero folding features in every state. In debug builds, **Show creases** in the Thread pane's **More Actions** menu draws the creases as dashed lines with drag handles and 1 px nudge buttons, and the reported fold as a dotted line. A placed crease overrides the table on that device and logs under `AmpCrease`.

When the window does not span the full inner display, there are no creases, and the width class alone decides the layout.

| Window | Size (dp) | Width class | Creases | Layout |
|---|---|---|---|---|
| TriFold cover | 411x960 | Compact | none | Single pane with a bottom bar |
| TriFold inner, landscape | 1080x792 | Expanded | two, vertical | Three panels |
| TriFold inner, portrait | 792x1080 | Medium | two, horizontal (ignored) | Rail, Thread, and Machine pane, 72 + 360 + 360 |
| Two-app split on the inner display | about 540 wide | Compact | none | Single pane with a bottom bar |
| Three-app split on the inner display | about 355 wide | Compact | none | Single pane with a bottom bar |
| DeX freeform window | 304 and up | any | none | By width class |
| DeX on an external monitor | 1200 to 1920 and up | Large or Extra-large | none | Four columns |

Android 17 ignores orientation locks on the inner display, so every layout above must work in both orientations. The cover display still honors orientation requests, and the app keeps it in portrait.

## Panes

A pane is one rectangular region with one job. The app has these pane kinds:

- **Sidebar.** Threads grouped as on ampcode.com (Pinned, active by project, Inactive Last 72h, Snoozed, Archived), search, Activity, projects, and the account switcher. Collapses to a 72dp rail of thread avatars with status dots.
- **Thread.** Title bar, transcript, queued messages, and composer.
- **Changes.** Changed files and the diff, with Ship, Review, and Sync. A Changes pane narrower than 600dp shows the file list, then one file's unified diff. It never shows the split diff and has no **Show Split Diff** toggle, in its toolbar or in **More Actions**.
- **Files.** The executor's file tree and a file viewer.
- **Terminal.** A shell in the executor.
- **Portal.** A web view of a service running in the executor.
- **Desktop.** The executor's remote desktop.
- **Space.** The thread's voice and video call.
- **Puck.** A Puck conversation that follows the focused thread.

Changes, Files, Terminal, Portal, Desktop, and Space are machine panes. They exist only for threads with an executor. A machine pane shows a tab strip so one pane slot can switch between them.

## The window bar holds window controls

On Medium and wider windows, a 40dp bar runs along the top edge of the window, above every pane. Its controls are 40dp tall, and its icon buttons and divider grips are 48 by 40dp targets (UX-33). No pane owns the bar, so it stays on screen in every arrangement, including Focus. It holds three controls:

- **Arrangement** (`window-arrangement`), the arrangement's short name as quiet text with a chevron, which opens a menu of the arrangements in [Arrangements by window class](#arrangements-by-window-class). Its accessible name is "Arrangement: <name>". Items carry `arrangement-<name>` and report `selected`.
- **Exit Focus** (`window-exit-focus`), shown only in Focus. It returns to the arrangement the window used before Focus.
- **Command Palette** (`window-palette`), an icon button, the touch entry point for the command palette in `screens.md` (UX-18).

Compact windows have no window bar. Their bottom bar picks the pane, and the palette opens from the Sidebar header and the Thread pane's **More Actions** menu. In a DeX freeform window, the bar sits under the system caption bar.

## Arrangements

An arrangement is an ordered list of panes, each with a span of one, two, or three panels. Arrangements are data, not separate screens. The user picks one from **Arrangement** in the window bar or with a keyboard shortcut from `screens.md`. Each window remembers its last arrangement, separately for the cover display and the inner display.

The table below defines each arrangement on the full-width inner display in landscape. [Arrangements by window class](#arrangements-by-window-class) maps each one to every other window.

| Arrangement | Panel 1 | Panel 2 | Panel 3 | Use |
|---|---|---|---|---|
| Triage (default) | Sidebar | Thread | Machine pane | Supervising many threads |
| Review | Rail and Thread | Changes, span 2 | | Reading a diff |
| Terminal | Rail and Thread | Terminal, span 2 | | Working in the shell next to the agent |
| Read | Sidebar | Thread, span 2 | | Long transcripts and wide code blocks |
| Puck | Sidebar | Thread | Puck | Coordinating agents by voice or chat |
| Focus | Any one pane, span 3 | | | Desktop, Portal, or a large diff |

The prototype measured why two-panel machine panes are needed. A 12sp monospace terminal fits 48 columns in a one-panel pane and 98 columns in a two-panel pane. A diff line in a one-panel pane truncates after about 45 characters. The prototype's emulator had equal 360dp panels. On the physical device, panels 2 and 3 together are 745dp. Panel 1 is 335dp, so in Review and Terminal the Thread pane beside the 72dp rail is about 263dp wide [INFERENCE: 335 - 72].

The Triage arrangement keeps every pane inside one physical panel, so no text crosses a crease. It was the only prototype variant where that held. The weighted variant with a 300dp sidebar and a 380dp machine pane put both creases through content.

Some actions change the arrangement because the user asked for a bigger view:

- **Expand** on a machine pane moves to the two-panel arrangement for its tab: Review for Changes and Terminal for Terminal. Files, Portal, Desktop, and Space have no arrangement of their own. **Expand** gives them the Review layout with their own tab selected, and **Arrangement** then marks **Review**. **Collapse** moves back.
- A machine pane has one 48dp header: the tab strip, then **Expand** or **Collapse**, **Focus**, and **Open in New Window** as icon buttons. The strip shows text tabs when every label fits with room for one control beside it. Otherwise it shows one 48dp icon tab per tab, and a long press names the tab. In Triage's 363dp pane the strip uses icon tabs. Controls that do not fit beside the strip move to the header's **More Actions** (`pane-more`), each tagged with its own tag plus `-menu`. **Expand** is always in the row or in **More Actions** as `pane-expand-menu`, whatever the number of tabs, including when a Desktop tab is shown. The selected tab's own items sit above the moved controls in the same menu (UX-04, UX-18, UX-31).
- **Collapse** on the Sidebar is offered only in three-panel Triage, where it frees a panel. It does what **Expand** does: the rail and the Thread share panel 1 and the machine pane spans panels 2 and 3. That rail shows **Expand** (`sidebar-collapse`), which returns to Triage. Where the Sidebar's column would keep its width, as on Compact windows, in Read, in Puck, and on Large windows, the Sidebar has no **Collapse** (UX-09, UX-35).
- Settings opens in the Thread column. While Settings or a page it opened is in that column, the window lays out as Read, so on the inner display the Sidebar keeps panel 1, the Settings index sits in panel 2, and the page in panel 3. **Close** (`settings-close`) takes Settings and what it opened away, and the columns return. On a one-pane window Settings opens over the Sidebar (UX-21).
- A Thread pane wider than 600dp, as in Read and in Focus, keeps a 600dp measure (`AmpMetrics.readingMeasure`) for the title bar, transcript, and composer. It starts on the crease nearest its centered position that still fits, which is the 335dp crease in both Read and Focus, so switching between them leaves the text in place; with no crease it centers. Lines stay near 75 characters and cross at most one fold (UX-34).
- Opening a diff from a tool card in the transcript selects the Changes tab and scrolls to the file. It does not change the arrangement.
- Opening a Desktop or Portal tab offers **Focus**. The app never enters Focus on its own.

### Arrangements by window class

Every arrangement exists on every window class, so a shortcut or a menu item always has a defined result. "Rail" is the 72dp collapsed Sidebar.

| Arrangement | Inner display, landscape | Medium, 720dp and wider, such as inner portrait | Expanded without creases | Large and Extra-large | Compact |
|---|---|---|---|---|---|
| Triage | Sidebar, Thread, Machine pane | Rail, Thread, and Machine pane, split equally | Rail, Thread, and Machine pane at 380dp | Sidebar at 300dp, Thread, and two machine panes | Thread destination |
| Review | Rail and Thread, Changes span 2 | Rail and Changes at full width | Rail, Thread at 360dp, and Changes in the rest | Sidebar, Thread, and Changes across both machine columns | Machine destination on its Changes tab |
| Terminal | Rail and Thread, Terminal span 2 | Rail and Terminal at full width | Rail, Thread at 360dp, and Terminal in the rest | Sidebar, Thread, and Terminal across both machine columns | Terminal destination |
| Read | Sidebar, Thread span 2 | Rail and Thread at full width | Rail and Thread | Sidebar and Thread, no machine panes | Thread destination |
| Puck | Sidebar, Thread, Puck | Rail, Thread, and Puck, split equally | Rail, Thread, and Puck at 380dp | Sidebar, Thread, Puck, and one machine pane | Puck route |
| Focus | One pane, span 3 | One pane at full width | One pane at full width | One pane at full width | Not offered. A Compact window already shows one pane. |

A Medium window narrower than 720dp keeps the rail and otherwise uses the Compact column. In the Medium layouts with a full-width machine pane, the Thread pane comes back with **Collapse** or a tap on its rail avatar.

## Dividers snap to creases

Panes are separated by draggable dividers. When the window has creases, a released divider snaps to the nearest crease that leaves every pane at least one panel wide, so a pane edge always sits on a fold. A window with no creases snaps a released divider to its midpoint instead.

In the prototype, a release at 684dp snapped to the 720dp crease. A release at 534dp snapped to the 540dp midpoint on the inner display, which put the Thread pane across the 360dp crease. That is why the midpoint rule no longer applies when creases exist.

The divider is a real control with the test tag `divider-<index>`. Its 48 by 40dp grip sits in the window bar above the divider, and dragging it draws the divider down the panes. A grip down the full height of the divider covered the outer 12dp of both neighbors, so a title bar's More Actions and the composer's Attach and Send measured 40dp wide (UX-33). Expand and Collapse buttons offer the same result without dragging, so agent-device and keyboard users never need a gesture.

## Sheets and menus stay inside one panel

A sheet or a menu that crossed a crease would put its text on a fold. Each one opens inside one panel:

- A bottom sheet opens from a pane. Share, the New Thread executor and mode pickers, Attach, and the Changes sheets all use `AmpSheet` in `:core:design`, and `WindowShell` gives it the span of the pane it opens from. The sheet sits on the widest stretch of that pane between creases, at most 640dp wide and centered in that stretch. From the Thread pane in Triage or Read, the stretch is the middle panel, 382dp wide from 335dp to 717dp. In a pane with no crease, the stretch is the whole pane.
- The command palette opens from no pane, so it takes the widest panel of the window. On the inner display that is the middle panel, 382dp wide; the palette keeps 8dp from each crease and centers vertically.
- A menu (`AmpDropdownMenu`) opens under its anchor, inside the panel that holds the anchor's center. It opens over the anchor only when it does not fit above the navigation bar.
- The account switcher opens 8dp under the avatar, across the Sidebar less 8dp each side, or 8dp over the avatar when the rail puts it at the window's foot.

## Layout by width class

The width class decides the layout when the window is not the full inner display, and on displays other than the TriFold.

- **Compact (under 600dp).** One pane at a time with a bottom bar: Threads, Thread, Machine, and Terminal. **Machine** opens the machine pane, whose tab strip holds every machine tab except Terminal. **Terminal** opens the terminal, so the machine pane does not repeat it as a tab. The cover display uses this layout.
- **Medium (600 to 839dp).** At 720dp and wider: the rail, the Thread pane, and one machine pane, split equally. Inner-display portrait is 792dp wide, so it gets 72 + 360 + 360, and the transcript and a machine pane sit side by side. The prototype's portrait layout used a 280dp Sidebar and let machine panes replace the transcript. That wasted the width this layout uses. Below 720dp: the rail and the Thread pane, with machine panes as tabs in the Thread title bar that replace the transcript while open. Portrait creases are horizontal, so they cut across every pane. Panes do not split at them.
- **Expanded (840 to 1199dp).** Three panels when creases are present. On other devices, a rail, the thread, and one machine pane at 380dp.
- **Large and Extra-large (1200dp and up).** Four columns: Sidebar at 300dp, Thread, and two machine panes side by side, such as Changes and Terminal.

## Folding and unfolding keep your place

Folding and unfolding changes the window size without recreating app state. The selected thread, scroll position, draft text, open machine tab, and terminal session survive the change.

The cover display and the inner display keep separate saved workspaces, each with its own arrangement and pane stack. Unfolding restores the inner workspace and opens the cover's current thread in it. If a machine tab had focus on the cover, unfolding selects the two-panel arrangement for that tab, as **Expand** would, so a terminal opened on the cover grows from 53 columns to 98 (`docs/design/terminal.md`, "Columns per arrangement"). Folding saves the inner workspace and shows the cover layout on the destination of the pane that had focus, with the same thread.

## Multiple windows

The app declares `PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI`, so the system offers **New Window**. Each window owns its own arrangement and selected thread. All windows share one data layer, so a message sent in one window appears in the others without a reload.

These actions open a new window, matching the macOS app's auxiliary windows:

- **Open in New Window** on a thread.
- **Open in New Window** on a machine pane, which opens that pane alone for the same thread.
- **Open in New Window** on a file or a portal.

Drag and drop works across windows and apps:

- Drop images and files from another app onto the composer to attach them, or onto the Files pane to upload them.
- Drag a file from the Files pane to another app as a content URI.
- Drag a machine pane tab onto another panel to move it there.

## Keyboard, mouse, and trackpad

The TriFold has no S Pen, so hardware keyboards and pointing devices through DeX and Bluetooth are the precision inputs. The app supports Tab focus order, arrow-key navigation in lists, hover states, right-click context menus on threads, files, and diff lines, and wheel scrolling in every scrollable pane. `screens.md` holds the only shortcut table, and the app publishes those shortcuts to the system helper through `KeyboardShortcutGroup`.

## Test tags for layout

Every pane root carries the test tag `pane-<kind>`, such as `pane-thread` and `pane-changes`. The window bar carries `window-arrangement`, `window-palette`, and `window-exit-focus`. Arrangement menu items carry `arrangement-<name>` and report `selected`. The prototype confirmed that these tags resolve as agent-device `id=` selectors when the root sets `testTagsAsResourceId`.

## Open questions

- Does the inner display ever run upside down? Android 17 ignores orientation locks there, so it can [INFERENCE]. The mirrored table (363dp and 745dp) passes a unit test but has not been checked on the device.
- Does the TriFold expose `WindowAreaController` presentation on the cover display while unfolded? If it does, the cover could show a glanceable list of working threads.
- How does Samsung size each app in a three-app split, and is any split narrower than the 304dp minimum the app supports?
