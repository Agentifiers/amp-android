package com.agentifiers.amp.settings

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.SettingsTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpListRow
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.components.AmpSecureTextField
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AccountResource
import com.agentifiers.amp.model.McpApprovalOwner
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.model.AutomationInfo
import com.agentifiers.amp.model.LockAfter
import com.agentifiers.amp.model.NotificationAccess
import com.agentifiers.amp.model.RunnerInfo
import com.agentifiers.amp.model.SecretTarget
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.CreditsGlance
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.toJavaInstant
import kotlinx.collections.immutable.persistentListOf

private const val SERVICE_STATUS_URL = "https://ampcodestatus.com"

/** Amp's docs links Amp's Minimal Data Retention page at this path. */
private const val DATA_RETENTION_URL = "https://ampcode.com/docs/enterprise/minimal-data-retention"

private const val DATA_RETENTION_TEXT =
    "When Amp sends a request through its own model provider connections on Amp credits, Minimal Data Retention " +
        "limits how long the provider keeps your data, with some exceptions. It does not change how Amp stores your " +
        "threads. Connections you add with your own key follow that provider's terms."

private const val RUNNER_COMMAND = "amp --no-tui"

@Composable
private fun WebLink(page: AccountWebPage, label: String, host: AccountsHost) {
    AmpButton(label, { host.openWeb(page) }, SettingsTags.settingsWeb(page), Modifier.rowInset(), rank = AmpButtonRank.Link)
}

/** A switch row: tapping anywhere on it flips [checked]. [modifier] carries the tag flows toggle. */
@Composable
private fun ToggleRow(title: String, detail: String?, checked: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = AmpTheme.metrics.row)
            .clip(AmpTheme.shapes.control)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = AmpTheme.spacing.rowPadX),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.rowTitleMetaGap),
    ) {
        Column(Modifier.weight(1f).padding(vertical = AmpTheme.spacing.xs)) {
            Text(title, color = AmpTheme.colors.text.ink, style = AmpTheme.type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A row that only shows something: AmpListRow's geometry without its tap target, which would announce an action that
 * does nothing. [details] are its second lines.
 */
@Composable
private fun InfoRow(
    title: String,
    titleStyle: TextStyle = AmpTheme.type.rowTitle,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.row).padding(start = AmpTheme.spacing.rowPadX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = AmpTheme.spacing.xs)) {
            Text(title, color = AmpTheme.colors.text.ink, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            details()
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun Detail(text: String, style: TextStyle = AmpTheme.type.meta) {
    Text(text, color = AmpTheme.colors.text.inkMuted, style = style)
}

@Composable
internal fun NotificationsPage(host: SettingsHost) {
    val access by host.notifications.collectAsStateWithLifecycle()
    Heading("On this device")
    val state = when (access) {
        is NotificationAccess.Allowed -> "Amp can post notifications on this device."
        NotificationAccess.FirstAsk, NotificationAccess.AskAgain -> "Notifications are off. Amp cannot tell you when a thread needs you or finishes."
        NotificationAccess.SettingsOnly -> "Notifications are off for Amp in Android settings. Turn On opens them."
    }
    Text(state, color = AmpTheme.colors.text.ink, style = AmpTheme.type.body, modifier = Modifier.rowInset().testTag(SettingsTags.notificationsState))
    if (access !is NotificationAccess.Allowed) {
        AmpButton("Turn On", host::turnOnNotifications, SettingsTags.notificationsTurnOn, Modifier.rowInset(), rank = AmpButtonRank.Primary)
    }
    (access as? NotificationAccess.Allowed)?.channelsOff?.forEach { channel ->
        Text("$channel notifications are off.", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary, modifier = Modifier.rowInset().testTag(SettingsTags.notificationsChannelOff(channel)))
    }
    Note("Which notifications Amp may post, and how each kind sounds, are Android settings.")
    AmpButton("Android Notification Settings", host::openNotificationSettings, SettingsTags.notificationsSystem, Modifier.rowInset(), rank = AmpButtonRank.Link)
    val elsewhere by host.notifyWhenOpenElsewhere.collectAsStateWithLifecycle()
    ToggleRow(
        title = "Notify even when a thread is open elsewhere",
        detail = "Amp sends no push while a thread is open in a browser, so this phone checks for itself, a few minutes late or longer while it sleeps.",
        checked = elsewhere,
        modifier = Modifier.testTag(SettingsTags.notificationsOpenElsewhere),
        onChange = host::setNotifyWhenOpenElsewhere,
    )
    Heading("Notification Preferences")
    Note("Choose Push, Email, and Slack for each kind of event on ampcode.com.")
    WebLink(AccountWebPage.NotificationSettings, "Notification Preferences", host)
    Heading("Watch")
    Note("Watching a thread shows its live progress for up to 30 minutes. After that, the app checks the thread from time to time.")
}

@Composable
internal fun DictationPage(scope: AccountScope, host: SettingsHost) {
    Heading("Custom vocabulary")
    Note("Names and terms dictation should recognize, such as project and library names.")
    Loaded(scope, AccountResource.DictationVocabulary) { words ->
        if (words.isEmpty()) Note("No custom words yet.")
        words.forEach { word ->
            InfoRow(word, trailing = {
                AmpButton(
                    "Remove",
                    { scope.dispatch(AccountCommand.EditVocabulary(add = persistentListOf(), remove = persistentListOf(word))) },
                    SettingsTags.vocabularyRemove(word),
                    rank = AmpButtonRank.Tertiary,
                )
            })
        }
        val newWord = rememberTextFieldState()
        val add = {
            val word = newWord.text.trim().toString()
            if (word.isNotEmpty() && word !in words) {
                scope.dispatch(AccountCommand.EditVocabulary(add = persistentListOf(word), remove = persistentListOf()))
            }
            newWord.clearText()
        }
        Row(Modifier.rowInset(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            AmpTextField(
                state = newWord,
                testTag = SettingsTags.vocabularyInput,
                modifier = Modifier.weight(1f),
                placeholder = "Word or phrase",
                lineLimits = TextFieldLineLimits.SingleLine,
                onKeyboardAction = { add() },
            )
            AmpButton("Add", add, SettingsTags.vocabularyAdd, rank = AmpButtonRank.Primary, enabled = newWord.text.isNotBlank())
        }
    }
    Heading("Composer")
    Note("Default Message Send Behavior and the other composer options live on ampcode.com.")
    WebLink(AccountWebPage.Composer, "Composer & Dictation on ampcode.com", host)
}

@Composable
internal fun RunnersPage(scope: AccountScope) {
    val context = LocalContext.current
    val runners by remember(scope) { scope.catalog.runners }.collectAsStateWithLifecycle()
    Heading("Your runners")
    Loaded(scope, AccountResource.RunnerShares) { shares ->
        if (runners.isEmpty()) Note("No runners are connected to this account.")
        runners.forEach { runner ->
            RunnerRows(runner, shared = shares.any { it.runner == runner.id }) { share ->
                scope.dispatch(AccountCommand.ShareRunner(runner.id, share))
            }
        }
    }
    Heading("Add a runner")
    Note("Android can't be a runner. On a computer with the Amp CLI, start one with:")
    InfoRow(RUNNER_COMMAND, titleStyle = AmpTheme.type.mono, trailing = {
        AmpIconButton(AmpIcons.Copy, "Copy", { context.copyText("Runner command", RUNNER_COMMAND) }, SettingsTags.runnerCopyCommand)
    })
    Note("To share a desktop, start the runner with --desktop, or turn on Share This Mac's Display in the Mac app.")
}

@Composable
private fun RunnerRows(runner: RunnerInfo, shared: Boolean, onShare: (Boolean) -> Unit) {
    InfoRow(runner.hostname) {
        Text(if (runner.online) "Online" else "Offline", color = if (runner.online) AmpTheme.colors.text.ink else AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta)
        runner.directories.forEach { Detail(it, AmpTheme.type.mono) }
    }
    ToggleRow(
        title = "Share with workspace",
        detail = "Workspace members can run threads on this machine as you.",
        checked = shared,
        modifier = Modifier.testTag(SettingsTags.runnerShare(runner.id)),
        onChange = onShare,
    )
}

@Composable
internal fun McpServersPage(scope: AccountScope, host: SettingsHost) {
    Heading("Approvals")
    Note("Repository and workspace MCP servers you allowed to run.")
    Loaded(scope, AccountResource.McpApprovals) { approvals ->
        if (approvals.isEmpty()) Note("No approvals.")
        approvals.forEach {
            InfoRow(it.serverName) { Detail("Approved for ${if (it.owner == McpApprovalOwner.User) "you" else "the workspace"}") }
        }
    }
    Note("To remove an approval, open Integrations & MCP on ampcode.com.")
    WebLink(AccountWebPage.Integrations, "Integrations & MCP", host)
    Heading("Extensions")
    Loaded(scope, AccountResource.Extensions) { glance ->
        ExtensionRow("MCP servers", glance.mcpServers)
        ExtensionRow("Plugin repositories", glance.pluginRepos)
        ExtensionRow("Skill repositories", glance.skillRepos)
    }
}

@Composable
private fun ExtensionRow(title: String, items: List<String>) {
    InfoRow(title) {
        if (items.isEmpty()) Detail("None")
        items.forEach { Detail(it, AmpTheme.type.mono) }
    }
}

@Composable
internal fun SecretsPage(scope: AccountScope, host: SettingsHost) {
    var editing by remember { mutableStateOf<String?>(null) }
    Heading("Personal")
    Note("Environment variables and secrets for your own orbs. Your values override project and workspace values.")
    Loaded(scope, AccountResource.Secrets(SecretTarget.Personal)) { entries ->
        if (entries.isEmpty()) Note("No personal secrets or variables.")
        entries.forEach { entry ->
            AmpListRow(
                title = entry.name,
                onClick = { editing = entry.name },
                testTag = SettingsTags.secretRow(entry.name),
                titleStyle = AmpTheme.type.mono,
                meta = if (entry.isSecret) "Secret" else "Variable",
            )
        }
        if (editing == null) {
            AmpButton("Add", { editing = "" }, SettingsTags.secretAdd, Modifier.rowInset(), rank = AmpButtonRank.Primary, leadingIcon = AmpIcons.Plus)
        }
    }
    editing?.let { name ->
        key(name) {
            SecretEditor(
                initialName = name,
                onSave = { savedName, value, secret ->
                    scope.dispatch(AccountCommand.SetSecret(SecretTarget.Personal, savedName, value, secret))
                    editing = null
                },
                onCancel = { editing = null },
            )
        }
    }
    Heading("Projects and workspace")
    WebLink(AccountWebPage.SecretsAndEnvVars, "Secrets & Env Vars on ampcode.com", host)
}

/** The window is FLAG_SECURE while this editor is composed (auth-and-security.md, "Secrets stay out of screenshots"). */
@Composable
private fun SecretEditor(initialName: String, onSave: (name: String, value: String, secret: Boolean) -> Unit, onCancel: () -> Unit) {
    SecureWindow()
    val name = rememberTextFieldState(initialName)
    val value = rememberTextFieldState()
    var secret by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(vertical = AmpTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
        Column(Modifier.rowInset(), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            AmpTextField(
                state = name,
                testTag = SettingsTags.secretName,
                modifier = Modifier.fillMaxWidth(),
                label = "Name",
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            )
            AmpSecureTextField(
                state = value,
                testTag = SettingsTags.secretValue,
                modifier = Modifier.fillMaxWidth(),
                label = "Value",
                obfuscation = if (secret) TextObfuscationMode.RevealLastTyped else TextObfuscationMode.Visible,
            )
        }
        ToggleRow(
            title = "Secret",
            detail = "Mark API keys, tokens, and passwords as secret.",
            checked = secret,
            modifier = Modifier.testTag(SettingsTags.secretIsSecret),
            onChange = { secret = it },
        )
        Row(Modifier.rowInset(), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            AmpButton(
                "Save",
                { onSave(name.text.trim().toString(), value.text.toString(), secret) },
                SettingsTags.secretSave,
                rank = AmpButtonRank.Primary,
                enabled = name.text.isNotBlank(),
            )
            AmpButton("Cancel", onCancel, SettingsTags.secretCancel)
        }
    }
}

private val nextRunFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
internal fun AutomationsPage(scope: AccountScope) {
    var deleting by remember { mutableStateOf<AutomationInfo?>(null) }
    Loaded(scope, AccountResource.Automations) { automations ->
        if (automations.isEmpty()) Note("No automations. Ask Amp in a thread to schedule work.")
        automations.forEach { automation ->
            val next = automation.nextRun?.let { "Next run: " + nextRunFormat.format(it.toJavaInstant().atZone(ZoneId.systemDefault())) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToggleRow(
                    title = automation.title,
                    detail = next ?: "No run scheduled",
                    checked = automation.enabled,
                    modifier = Modifier.weight(1f).testTag(SettingsTags.automationEnabled(automation.id)),
                    onChange = { scope.dispatch(AccountCommand.SetAutomationEnabled(automation.id, it)) },
                )
                RowMenu(
                    SettingsTags.automationMenu(automation.id),
                    "Automation actions",
                    listOf(AmpMenuItem("Delete", SettingsTags.automationDelete(automation.id)) { deleting = automation }),
                )
            }
        }
    }
    deleting?.let { automation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("Delete ${automation.title}?") },
            text = { Text("The thread's schedule stops. The thread stays.") },
            confirmButton = {
                AmpButton(
                    "Delete",
                    {
                        deleting = null
                        scope.dispatch(AccountCommand.DeleteAutomation(automation.id))
                    },
                    SettingsTags.automationDeleteConfirm,
                    rank = AmpButtonRank.Destructive,
                )
            },
            dismissButton = {
                AmpButton("Cancel", { deleting = null }, SettingsTags.automationDeleteCancel)
            },
        )
    }
}

@Composable
internal fun OAuthClientsPage(scope: AccountScope) {
    Loaded(scope, AccountResource.OAuthClients) { clients ->
        if (clients.isEmpty()) Note("No OAuth clients.")
        clients.forEach { client ->
            InfoRow(client.name) {
                Detail(client.clientId, AmpTheme.type.mono)
                if (client.scopes.isNotEmpty()) Detail(client.scopes.joinToString(", "))
            }
        }
    }
}

@Composable
internal fun PortalHostnamesPage(scope: AccountScope, host: SettingsHost) {
    Note("Your own domains that portals can use.")
    Loaded(scope, AccountResource.CustomDomains) { domains ->
        if (domains.isEmpty()) Note("No custom domains.")
        domains.forEach { InfoRow(it, titleStyle = AmpTheme.type.mono) }
    }
    WebLink(AccountWebPage.Domains, "Domains on ampcode.com", host)
}

@Composable
internal fun UsagePage(scope: AccountScope, host: SettingsHost) {
    val account by scope.account.collectAsStateWithLifecycle()
    val credits by remember(scope) { scope.catalog.credits }.collectAsStateWithLifecycle()
    Heading("Credits")
    when (val glance = credits) {
        CreditsGlance.Loading -> Note("Loading credits…", Modifier.testTag(SettingsTags.settingsLoading))
        is CreditsGlance.Known -> Column(Modifier.rowInset().testTag(SettingsTags.accountCredits(account.id))) {
            Text(remainingText(glance.remainingUsd), color = AmpTheme.colors.text.ink, style = AmpTheme.type.body)
            glance.workspaceQuota?.let { Text(it, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary) }
        }
        CreditsGlance.Unavailable -> Note("Amp didn't report a balance this app can read. See usage on ampcode.com.")
    }
    WebLink(AccountWebPage.Usage, "Usage on ampcode.com", host)
}

@Composable
internal fun AppPage(host: SettingsHost) {
    val lock by host.appLock.collectAsStateWithLifecycle()
    ToggleRow(
        title = "App Lock",
        detail = "Ask for your fingerprint or device PIN before Amp shows anything. Background work and notifications keep running.",
        checked = lock is AppLock.On,
        modifier = Modifier.testTag(SettingsTags.appLockToggle),
        onChange = { on -> host.setAppLock(if (on) AppLock.On(LockAfter.FiveMinutes) else AppLock.Off) },
    )
    (lock as? AppLock.On)?.let { on ->
        Heading("Lock after")
        LockAfter.entries.forEach { after ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = AmpTheme.metrics.row)
                    .clip(AmpTheme.shapes.control)
                    .selectable(selected = on.after == after, role = Role.RadioButton, onClick = { host.setAppLock(AppLock.On(after)) })
                    .testTag(SettingsTags.appLockAfter(after))
                    .padding(horizontal = AmpTheme.spacing.rowPadX),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.rowDotGap),
            ) {
                RadioButton(selected = on.after == after, onClick = null)
                Text(after.label, color = AmpTheme.colors.text.ink, style = AmpTheme.type.rowTitle)
            }
        }
    }
}

@Composable
internal fun AboutPage(host: SettingsHost) {
    Text("Amp for Android", color = AmpTheme.colors.text.ink, style = AmpTheme.type.headline, modifier = Modifier.rowInset().padding(top = AmpTheme.spacing.sm))
    Note("Version ${host.versionName}")
    AmpButton("Service Status", { host.openUrl(SERVICE_STATUS_URL) }, SettingsTags.serviceStatus, Modifier.rowInset(), rank = AmpButtonRank.Link)
    Heading("Data Retention")
    Text(DATA_RETENTION_TEXT, color = AmpTheme.colors.text.ink, style = AmpTheme.type.body, modifier = Modifier.rowInset().testTag(SettingsTags.dataRetention))
    AmpButton("Learn More", { host.openUrl(DATA_RETENTION_URL) }, SettingsTags.dataRetentionLearnMore, Modifier.rowInset(), rank = AmpButtonRank.Link)
}
