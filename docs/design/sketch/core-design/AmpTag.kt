// :core:design  package amp.design
// The single registry of test tags. Every interactive or verifiable node uses one of these, and
// `testTagsAsResourceId = true` at the root turns them into Android resource-ids for agent-device
// (`id=` selector, device.md:128-129). Entity-scoped tags embed stable ids so a test can target
// "the approve button of tool call TU-…" without positional refs.
//
// Rules the lint check `AmpTagRequired` enforces:
//  - Every Button/IconButton/clickable/toggleable/Tab has Modifier.testTag(AmpTag…).
//  - Secondary actions are real buttons (row overflow opens a menu of real buttons), never
//    long-press-only or custom accessibility actions (device.md:127, :134).
//  - Tabs and toggles set selected/checked semantics; section headers set heading().
package amp.design

import amp.model.*

object AmpTag {
    object Shell {
        const val root = "app-root"
        fun slot(role: String) = "slot-$role"
        /** layout.md: every pane root carries pane-<kind> (pane-thread, pane-changes, …); machine panes use their tab key. */
        fun pane(kind: String) = "pane-$kind"
        /** layout.md: the window bar's arrangement menu items carry arrangement-<name> and report selected. */
        fun arrangement(name: String) = "arrangement-${name.lowercase()}"
        const val windowArrangement = "window-arrangement"
        const val windowPalette = "window-palette"
        const val windowExitFocus = "window-exit-focus"     // Focus only
        const val webOpenInBrowser = "web-open-in-browser"   // account and portal page frames
        fun nav(item: String) = "nav-$item"                // prototype: nav-thread, nav-terminal
        const val divider = "divider"
        const val linkBanner = "link-banner"               // Link.Reconnecting / Offline / drift seen
        const val accountSwitcher = "account-switcher"
    }
    object Sidebar {
        const val list = "thread-list"
        const val newThread = "new-thread"
        const val search = "thread-search"
        fun section(name: String) = "sidebar-section-$name"            // pinned, project-<id>, inactive, snoozed, archived
        fun row(id: ThreadId) = "thread-row-${id.raw}"
        fun rowAction(id: ThreadId, action: String) = "thread-row-${id.raw}-$action"  // more, archive, pin, snooze…
        const val archiveInactive = "inactive-archive-all"
    }
    object Thread {
        fun pane(id: ThreadId) = "thread-pane-${id.raw}"
        const val title = "thread-title"
        const val menu = "thread-menu"
        const val connectLive = "orb-connect-live"          // Link.OrbPaused
        fun menuItem(name: String) = "thread-menu-$name"
        const val share = "thread-share"
        const val transcript = "transcript"
        fun turn(key: TurnKey) = "turn-${key.anchor.raw}"
        fun step(key: StepKey) = "step-${key.raw}"
        fun toolCard(id: ToolCallId) = "tool-card-${id.raw}"           // verification.md naming
        fun toolToggle(id: ToolCallId) = "tool-card-${id.raw}-toggle"
        const val loadEarlier = "transcript-load-earlier"
        const val jumpToLatest = "transcript-jump-latest"
        const val queue = "queue-strip"
        fun queued(id: QueuedMessageId) = "queued-${id.raw}"
        fun queuedAction(id: QueuedMessageId, action: String) = "queued-${id.raw}-$action"  // steer, remove, up, down
    }
    object Gate {
        const val dock = "gate-dock"
        fun gate(id: GateId) = "gate-${id.raw}"
        fun action(id: GateId, action: String) = "gate-${id.raw}-$action"   // approve, deny, submit, option-<n>
        fun field(id: GateId, key: String) = "gate-${id.raw}-field-$key"
    }
    object Composer {
        const val input = "composer-input"
        fun action(kind: ComposerActionKind) = "composer-${kind.name.lowercase()}"
        const val attach = "composer-attach"
        const val mic = "composer-mic"
        const val mode = "composer-mode"
        fun outbox(id: MessageId) = "outbox-${id.raw}"
    }
    object Machine {
        fun tab(key: String) = "tab-$key"                    // prototype: tab-terminal, tab-changes
        fun file(path: String) = "changes-file-${path.hashCode().toUInt()}"
        fun hunk(path: String, index: Int) = "hunk-${path.hashCode().toUInt()}-$index"
        fun changesAction(action: String) = "changes-action-$action"   // ship, review, restack, …
        fun terminalRow(row: Int) = "terminal-row-$row"
        const val terminalKeys = "terminal-softkeys"
        const val desktopSurface = "desktop-surface"
        fun fileNode(path: String) = "file-node-${path.hashCode().toUInt()}"
    }
}
