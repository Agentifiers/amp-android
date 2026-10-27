// :feature:web  package amp.web
// The only module allowed to import android.webkit.*, androidx.webkit.* or androidx.browser.*, and the
// only module where the literal "/_app/remote/" may appear (build-logic/ModuleGraphRules.kt). It may
// name that path to block it in WebView navigation; it never calls it, never injects scripts into
// ampcode.com pages, and never reads cookies from them.
package amp.web

import amp.model.*
import androidx.compose.runtime.Composable

/** Called from :app's PaneHost for PaneDestination.Web. */
@Composable
fun WebPane(dest: PaneDestination.Web, scope: amp.sync.api.AccountScope): Unit = TODO(
    """
    when (val s = dest.surface) {
      is WebSurface.McpApp      -> McpAppView(s)
      is WebSurface.AmpView     -> AmpViewView(s)
      is WebSurface.AccountPage -> AccountPageView(s)
    }
    """
)

/**
 * The Portal machine tab, called from :app's MachinePane. Portals.loginUrl(port) =
 * resolveThreadPortalURL✓ + mintPortalLoginURL✓ (one-time URL, spike S13 control plane OK, data plane
 * INCONCLUSIVE). WebView in the account's profile (WebProfiles), so portal cookies don't cross
 * accounts. Navigation outside *.onamp.dev / custom portal domains opens Custom Tabs. Toolbar: back,
 * reload, URL, [Open in browser], port picker, [Focus]. Fallback when the login URL 5xx's: Custom Tab
 * with the portal URL (the browser's ampcode session completes portal-auth).
 */
@Composable
fun PortalTab(dest: PaneDestination.Machine, tab: MachineTab.Portal, scope: amp.sync.api.AccountScope): Unit = TODO()

/**
 * MCP App (customize-enterprise.md:205-208): HTML from mcpAppReadResource✓ loaded into a sandboxed
 * WebView on a synthetic origin (WebViewAssetLoader domain, no file access, no ampcode cookies).
 * postMessage JSON-RPC bridge implements the MCP Apps host side; tools/call → mcpAppCallTool✓.
 * Ask First tools raise a native ToolApprovalGate-styled confirm before forwarding.
 */
@Composable fun McpAppView(s: WebSurface.McpApp): Unit = TODO()

/** amp-view prototypes: sandboxed WebView, JS on, network off except data: and blob:, height-capped card with [Expand]. */
@Composable fun AmpViewView(s: WebSurface.AmpView): Unit = TODO()

/**
 * Account pages (billing, workspace admin, notification settings, model routing, integrations,
 * project settings …), [Answer on web] for UnsupportedGate, and [Open on web] for Space and Desktop.
 * A WebView in the account's profile `amp-<accountId>`, never the browser's session, because the
 * browser has one ampcode.com session for every account. The pane header shows the account. The frame
 * always has [Open in browser] (`web-open-in-browser`), which warns "Your browser may be signed in to a
 * different Amp account." before moving the URL to a Custom Tab. browserOnly pages (passkeys) skip the
 * WebView and open that way directly. Leaving the page triggers a catalog refresh in :sync.
 */
@Composable fun AccountPageView(s: WebSurface.AccountPage): Unit = TODO()

/** One androidx.webkit Profile per account. SignOutCoordinator calls [delete] during sign-out. */
interface WebProfiles {
    fun profileFor(account: AccountId): String       // "amp-<accountId>"
    suspend fun delete(account: AccountId)
}
