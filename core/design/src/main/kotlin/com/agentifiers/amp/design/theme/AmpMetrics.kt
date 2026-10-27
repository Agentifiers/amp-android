package com.agentifiers.amp.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Component sizes from the A2 density direction. Text containers
 * take these as minimum heights so a larger font scale grows them instead of clipping. [touch] is the hit area around
 * every control whose visual is smaller.
 *
 * [titleMin] is the room a pane title keeps before its header's controls move into More Actions. screens.md asks for at
 * least 120dp; 120dp shows about 14 characters of a title, fewer than the 17 that UX-11 reports as too few, so the floor
 * is 160dp, about 20 characters.
 *
 * [sidebarSheet] is the Sidebar's width when a rail opens it over the panes, one inner-display panel.
 * [navIndicatorWidth] by [navIndicatorHeight] is the pill behind the selected cover nav icon (A2 table, B).
 *
 * [readingMeasure] caps a transcript wider than one panel at about 75 characters of body text.
 *
 * [accountSwitcher] is the switcher's width where no sidebar sets it, about a TriFold sidebar's.
 *
 * [paneGap] is the web's `--app-pane-card-gap`, the canvas gutter drawn over each crease between panes.
 */
@Immutable
data class AmpMetrics(
    val windowBar: Dp = 40.dp,
    val readingMeasure: Dp = 600.dp,
    val paneHeader: Dp = 48.dp,
    val headerBaseline: Dp = 21.dp,
    val row: Dp = 52.dp,
    val section: Dp = 32.dp,
    val search: Dp = 36.dp,
    val field: Dp = 40.dp,
    val tool: Dp = 32.dp,
    val toolChild: Dp = 28.dp,
    val tab: Dp = 40.dp,
    val button: Dp = 32.dp,
    val touch: Dp = 48.dp,
    val iconButton: Dp = 32.dp,
    val icon: Dp = 18.dp,
    val iconSmall: Dp = 14.dp,
    val avatar: Dp = 28.dp,
    val composer: Dp = 48.dp,
    val chip: Dp = 24.dp,
    val navBar: Dp = 56.dp,
    val fileRow: Dp = 36.dp,
    val actionBar: Dp = 48.dp,
    val coverTopBar: Dp = 48.dp,
    val dot: Dp = 8.dp,
    val tabIndicator: Dp = 2.dp,
    val rowTrailingButton: Dp = 28.dp,
    val chipDot: Dp = 6.dp,
    val subRow: Dp = 40.dp,
    val divider: Dp = 1.dp,
    val paneGap: Dp = 2.dp,
    val gripLength: Dp = 16.dp,
    val gripThickness: Dp = 3.dp,
    val titleMin: Dp = 160.dp,
    val sidebarSheet: Dp = 335.dp,
    val navIndicatorWidth: Dp = 56.dp,
    val navIndicatorHeight: Dp = 28.dp,
    val accountSwitcher: Dp = 320.dp,
)

/**
 * The 4dp scale ([xs] to [xxl]) for gaps that have no role, then A2's spacing table (column A) and the constants every
 * direction shares, named by where they apply.
 */
@Immutable
data class AmpSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val paneX: Dp = 12.dp,
    val listInset: Dp = 4.dp,
    val rowPadX: Dp = 8.dp,
    val rowGap: Dp = 0.dp,
    val sectionTop: Dp = 8.dp,
    val sectionBottom: Dp = 6.dp,
    val blockGap: Dp = 8.dp,
    val toolGap: Dp = 0.dp,
    val toolPadX: Dp = 4.dp,
    val toolInnerGap: Dp = 8.dp,
    val childIndent: Dp = 22.dp,
    val composerMargin: Dp = 8.dp,
    val composerPadX: Dp = 4.dp,
    val composerTextInset: Dp = 6.dp,
    val composerIconGap: Dp = 2.dp,
    val headerIconGap: Dp = 4.dp,
    val buttonPadX: Dp = 12.dp,
    val actionGap: Dp = 8.dp,
    val userPadX: Dp = 12.dp,
    val userPadY: Dp = 8.dp,
    val userLabelGap: Dp = 4.dp,
    val searchMarginY: Dp = 4.dp,
    val searchPadX: Dp = 12.dp,
    val searchIconGap: Dp = 8.dp,
    val fieldPadX: Dp = 12.dp,
    val fieldPadY: Dp = 8.dp,
    val fieldLabelGap: Dp = 4.dp,
    val rowDotGap: Dp = 10.dp,
    val rowTitleMetaGap: Dp = 8.dp,
    val tabGap: Dp = 18.dp,
    val tabPadX: Dp = 2.dp,
    val chipPadX: Dp = 10.dp,
    val machineTop: Dp = 8.dp,
    val inlineCodePadX: Dp = 3.dp,
    val scrollFade: Dp = 20.dp,
)

/** A2's radius table (column A). Rows, the search field, buttons, and other controls share [control]. */
@Immutable
data class AmpShapes(
    val control: RoundedCornerShape = RoundedCornerShape(6.dp),
    val composer: RoundedCornerShape = RoundedCornerShape(24.dp),
    val chip: RoundedCornerShape = RoundedCornerShape(12.dp),
    val userMessage: RoundedCornerShape = RoundedCornerShape(8.dp),
    val inlineCode: RoundedCornerShape = RoundedCornerShape(4.dp),
)
