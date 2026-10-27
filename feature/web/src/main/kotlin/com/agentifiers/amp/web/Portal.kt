package com.agentifiers.amp.web

import com.agentifiers.amp.design.AmpTheme
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.WebTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpListRow
import com.agentifiers.amp.design.components.AmpSectionLabel
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.Portal
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.Portals
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What the Portal tab shows: the list, a login URL being minted, the portal page, or why it could not open. */
private sealed interface PortalView {
    data object Picking : PortalView
    data class Opening(val what: String) : PortalView
    data class Open(val portalUrl: String, val loginUrl: String) : PortalView
    data class Failed(val reason: String, val portalUrl: String?) : PortalView
}

/** Lapsed portal cookies re-mint at most this many times in a row before the tab gives up (a redirect loop). */
private const val MAX_RELOGINS = 3

/** The portal's own login hop (`.../__amp_auth/headless`, s13-portal.jsonl:5); reaching it is not a loaded portal page. */
private const val PORTAL_LOGIN_PATH = "/__amp_auth/"

/** The Portal machine tab: the executor's portal links, and a one-time login URL in the account's WebView profile. */
@Composable
fun PortalTab(dest: PaneDestination.Machine, tab: MachineTab.Portal, scope: AccountScope, modifier: Modifier = Modifier) {
    val portals = remember(scope, dest.thread) { scope.thread(dest.thread).machine.portals }
    val links by portals.list.collectAsStateWithLifecycle()
    val account by scope.account.collectAsStateWithLifecycle()
    val coroutines = rememberCoroutineScope()
    var view by remember(dest.thread) { mutableStateOf<PortalView>(PortalView.Picking) }
    var opening by remember { mutableStateOf<Job?>(null) }

    fun open(what: String, knownPortalUrl: String?, mint: suspend () -> Outcome<String>) {
        opening?.cancel()
        view = PortalView.Opening(what)
        opening = coroutines.launch {
            view = mint().fold(
                ok = { login -> PortalView.Open(knownPortalUrl ?: portalUrlOf(login), login) },
                failed = { reason -> PortalView.Failed(reason, knownPortalUrl) },
            )
        }
    }
    val openPort = { port: Int -> open("Opening port $port…", null) { portals.loginUrl(port) } }
    val openLink = { portal: Portal -> open("Opening ${portal.label}…", portal.url) { portals.loginUrlFor(portal.url) } }

    LaunchedEffect(dest.thread, tab.port) { tab.port?.let(openPort) }

    Column(modifier.fillMaxSize().background(LocalPaneSurface.current)) {
        when (val shown = view) {
            PortalView.Picking -> PortalPicker(links, openPort, openLink)
            is PortalView.Opening -> Text(
                shown.what,
                color = AmpTheme.colors.text.inkMuted,
                style = AmpTheme.type.secondary,
                modifier = Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.machineTop),
            )
            is PortalView.Failed -> PortalFailure(shown, onBack = { view = PortalView.Picking })
            is PortalView.Open -> key(shown) {
                PortalPage(shown, portals, account, scope, onClose = { view = PortalView.Picking })
            }
        }
    }
}

/** A port's login URL carries the portal URL as `return_to` (s13-portal.jsonl:5); its own origin is the fallback. */
private fun portalUrlOf(loginUrl: String): String {
    val login = Uri.parse(loginUrl)
    return login.getQueryParameter("return_to") ?: "${login.scheme}://${login.encodedAuthority}/"
}

private fun hostAndPath(url: String): String = Uri.parse(url).let { it.host.orEmpty() + it.path.orEmpty() }

@Composable
private fun PortalPicker(links: PersistentList<Portal>, openPort: (Int) -> Unit, openLink: (Portal) -> Unit) {
    var portText by rememberSaveable { mutableStateOf("") }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    val spacing = AmpTheme.spacing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = spacing.machineTop)) {
        Row(
            Modifier.padding(horizontal = spacing.paneX),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.actionGap),
        ) {
            AmpTextField(
                value = portText,
                onValueChange = { text -> portText = text.filter(Char::isDigit).take(5) },
                testTag = WebTags.portalPortInput,
                modifier = Modifier.weight(1f),
                placeholder = "Port",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            AmpButton("Open", { port?.let(openPort) }, WebTags.portalOpen, rank = AmpButtonRank.Primary, enabled = port != null)
        }
        if (links.isEmpty()) {
            AmpEmptyState(
                AmpIcons.Globe,
                "No portals",
                "Open a port to view a server running on this machine. Links in the executor's .amp/portals show here.",
                WebTags.portalEmpty,
                Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.padding(horizontal = spacing.listInset, vertical = spacing.blockGap)) {
            links.filter { it.folder == null }.forEach { PortalRow(it, openLink) }
            links.filter { it.folder != null }.groupBy { it.folder.orEmpty() }.forEach { (folder, inFolder) ->
                AmpSectionLabel(folder, WebTags.portalFolder(folder))
                inFolder.forEach { PortalRow(it, openLink) }
            }
        }
    }
}

@Composable
private fun PortalRow(portal: Portal, openLink: (Portal) -> Unit) {
    AmpListRow(
        title = portal.label,
        onClick = { openLink(portal) },
        testTag = WebTags.portalItem(portal),
        secondLine = {
            Column(Modifier.weight(1f)) {
                portal.note?.let { Text(it, style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(hostAndPath(portal.url), style = AmpTheme.type.monoSmall, color = AmpTheme.colors.text.inkMuted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        },
    )
}

@Composable
private fun PortalFailure(failed: PortalView.Failed, onBack: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AmpEmptyState(AmpIcons.CircleAlert, "Portal unavailable", failed.reason, WebTags.portalError) {
            Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.actionGap)) {
                AmpButton("Back to Portals", onBack, WebTags.portalBack)
                failed.portalUrl?.let { url ->
                    AmpButton("Open in browser", { openCustomTab(context, url, ephemeral = false) }, WebTags.openInBrowser, rank = AmpButtonRank.Link)
                }
            }
        }
    }
}

@Composable
private fun PortalPage(open: PortalView.Open, portals: Portals, account: Account, scope: AccountScope, onClose: () -> Unit) {
    val context = LocalContext.current
    val prompts = remember { WebPrompts() }
    // The login URL is served by the portal itself, so its host is the portal's; a manifest URL may name a host the
    // server fronts elsewhere (the fake serves portals under its own origin).
    val portalHost = remember(open) { Uri.parse(open.loginUrl).host }
    val authorize = account.server.ampBaseUrl.trimEnd('/') + "/api/portal-auth/authorize"
    var unusedLogin by remember { mutableStateOf<String?>(open.loginUrl) }
    val relogins = remember { Channel<String>(Channel.CONFLATED) }
    var reloginsInARow by remember { mutableIntStateOf(0) }
    var mintFailure by remember { mutableStateOf<String?>(null) }
    var showOptions by rememberSaveable { mutableStateOf(false) }

    val frame = rememberWebFrame(WebProfiles.profileFor(account.id), networked = true, prompts) { uri ->
        when {
            uri.toString().startsWith(authorize) -> {
                relogins.trySend(uri.getQueryParameter("return_to") ?: open.portalUrl)
                Navigation.Cancel
            }
            uri.host == portalHost -> Navigation.Load
            else -> Navigation.Browser(uri.toString())
        }
    }

    LaunchedEffect(frame) {
        val login = unusedLogin
        if (login != null) {
            unusedLogin = null
            frame.view.loadUrl(login)
        } else {
            relogins.trySend(open.portalUrl)
        }
        for (target in relogins) {
            if (++reloginsInARow > MAX_RELOGINS) {
                mintFailure = "The portal kept asking to sign in again. Open it in the browser instead."
                continue
            }
            mintFailure = portals.loginUrlFor(target).fold(
                ok = { fresh -> frame.view.loadUrl(fresh); null },
                failed = { it },
            )
        }
    }
    LaunchedEffect(frame.url) {
        val shown = frame.url?.let(Uri::parse) ?: return@LaunchedEffect
        if (shown.host == portalHost && !shown.path.orEmpty().contains(PORTAL_LOGIN_PATH)) reloginsInARow = 0
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.subRow).background(LocalPaneSurface.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showOptions) {
                Text(
                    "Portal Options",
                    style = AmpTheme.type.rowTitle,
                    color = AmpTheme.colors.text.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = AmpTheme.spacing.paneX),
                )
                AmpIconButton(AmpIcons.X, "Close", { showOptions = false }, WebTags.portalOptionsClose)
            } else {
                AmpIconButton(AmpIcons.ArrowLeft, "Back", { if (frame.canGoBack) frame.view.goBack() else onClose() }, WebTags.portalBack)
                AmpIconButton(
                    AmpIcons.RefreshCw,
                    "Reload",
                    {
                        reloginsInARow = 0
                        if (mintFailure != null || frame.url == null) relogins.trySend(open.portalUrl) else frame.view.reload()
                    },
                    WebTags.portalReload,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        frame.title.orEmpty(),
                        style = AmpTheme.type.rowTitle,
                        color = AmpTheme.colors.text.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(WebTags.portalTitle),
                    )
                    Text(
                        hostAndPath(frame.url ?: open.portalUrl),
                        style = AmpTheme.type.mono,
                        color = AmpTheme.colors.text.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(WebTags.portalAddress),
                    )
                }
                AmpIconButton(AmpIcons.ExternalLink, "Open in browser", { openCustomTab(context, open.portalUrl, ephemeral = false) }, WebTags.openInBrowser)
                AmpIconButton(AmpIcons.Settings, "Portal Options", { showOptions = true }, WebTags.portalOptions)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            WebFrameView(frame, Modifier.fillMaxSize().testTag(WebTags.portalPage))
            (mintFailure ?: frame.failure)?.let { reason ->
                Box(Modifier.matchParentSize().background(AmpTheme.colors.surfaces.canvas), contentAlignment = Alignment.TopCenter) {
                    AmpEmptyState(
                        AmpIcons.CircleAlert,
                        "Portal unavailable",
                        "$reason Open in browser lets the browser's Amp session sign in to the portal.",
                        WebTags.portalError,
                    )
                }
            }
            if (showOptions) {
                AccountPageView(
                    WebSurface.AccountPage(AccountWebPage.PortalPolicy, account.id, null),
                    scope,
                    onDone = { showOptions = false },
                    Modifier.matchParentSize(),
                )
            }
        }
        NoticeLine(prompts, Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs))
    }
    PromptDialog(prompts)
}
