package com.agentifiers.amp.settings

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.LocalPaneCreases
import com.agentifiers.amp.design.SettingsTags
import com.agentifiers.amp.design.components.AmpHeaderAction
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpListRow
import com.agentifiers.amp.design.components.AmpPaneHeader
import com.agentifiers.amp.design.components.AmpSectionLabel
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.SettingsPage
import com.agentifiers.amp.sync.api.AccountScope

private class NativePage(val page: SettingsPage, val title: String, val content: @Composable (AccountScope, SettingsHost) -> Unit)

/**
 * The native pages in index order. Layout, Projects and Diagnostics have no native content in screens.md
 * that the app can back, so they are not listed; a destination naming one opens the index.
 */
private val nativePages: List<NativePage> = listOf(
    NativePage(SettingsPage.Accounts, "Accounts") { _, host -> AccountList(host, afterAction = null) },
    NativePage(SettingsPage.Notifications, "Notifications") { _, host -> NotificationsPage(host) },
    NativePage(SettingsPage.Dictation, "Composer & Dictation") { scope, host -> DictationPage(scope, host) },
    NativePage(SettingsPage.Runners, "Runners") { scope, _ -> RunnersPage(scope) },
    NativePage(SettingsPage.McpServers, "MCP Server Approvals") { scope, host -> McpServersPage(scope, host) },
    NativePage(SettingsPage.Secrets, "Secrets & Env Vars") { scope, host -> SecretsPage(scope, host) },
    NativePage(SettingsPage.Automations, "Automations") { scope, _ -> AutomationsPage(scope) },
    NativePage(SettingsPage.OAuthClients, "OAuth Clients") { scope, _ -> OAuthClientsPage(scope) },
    NativePage(SettingsPage.PortalHostnames, "Portal Hostnames") { scope, host -> PortalHostnamesPage(scope, host) },
    NativePage(SettingsPage.Usage, "Usage") { scope, host -> UsagePage(scope, host) },
    NativePage(SettingsPage.App, "App") { _, host -> AppPage(host) },
    NativePage(SettingsPage.About, "About") { _, host -> AboutPage(host) },
)

/** screens.md, "Settings in a web view", in its order. */
private val webPages: List<Pair<AccountWebPage, String>> = listOf(
    AccountWebPage.Account to "Account",
    AccountWebPage.Billing to "Billing",
    AccountWebPage.Passkeys to "Security & Passkeys",
    AccountWebPage.NotificationSettings to "Notification Preferences",
    AccountWebPage.ModeDial to "Mode Dial",
    AccountWebPage.ModelRouting to "Model Routing",
    AccountWebPage.Integrations to "Integrations & MCP",
    AccountWebPage.Plugins to "Plugins",
    AccountWebPage.Skills to "Skills",
    AccountWebPage.SavedPrompts to "Prompts",
    AccountWebPage.ProjectSettings to "Projects",
    AccountWebPage.Domains to "Domains",
    AccountWebPage.SecretsAndEnvVars to "Secrets & Env Vars",
    AccountWebPage.PuckSettings to "Puck",
    AccountWebPage.WorkspaceAdmin to "Workspace Settings",
)

/** The page whose writes this command makes, for the failure line on that page. */
private val AccountCommand.page: SettingsPage
    get() = when (this) {
        is AccountCommand.ShareRunner -> SettingsPage.Runners
        is AccountCommand.SetSecret -> SettingsPage.Secrets
        is AccountCommand.SetAutomationEnabled, is AccountCommand.DeleteAutomation -> SettingsPage.Automations
        is AccountCommand.SetShipBehavior, is AccountCommand.ClearOrbSnapshots -> SettingsPage.Projects
        is AccountCommand.EditVocabulary -> SettingsPage.Dictation
        is AccountCommand.CreateOAuthClient -> SettingsPage.OAuthClients
    }

/**
 * The index column's width when the index and the page sit side by side, or null when the pane is narrow and shows
 * one at a time. The index ends at the pane's first crease when one crosses it, so each gets its own panel.
 */
internal fun indexWidth(paneWidth: Dp, creases: List<Dp>): Dp? = when {
    paneWidth < 600.dp -> null
    else -> creases.firstOrNull { it > 0.dp && it < paneWidth } ?: 240.dp
}

/** Native settings: a page index with the ampcode.com pages after it, and the open page. */
@Composable
fun SettingsPane(dest: PaneDestination.Settings, scope: AccountScope, host: SettingsHost, modifier: Modifier = Modifier) {
    val open = nativePages.firstOrNull { it.page == dest.page }
    var failure by remember(scope, dest.page) { mutableStateOf<String?>(null) }
    LaunchedEffect(scope, dest.page) {
        scope.commandResults.collect { result ->
            if (result.command.page == dest.page) failure = result.outcome.failureText()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(LocalPaneSurface.current).testTag(ShellTags.pane("settings"))) {
        val close = AmpHeaderAction(AmpIcons.X, "Close", SettingsTags.settingsClose, SettingsTags.settingsCloseMenu, onClick = host::close)
        val split = indexWidth(maxWidth, LocalPaneCreases.current)
        if (split != null) {
            Row(Modifier.fillMaxSize()) {
                PageIndex(open?.page, host, host::openSettings, close.takeIf { open == null }, Modifier.width(split).fillMaxHeight())
                VerticalDivider(color = AmpTheme.colors.surfaces.line)
                if (open == null) {
                    Note("Choose a settings page.", Modifier.padding(start = AmpTheme.spacing.listInset, top = AmpTheme.spacing.md))
                } else {
                    PageBody(open, failure, scope, host, back = null, close, Modifier.weight(1f))
                }
            }
        } else {
            var showIndex by remember(dest.page) { mutableStateOf(false) }
            if (open == null || showIndex) {
                PageIndex(
                    open?.page,
                    host,
                    { page ->
                        showIndex = false
                        host.openSettings(page)
                    },
                    close,
                    Modifier.fillMaxSize(),
                )
            } else {
                PageBody(open, failure, scope, host, back = { showIndex = true }, close, Modifier.fillMaxSize())
            }
        }
    }
}

/** [close] is null beside an open page, whose header holds Close instead. */
@Composable
private fun PageIndex(selected: SettingsPage?, host: SettingsHost, choose: (SettingsPage) -> Unit, close: AmpHeaderAction?, modifier: Modifier) {
    Column(modifier) {
        AmpPaneHeader("Settings", SettingsTags.settingsIndexHeader, actions = listOfNotNull(close), kind = "settings")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AmpTheme.spacing.listInset)) {
            nativePages.forEach { native ->
                IndexRow(native.title, SettingsTags.settingsNav(native.page), native.page == selected) { choose(native.page) }
            }
            AmpSectionLabel("On ampcode.com", SettingsTags.settingsSection("On ampcode.com"))
            webPages.forEach { (page, title) ->
                IndexRow(title, SettingsTags.settingsWeb(page), selected = false) { host.openWeb(page) }
            }
        }
    }
}

@Composable
private fun IndexRow(title: String, testTag: String, selected: Boolean, onClick: () -> Unit) {
    AmpListRow(title, onClick, testTag, Modifier.semantics { this.selected = selected }, selected = selected)
}

/** [back] is set on narrow panes, where the index is a separate view; it opens the index. */
@Composable
private fun PageBody(
    open: NativePage,
    failure: String?,
    scope: AccountScope,
    host: SettingsHost,
    back: (() -> Unit)?,
    close: AmpHeaderAction,
    modifier: Modifier,
) {
    Column(modifier.testTag(SettingsTags.settingsPage(open.page))) {
        AmpPaneHeader(
            open.title,
            SettingsTags.settingsPageHeader,
            navigation = back?.let { { AmpIconButton(AmpIcons.ArrowLeft, "All Settings", it, SettingsTags.settingsIndex) } },
            actions = listOf(close),
            kind = "settings-page",
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = AmpTheme.spacing.listInset, end = AmpTheme.spacing.listInset, bottom = AmpTheme.spacing.lg)) {
            failure?.let { Text(it, color = AmpTheme.colors.status.danger, style = AmpTheme.type.secondary, modifier = Modifier.rowInset().padding(vertical = AmpTheme.spacing.sm).testTag(SettingsTags.settingsCommandError)) }
            open.content(scope, host)
        }
    }
}
