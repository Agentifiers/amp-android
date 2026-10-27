package com.agentifiers.amp.design

/**
 * Arrangements, dividers, columns and pane window controls (docs/design/layout.md, "Test tags for layout"). The
 * window bar's own tags are in [ShellTags].
 */
object LayoutTags {
    /** [name] is the `:layout` arrangement name; items report `selected`. */
    fun arrangement(name: String) = "arrangement-${name.lowercase()}"

    /** [index] counts the window's draggable dividers from the left, from 0. */
    fun divider(index: Int) = "divider-$index"

    /** [role] is the `:layout` slot role, lowercase. Each column of the window carries one. */
    fun column(role: String) = "column-$role"

    /** [kind] names the pane, such as `sidebar`, `thread`, or `machine`. scripts/check-alignment.sh lines these up. */
    fun paneHeader(kind: String) = "pane-header-$kind"

    const val paneExpand = "pane-expand"
    const val paneCollapse = "pane-collapse"
    const val paneFocus = "pane-focus"
    const val paneNewWindow = "pane-new-window"
    const val paneMore = "pane-more"

    /** A pane control that moved into More carries its own tag plus `-menu`. */
    fun paneMenu(control: String) = "$control-menu"
}
