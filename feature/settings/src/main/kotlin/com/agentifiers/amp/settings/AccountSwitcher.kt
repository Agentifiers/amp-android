package com.agentifiers.amp.settings

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.SettingsTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpListRow
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.components.AmpPaneHeader
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.CredentialKind
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.SettingsPage
import com.agentifiers.amp.model.WorkspaceRef
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.CreditsGlance
import com.agentifiers.amp.sync.api.Loadable
import java.text.NumberFormat
import java.util.Locale

internal fun WorkspaceRef.label(): String = when (this) {
    WorkspaceRef.Personal -> "Personal"
    is WorkspaceRef.Team -> name
}

/** Null on ampcode.com, where every account would show the same host. */
internal fun ServerEnvironment.shownHost(): String? = host.takeUnless { it == ServerEnvironment.Production.host }

internal fun needsYouText(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 needs you"
    else -> "$count need you"
}

internal fun remainingText(usd: Double): String = "${NumberFormat.getCurrencyInstance(Locale.US).format(usd)} remaining"

/** The balance, then the workspace entitlement when one applies. Empty while loading or when Amp gave no balance. */
internal fun creditsLines(glance: CreditsGlance): List<String> = when (glance) {
    is CreditsGlance.Known -> listOfNotNull(remainingText(glance.remainingUsd), glance.workspaceQuota)
    CreditsGlance.Loading, CreditsGlance.Unavailable -> emptyList()
}

/** The content of the account sheet: every signed-in account, Add Account, Manage Account, and Settings. */
@Composable
fun AccountSwitcher(host: AccountsHost, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = AmpTheme.spacing.sm)) {
        AmpPaneHeader("Accounts", SettingsTags.accountSwitcherHeader)
        Column(Modifier.padding(horizontal = AmpTheme.spacing.listInset)) {
            AccountList(host, afterAction = host::dismiss)
            AmpButton(
                text = "Manage Account",
                onClick = {
                    host.openWeb(AccountWebPage.Account)
                    host.dismiss()
                },
                testTag = SettingsTags.accountManage,
                modifier = Modifier.rowInset(),
                rank = AmpButtonRank.Link,
            )
            AmpButton(
                text = "Settings",
                onClick = {
                    host.openSettings(SettingsPage.Accounts)
                    host.dismiss()
                },
                testTag = SettingsTags.accountSettings,
                modifier = Modifier.rowInset(),
                rank = AmpButtonRank.Link,
            )
        }
    }
}

/**
 * The roster with per-account sign-out and Add Account, shared by the switcher and Settings, Accounts.
 * [afterAction] closes the switcher's sheet; Settings has nothing to close.
 */
@Composable
internal fun AccountList(host: AccountsHost, afterAction: (() -> Unit)?) {
    val roster by host.roster.collectAsStateWithLifecycle()
    var signingOut by remember { mutableStateOf<Account?>(null) }
    when (val loaded = roster) {
        Loadable.Loading -> Note("Loading accounts…", Modifier.testTag(SettingsTags.settingsLoading))
        is Loadable.Failed -> Text(loaded.error.text(), color = AmpTheme.colors.status.danger, style = AmpTheme.type.secondary, modifier = Modifier.rowInset().testTag(SettingsTags.settingsLoadError))
        is Loadable.Ready -> {
            loaded.value.accounts.forEach { account ->
                AccountRow(
                    account = account,
                    scope = remember(host, account.id) { host.scopeOf(account.id) },
                    current = account.id == host.current,
                    onSwitch = {
                        host.switchTo(account.id)
                        afterAction?.invoke()
                    },
                    onSignOut = { signingOut = account },
                )
            }
            val full = loaded.value.isFull
            AmpButton(
                text = "Add Account",
                onClick = host::addAccount,
                testTag = SettingsTags.accountAdd,
                modifier = Modifier.rowInset(),
                rank = AmpButtonRank.Primary,
                enabled = !full,
            )
            if (full) Note(ACCOUNT_LIMIT_TEXT, Modifier.testTag(SettingsTags.accountLimit))
        }
    }
    signingOut?.let { account ->
        SignOutDialog(
            account = account,
            onConfirm = {
                signingOut = null
                host.signOut(account.id)
            },
            onActiveSessions = { host.openWeb(AccountWebPage.Passkeys) },
            onDismiss = { signingOut = null },
        )
    }
}

@Composable
private fun AccountRow(account: Account, scope: AccountScope, current: Boolean, onSwitch: () -> Unit, onSignOut: () -> Unit) {
    val colors = AmpTheme.colors
    val sidebar by remember(scope) { scope.fleet.sidebar }.collectAsStateWithLifecycle()
    val credits by remember(scope) { scope.catalog.credits }.collectAsStateWithLifecycle()
    AmpListRow(
        title = account.email,
        onClick = onSwitch,
        testTag = SettingsTags.accountRow(account.id),
        modifier = Modifier.semantics { selected = current },
        selected = current,
        meta = "This window".takeIf { current },
        leading = {
            Box(Modifier.size(AmpTheme.metrics.avatar).clip(CircleShape).background(colors.accents.brandMark), contentAlignment = Alignment.Center) {
                Text(account.email.take(1).uppercase(), color = colors.surfaces.canvas, style = AmpTheme.type.button, fontWeight = FontWeight.Bold)
            }
        },
        secondLine = {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.rowTitleMetaGap)) {
                    Text(
                        listOfNotNull(account.workspace.label(), account.server.shownHost()).joinToString(" · "),
                        Modifier.weight(1f, fill = false),
                        color = colors.text.inkMuted,
                        style = AmpTheme.type.meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    needsYouText(sidebar.needsYou.size)?.let {
                        Text(it, color = colors.status.needsYou, style = AmpTheme.type.meta, maxLines = 1, modifier = Modifier.testTag(SettingsTags.accountNeedsYou(account.id)))
                    }
                    if (account.auth == AuthHealth.NeedsSignIn) Text("Needs sign-in", color = colors.status.danger, style = AmpTheme.type.meta, maxLines = 1)
                }
                val lines = creditsLines(credits)
                if (lines.isNotEmpty()) {
                    Column(Modifier.testTag(SettingsTags.accountCredits(account.id))) {
                        lines.forEach { Text(it, color = colors.text.inkMuted, style = AmpTheme.type.meta) }
                    }
                }
            }
        },
        trailing = {
            RowMenu(
                SettingsTags.accountMenu(account.id),
                "Account actions",
                listOf(AmpMenuItem("Sign Out", SettingsTags.accountSignOut(account.id), AmpIcons.LogOut, onClick = onSignOut)),
            )
        },
    )
}

/** States what outlives sign-out (auth-and-security.md, "Sign-out ends the session and wipes the account"). */
@Composable
private fun SignOutDialog(account: Account, onConfirm: () -> Unit, onActiveSessions: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("Sign out of ${account.email}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                Text("This removes the account and everything Amp stored for it from this device.")
                when (account.credential) {
                    CredentialKind.AccessToken ->
                        Text("This token still works until you revoke it in Settings, Security, Access Token on ampcode.com.")
                    CredentialKind.OAuth -> {
                        Text("The browser that approved this sign-in stays signed in to ampcode.com, unless you used a private tab.")
                        Text("Amp may keep this session alive until it expires.")
                        AmpButton("Active Sessions", onActiveSessions, SettingsTags.accountActiveSessions, rank = AmpButtonRank.Link)
                    }
                }
            }
        },
        confirmButton = {
            AmpButton("Sign Out", onConfirm, SettingsTags.accountSignOutConfirm, rank = AmpButtonRank.Destructive)
        },
        dismissButton = {
            AmpButton("Cancel", onDismiss, SettingsTags.accountSignOutCancel)
        },
    )
}
