package com.agentifiers.amp.design

/**
 * Test tags, one `object <Area>Tags` per file and feature area. A feature unit edits only its own
 * area's file. Tags are kebab-case and name the thing, never its position, and keep their exact
 * strings once a flow uses them. The root sets `testTagsAsResourceId`, so each tag is the Android
 * resource-id that agent-device selects with `id=` (docs/design/verification.md).
 *
 * Shell holds the window-level chrome that no single feature owns.
 */
object ShellTags {
    const val root = "app-root"
    const val notificationsAsk = "notifications-ask"
    const val notificationsAskTurnOn = "notifications-ask-turn-on"
    const val notificationsAskNotNow = "notifications-ask-not-now"

    fun slot(role: String) = "slot-$role"

    /** [kind] is the `:layout` pane kind, lowercase, which this module cannot see. */
    fun pane(kind: String) = "pane-$kind"

    /** Compact bottom bar destinations and the Sidebar's Activity and Puck buttons. */
    fun nav(kind: String) = "nav-$kind"

    const val windowBar = "window-bar"
    const val windowArrangement = "window-arrangement"
    const val windowPalette = "window-palette"
    const val windowExitFocus = "window-exit-focus"

    const val palette = "palette"
    const val paletteInput = "palette-input"

    /** [commandId] is the plugin command's `id` from `command.list`. */
    fun paletteItem(commandId: String) = "palette-item-$commandId"

    const val linkBanner = "link-banner"
    const val orbConnectLive = "orb-connect-live"

    const val tabChanges = "tab-changes"
    const val tabFiles = "tab-files"
    const val tabTerminal = "tab-terminal"
    const val tabPortal = "tab-portal"
    const val tabDesktop = "tab-desktop"
    const val tabSpace = "tab-space"
    const val machineEmpty = "machine-empty"
    const val machineNoExecutor = "machine-no-executor"
    const val machineNewThread = "machine-new-thread"
    const val machineOrbWaking = "machine-orb-waking"
    const val machineOrbWakeFailed = "machine-orb-wake-failed"
    const val machineOrbRetry = "machine-orb-retry"
    const val machineOrbOpenWeb = "machine-orb-open-web"

    const val creaseOverlay = "crease-overlay"
    fun creaseHandle(index: Int) = "crease-handle-$index"
    fun creaseLabel(index: Int) = "crease-label-$index"
    fun creaseNudgeMinus(index: Int) = "crease-nudge-$index-minus"
    fun creaseNudgePlus(index: Int) = "crease-nudge-$index-plus"
}
