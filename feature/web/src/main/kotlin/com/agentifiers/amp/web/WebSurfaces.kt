package com.agentifiers.amp.web

import com.agentifiers.amp.design.AmpTheme
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.WebTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.coroutines.launch

/** MCP apps, amp-view blocks and account pages in their WebViews. [onDone] leaves the pane, as after a web sign-in. */
@Composable
fun WebPane(dest: PaneDestination.Web, scope: AccountScope, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(LocalPaneSurface.current).testTag(WebTags.pane)) {
        key(dest.surface) {
            when (val surface = dest.surface) {
                is WebSurface.McpApp -> McpAppView(surface, scope, Modifier.fillMaxSize())
                is WebSurface.AmpView -> AmpViewView(surface, Modifier.fillMaxWidth())
                is WebSurface.AccountPage -> AccountPageView(surface, scope, onDone, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * An ampcode.com page in the account's own profile. Its header names the account, so the user sees who will act,
 * and the page's real host, which the page cannot fake. The sign-in page calls [onDone] once ampcode.com leaves its
 * `/auth` routes, which means the profile has a session and the page it returned to is not what the user came for.
 */
@Composable
internal fun AccountPageView(surface: WebSurface.AccountPage, scope: AccountScope, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val account by scope.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val prompts = remember { WebPrompts() }
    val pageUrl = account.server.ampBaseUrl.trimEnd('/') + surface.page.path + surface.suffix.orEmpty()
    val openInBrowser = {
        coroutines.launch {
            if (prompts.confirm("Open in browser?", "Your browser may be signed in to a different Amp account.", "Open")) {
                openCustomTab(context, pageUrl, ephemeral = false)
            }
        }
        Unit
    }

    Column(modifier.background(LocalPaneSurface.current)) {
        when {
            surface.account != account.id ->
                Text("This page belongs to another signed-in account. Open it from a window on that account.", color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(AmpTheme.spacing.lg))
            surface.page.browserOnly -> {
                var opened by rememberSaveable(pageUrl) { mutableStateOf(false) }
                LaunchedEffect(pageUrl) {
                    if (!opened) {
                        opened = true
                        openCustomTab(context, pageUrl, ephemeral = false)
                    }
                }
                AccountHeader(account.email, Uri.parse(pageUrl).host.orEmpty(), openInBrowser)
                Text("This page works only in your browser, so it opened there.", color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(AmpTheme.spacing.lg))
            }
            else -> {
                val frame = rememberWebFrame(WebProfiles.profileFor(account.id), networked = true, prompts) { Navigation.Load }
                LaunchedEffect(frame) { frame.view.loadUrl(pageUrl) }
                if (surface.page == AccountWebPage.SignIn) {
                    LaunchedEffect(frame.url) {
                        val shown = frame.url?.let(Uri::parse) ?: return@LaunchedEffect
                        if (shown.host == Uri.parse(pageUrl).host && !shown.path.orEmpty().startsWith("/auth")) onDone()
                    }
                }
                AccountHeader(account.email, Uri.parse(frame.url ?: pageUrl).host.orEmpty(), openInBrowser)
                WebFrameView(frame, Modifier.fillMaxWidth().weight(1f))
            }
        }
        NoticeLine(prompts, Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs))
    }
    PromptDialog(prompts)
}

@Composable
private fun AccountHeader(email: String, host: String, openInBrowser: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.subRow).background(LocalPaneSurface.current).padding(start = AmpTheme.spacing.paneX, end = AmpTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(email, style = AmpTheme.type.rowTitle, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host, style = AmpTheme.type.mono, color = AmpTheme.colors.text.inkMuted, maxLines = 1, modifier = Modifier.testTag(WebTags.pageHost))
        }
        AmpButton("Open in browser", openInBrowser, WebTags.openInBrowser, rank = AmpButtonRank.Link)
    }
}

/** The web bundle's amp-view size cap (screens.md, "amp-view prototypes"). */
private const val AMP_VIEW_MAX_BYTES = 524_288
private const val AMP_VIEW_HOST = "amp-view.invalid"

/** An amp-view prototype: JavaScript on, every request but `data:` and `blob:` refused, no bridge. */
@Composable
internal fun AmpViewView(surface: WebSurface.AmpView, modifier: Modifier = Modifier) {
    val html = surface.block.html
    val tooLarge = remember(html) { html.encodeToByteArray().size > AMP_VIEW_MAX_BYTES }
    var showSource by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(AmpTheme.spacing.md).border(AmpTheme.metrics.divider, AmpTheme.colors.surfaces.line).background(AmpTheme.colors.surfaces.pane)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text("Prototype", color = AmpTheme.colors.text.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!tooLarge) {
                AmpButton(if (showSource) "View Prototype" else "View Source", { showSource = !showSource }, WebTags.ampViewSource)
            }
        }
        if (tooLarge) {
            Text("Prototype too large to render", color = AmpTheme.colors.status.danger, modifier = Modifier.padding(horizontal = AmpTheme.spacing.md))
            HtmlSource(html, Modifier.fillMaxWidth().height(AMP_VIEW_HEIGHT))
        } else {
            val prompts = remember { WebPrompts() }
            val frame = rememberWebFrame(WebProfiles.AMP_VIEW, networked = false, prompts) { uri ->
                if (uri.host == AMP_VIEW_HOST) Navigation.Load else Navigation.Cancel
            }
            LaunchedEffect(frame) { frame.view.loadDataWithBaseURL("https://$AMP_VIEW_HOST/", html, "text/html", "utf-8", null) }
            Box(Modifier.fillMaxWidth().height(AMP_VIEW_HEIGHT)) {
                WebFrameView(frame, Modifier.fillMaxSize())
                if (showSource) HtmlSource(html, Modifier.matchParentSize().background(LocalPaneSurface.current))
            }
            NoticeLine(prompts, Modifier.padding(horizontal = AmpTheme.spacing.md, vertical = AmpTheme.spacing.xs))
            PromptDialog(prompts)
        }
    }
}

private val AMP_VIEW_HEIGHT = 480.dp

@Composable
private fun HtmlSource(html: String, modifier: Modifier) {
    SelectionContainer(modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Text(html, style = Mono, modifier = Modifier.padding(AmpTheme.spacing.md))
    }
}

internal inline fun <T, R> Outcome<T>.fold(ok: (T) -> R, failed: (String) -> R): R = when (this) {
    is Outcome.Ok -> ok(value)
    is Outcome.Failed -> failed(describe(error))
    Outcome.Unknown -> failed("Amp didn't answer. Try again.")
    is Outcome.Refused -> failed(why)
}

internal fun describe(error: EngineError): String = when (error) {
    EngineError.Offline -> "You're offline."
    EngineError.Unauthorized -> "Amp no longer accepts this account's sign-in."
    EngineError.StepUpRequired -> "Amp needs a passkey check before this."
    EngineError.PaymentRequired -> "This needs more Amp credits."
    is EngineError.NotFound -> "Amp couldn't find ${error.what}."
    is EngineError.Server -> error.message.ifBlank { "Amp answered ${error.code}." }
    is EngineError.Drift -> "Amp answered in a form this app doesn't understand."
    else -> error.toString()
}
