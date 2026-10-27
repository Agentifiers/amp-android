package com.agentifiers.amp.design.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpHeaderAction
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.components.AmpPaneHeader
import com.agentifiers.amp.design.components.fitHeader
import com.agentifiers.amp.design.components.rememberTitleWidth
import com.agentifiers.amp.design.components.AmpHeaderRow
import com.agentifiers.amp.design.components.AmpInputBar
import com.agentifiers.amp.design.components.AmpListRow
import com.agentifiers.amp.design.components.AmpRowMore
import com.agentifiers.amp.design.components.AmpSearchField
import com.agentifiers.amp.design.components.AmpSecureTextField
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.design.components.AmpSectionLabel
import com.agentifiers.amp.design.components.AmpSuggestionChip
import com.agentifiers.amp.design.components.AmpStatusDot
import com.agentifiers.amp.design.components.AmpTab
import com.agentifiers.amp.design.components.AmpTabRow
import com.agentifiers.amp.design.icons.AmpIcons

/** The parts of the catalog `open-catalog` can show alone, so each fits one screenshot. */
enum class CatalogSection(val key: String) {
    Icons("icons"),
    Buttons("buttons"),
    Controls("controls"),
    Headers("headers"),
    Lists("lists"),
    Fields("fields"),
    ;

    companion object {
        fun parse(key: String): CatalogSection? = entries.firstOrNull { it.key == key }
    }
}

/** Every AmpIcons icon with its name and every control in every state, in a column [width] wide, as a pane would be. */
@Composable
fun AmpCatalog(width: Dp, section: CatalogSection?) {
    val colors = AmpTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.surfaces.canvas)
            .semantics { testTagsAsResourceId = true }
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.width(width).fillMaxHeight().background(colors.surfaces.pane).testTag("catalog")) {
            AmpPaneHeader(
                title = "Components",
                subtitle = "${width.value.toInt()}dp pane, ${section?.key ?: "all sections"}",
                testTag = "catalog-header",
            )
            HorizontalDivider(color = colors.surfaces.line)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(vertical = AmpTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xl),
            ) {
                CatalogSection.entries.filter { section == null || it == section }.forEach {
                    when (it) {
                        CatalogSection.Icons -> IconGrid()
                        CatalogSection.Buttons -> Buttons()
                        CatalogSection.Controls -> Controls()
                        CatalogSection.Headers -> Headers()
                        CatalogSection.Lists -> Lists()
                        CatalogSection.Fields -> Fields()
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = AmpTheme.type.label,
        color = AmpTheme.colors.text.inkMuted,
        modifier = Modifier.padding(horizontal = AmpTheme.spacing.lg, vertical = AmpTheme.spacing.xs),
    )
}

@Composable
private fun IconGrid() {
    Column {
        Heading("Icons, Lucide 1.61.0 (${AmpIcons.all.size})")
        BoxWithConstraints(Modifier.padding(horizontal = AmpTheme.spacing.sm)) {
            val columns = (maxWidth / 78.dp).toInt().coerceAtLeast(1)
            Column {
                AmpIcons.all.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (name, icon) -> IconCell(name, icon, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconCell(name: String, icon: ImageVector, modifier: Modifier) {
    Column(
        modifier.padding(vertical = AmpTheme.spacing.sm).testTag("catalog-icon-$name"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = AmpTheme.colors.text.ink, modifier = Modifier.size(AmpTheme.metrics.icon))
        Text(
            // A zero-width space after each hyphen lets a long name wrap between words.
            name.replace("-", "-\u200B"),
            style = AmpTheme.type.secondary,
            color = AmpTheme.colors.text.inkMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = AmpTheme.spacing.xs),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons() {
    Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.md)) {
        AmpButtonRank.entries.forEach { rank ->
            val key = rank.name.lowercase()
            Column {
                Heading(rank.name)
                FlowRow(
                    Modifier.padding(horizontal = AmpTheme.spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
                ) {
                    AmpButton("Enabled", {}, "catalog-button-$key-enabled", rank = rank)
                    AmpButton("With icon", {}, "catalog-button-$key-icon", rank = rank, leadingIcon = AmpIcons.Check)
                    AmpButton("Disabled", {}, "catalog-button-$key-disabled", rank = rank, enabled = false)
                    AmpButton("Loading", {}, "catalog-button-$key-loading", rank = rank, loading = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls() {
    var unread by remember { mutableStateOf(false) }
    var needsYou by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.md)) {
        Heading("Icon buttons: default, primary, accent tint, disabled")
        Row(Modifier.padding(horizontal = AmpTheme.spacing.sm)) {
            AmpIconButton(AmpIcons.RefreshCw, "Refresh", {}, "catalog-icon-button-refresh")
            AmpIconButton(AmpIcons.ArrowUp, "Send", {}, "catalog-icon-button-send", primary = true)
            AmpIconButton(AmpIcons.Focus, "Focus", {}, "catalog-icon-button-focus", tint = AmpTheme.colors.accents.accent)
            AmpIconButton(AmpIcons.PanelLeftClose, "Collapse", {}, "catalog-icon-button-collapse", enabled = false)
            AmpIconButton(AmpIcons.ArrowUp, "Send", {}, "catalog-icon-button-send-disabled", enabled = false, primary = true)
        }
        Heading("Chips: filter off, filter on, picker, icon, disabled")
        FlowRow(
            Modifier.padding(horizontal = AmpTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
        ) {
            AmpChip("Unread", unread, { unread = !unread }, "catalog-chip-unread")
            AmpChip("Needs you", needsYou, { needsYou = !needsYou }, "catalog-chip-needs-you")
            AmpChip("Medium", false, {}, "catalog-chip-mode", trailingIcon = AmpIcons.ChevronDown)
            AmpChip("Filters", false, {}, "catalog-chip-filters", leadingIcon = AmpIcons.ListFilter)
            AmpChip("Archived", false, {}, "catalog-chip-disabled", enabled = false)
            AmpChip("Pinned", true, {}, "catalog-chip-disabled-selected", enabled = false)
            AmpSuggestionChip(AnnotatedString("Add a test for the sort order"), {}, "catalog-suggestion-chip", leadingIcon = AmpIcons.ArrowUp)
        }
        Heading("Empty state")
        AmpEmptyState(
            icon = AmpIcons.MessageSquare,
            title = "No threads yet",
            guidance = "Start a thread and Amp gets to work on your machine.",
            testTag = "catalog-empty",
            modifier = Modifier.fillMaxWidth(),
            action = {
                AmpButton("New Thread", {}, "catalog-empty-action", rank = AmpButtonRank.Primary, leadingIcon = AmpIcons.SquarePen)
            },
        )
    }
}

private fun action(icon: ImageVector, label: String, element: String) =
    AmpHeaderAction(icon, label, testTag = "catalog-$element", menuTestTag = "catalog-menu-$element") {}

private class HeaderSample(
    val name: String,
    val tag: String,
    val title: String,
    val subtitle: String? = null,
    val back: Boolean = false,
    val actions: List<AmpHeaderAction> = emptyList(),
    val menu: List<AmpMenuItem> = emptyList(),
)

private val headerSamples = listOf(
    HeaderSample("Title only", "catalog-header-plain", "Settings"),
    HeaderSample(
        "Back, subtitle, two actions", "catalog-header-back-row", "Notifications", subtitle = "Settings", back = true,
        actions = listOf(action(AmpIcons.Search, "Search", "search"), action(AmpIcons.RefreshCw, "Refresh", "refresh")),
    ),
    HeaderSample(
        "Thread title bar, four actions and a menu", "catalog-header-thread",
        "Rename add to add_numbers and update every caller", subtitle = "Runner, working",
        actions = listOf(
            action(AmpIcons.Share2, "Share", "share"),
            action(AmpIcons.Joystick, "Multiplayer", "multiplayer"),
            action(AmpIcons.Focus, "Focus", "focus"),
            action(AmpIcons.AppWindow, "New window", "new-window"),
        ),
        menu = listOf(
            AmpMenuItem("Rename", "catalog-menu-rename", AmpIcons.SquarePen) {},
            AmpMenuItem("Archive", "catalog-menu-archive", AmpIcons.Archive) {},
        ),
    ),
    HeaderSample(
        "Machine pane, back and six actions", "catalog-header-machine", "Changes", subtitle = "3 files, uncommitted", back = true,
        actions = listOf(
            action(AmpIcons.Maximize2, "Expand", "expand"),
            action(AmpIcons.Focus, "Focus", "machine-focus"),
            action(AmpIcons.AppWindow, "New window", "machine-new-window"),
            action(AmpIcons.RefreshCw, "Refresh", "machine-refresh"),
            action(AmpIcons.Columns2, "Split diff", "split"),
            action(AmpIcons.TextWrap, "Wrap lines", "wrap"),
        ),
    ),
)

@Composable
private fun Headers() {
    Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.md)) {
        headerSamples.forEach { sample ->
            val titleWidth = rememberTitleWidth(sample.title, sample.subtitle)
            BoxWithConstraints {
                val fit = fitHeader(maxWidth, sample.actions.size, sample.back, sample.menu.isNotEmpty(), titleWidth)
                Column {
                    Heading("${sample.name}: ${fit.visible} of ${sample.actions.size} in the row${if (fit.more) ", More actions shown" else ""}")
                    AmpPaneHeader(
                        title = sample.title,
                        testTag = sample.tag,
                        modifier = Modifier.background(AmpTheme.colors.surfaces.raised),
                        subtitle = sample.subtitle,
                        navigation = if (sample.back) {
                            { AmpIconButton(AmpIcons.ArrowLeft, "Back", {}, "${sample.tag}-back") }
                        } else {
                            null
                        },
                        actions = sample.actions,
                        menu = sample.menu,
                    )
                }
            }
        }
    }
}

@Composable
private fun Lists() {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var draft by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var headerTab by remember { mutableIntStateOf(0) }
    val colors = AmpTheme.colors
    val tabs = listOf("Changes", "Files", "Terminal", "Portal", "Desktop").map { AmpTab(it, "catalog-tab-${it.lowercase()}") }
    Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.md)) {
        Heading("Search field, 36dp")
        AmpSearchField(query, { query = it }, "Search threads", "catalog-search", Modifier.padding(horizontal = AmpTheme.spacing.paneX))
        Heading("Section label and list rows, 52dp")
        Column(Modifier.padding(horizontal = AmpTheme.spacing.listInset)) {
            AmpSectionLabel("Needs you", "catalog-section", count = 1)
            AmpListRow(
                "omamp probe (safe to delete)", {}, "catalog-row-done", meta = "1m",
                leading = { AmpStatusDot(colors.status.done) },
                secondLine = {
                    Text("done", style = AmpTheme.type.meta, color = colors.status.done)
                    Text("deployments", style = AmpTheme.type.meta, color = colors.text.inkMuted)
                },
                trailing = {
                    AmpIconButton(AmpIcons.Check, "Mark done", {}, "catalog-row-check", glyph = AmpTheme.metrics.iconSmall)
                    AmpRowMore({}, "catalog-row-more")
                },
            )
            AmpSectionLabel("Deployments", "catalog-section-2", count = 2)
            AmpListRow(
                "Stale documentation cleanup", {}, "catalog-row-selected", selected = true, meta = "19m",
                leading = { AmpStatusDot(colors.status.working) },
                secondLine = { Text("working", style = AmpTheme.type.meta, color = colors.status.working) },
                trailing = { AmpRowMore({}, "catalog-row-selected-more") },
            )
            AmpListRow(
                "Pstack skills Amp port", {}, "catalog-row-plain", meta = "10h",
                leading = { AmpStatusDot(colors.status.working) },
                secondLine = { Text("working", style = AmpTheme.type.meta, color = colors.status.working) },
                trailing = { AmpRowMore({}, "catalog-row-plain-more") },
            )
        }
        Heading("Tab row, 40dp")
        AmpTabRow(tabs, tab, { tab = it })
        Heading("Tabs in a pane header, on the header baseline")
        AmpHeaderRow(Modifier.background(colors.surfaces.raised), "catalog-header-tabs", kind = null) {
            AmpTabRow(tabs.take(3), headerTab, { headerTab = it }, height = AmpTheme.metrics.paneHeader, labelBaseline = AmpTheme.metrics.headerBaseline)
        }
        Heading("Input bar, 48dp, radius 24")
        AmpInputBar(
            draft, { draft = it }, "Steer the agent", "catalog-input",
            Modifier.padding(horizontal = AmpTheme.spacing.composerMargin),
            leading = { AmpIconButton(AmpIcons.Paperclip, "Attach", {}, "catalog-input-attach") },
            trailing = { AmpIconButton(AmpIcons.Send, "Send", {}, "catalog-input-send", primary = true) },
        )
    }
}

@Composable
private fun Fields() {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("amp.example.com") }
    var port by remember { mutableStateOf("80a") }
    var note by remember { mutableStateOf("Rename add to add_numbers\nand update every caller") }
    val token = rememberTextFieldState("sgamp_user_0123456789")
    val inset = Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.paneX)
    Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.md)) {
        Heading("Text field, 40dp, radius 6: label, placeholder")
        AmpTextField(name, { name = it }, "catalog-field-name", inset, label = "Name", placeholder = "feature-branch", singleLine = true)
        Heading("Filled, supporting text")
        AmpTextField(
            url, { url = it }, "catalog-field-url", inset,
            label = "Server URL", supportingText = "Only the address is kept; any path is dropped.", singleLine = true,
        )
        Heading("Error text in danger")
        AmpTextField(
            port, { port = it }, "catalog-field-port", inset,
            label = "Port", errorText = "Enter a number from 1 to 65535.", errorTestTag = "catalog-field-port-error", singleLine = true,
        )
        Heading("Multi-line, grows with its text")
        AmpTextField(note, { note = it }, "catalog-field-note", inset, label = "Comment", minLines = 2)
        Heading("Leading and trailing slots, disabled")
        AmpTextField(
            "", {}, "catalog-field-filter", inset, placeholder = "Filter by label",
            singleLine = true,
            leading = { Icon(AmpIcons.ListFilter, contentDescription = null, tint = AmpTheme.colors.text.inkMuted, modifier = Modifier.size(AmpTheme.metrics.icon)) },
            trailing = { AmpButton("Clear", {}, "catalog-field-filter-clear", rank = AmpButtonRank.Tertiary) },
        )
        AmpTextField("main", {}, "catalog-field-disabled", inset, label = "Base branch", enabled = false, singleLine = true)
        Heading("Secure field")
        AmpSecureTextField(token, "catalog-field-token", inset, label = "Access token")
    }
}
