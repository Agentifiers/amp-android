package com.agentifiers.amp.web

import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.OrbProviderListing
import com.agentifiers.amp.model.OrbProviderSource
import com.agentifiers.amp.sync.api.OrbProviderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

private const val BRIDGE_OBJECT = "ampOrbProviders"
private val READ_TIMEOUT = 20.seconds

/**
 * The page the reader runs on the account's origin. It fetches the web app's page data the way SvelteKit's client
 * does for `/` (web chunk `bd6913a5.js`, `function Ei`): `/__data.json` with the trailing-slash flag and the node mask
 * `110`, the root layout and the `/(app)` layout, whose data the composer reads as `orbProviderOptions` and `user`
 * (`aa7cdc32.js`, `l.data.orbProviderOptions`; the root layout reads `data.user`, `nodes_0.d73cc137.js`). Either node
 * may carry either key. It posts back only each option's `configID`, `name`, `isDefault`, and `owner` slots, and
 * `signedIn` when some node has a `user` key; other page data never leaves the page. An HTTP redirect, such as to a
 * sign-in page, posts as a `redirect`.
 */
private val READER_PAGE = """
<!doctype html><meta charset="utf-8"><script>
(async () => {
  const post = (reply) => $BRIDGE_OBJECT.postMessage(JSON.stringify(reply));
  let response;
  try {
    response = await fetch("/__data.json?x-sveltekit-trailing-slash=1&x-sveltekit-invalidated=110");
  } catch (error) {
    return post({ error: String((error && error.message) || error) });
  }
  if (!response.ok) return post({ status: response.status });
  if (response.redirected) return post({ status: response.status, type: "redirect" });
  let page;
  try {
    page = JSON.parse((await response.text()).split("\n").find((l) => l.trim()));
  } catch (error) {
    return post({ status: response.status, type: "unreadable" });
  }
  if (page.type !== "data") return post({ status: response.status, type: page.type });
  const nodes = (page.nodes || []).filter((node) => node && node.type === "data" && Array.isArray(node.data) && node.data[0]);
  const users = nodes.filter((node) => "user" in node.data[0]).map((node) => node.data[node.data[0].user]);
  const reply = { status: response.status, type: "data", values: null };
  if (users.length > 0) reply.signedIn = users.some((user) => user !== null && user !== undefined);
  const holder = nodes.find((node) => "orbProviderOptions" in node.data[0]);
  if (holder) {
    const values = holder.data, at = values[0].orbProviderOptions, kept = values.map(() => null);
    const list = values[at];
    kept[0] = { orbProviderOptions: at };
    kept[at] = list;
    if (Array.isArray(list) && typeof list[0] !== "string") {
      for (const entry of list) {
        const option = values[entry];
        if (!option || typeof option !== "object" || Array.isArray(option)) continue;
        kept[entry] = {};
        for (const key of ["configID", "name", "isDefault", "owner"]) {
          const slot = option[key];
          if (typeof slot !== "number" || slot < 0 || typeof values[slot] === "object") continue;
          kept[entry][key] = slot;
          kept[slot] = values[slot];
        }
      }
    }
    reply.values = kept;
  }
  post(reply);
})();
</script>
""".trimIndent()

/**
 * [OrbProviderSource] through the account's ampcode.com web session: a WebView that is never shown, on the account's
 * WebView profile, loads [READER_PAGE] as a page of the account's origin and answers through a message listener that
 * only that origin can reach. Native code gets the posted message, never a cookie.
 */
internal class OrbProviderReader(private val context: Context) : OrbProviderSource {
    override suspend fun list(account: Account): OrbProviderListing = withContext(Dispatchers.Main) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            return@withContext OrbProviderListing.Failed("This device's WebView can't list self-hosted orbs.")
        }
        val origin = originOf(account.server.ampBaseUrl)
        val view = hardenedWebView(context, WebProfiles.profileFor(account.id))
        try {
            withTimeoutOrNull(READ_TIMEOUT) { read(view, origin) } ?: OrbProviderListing.Failed("ampcode.com did not answer in time.")
        } finally {
            view.destroy()
        }
    }

    private suspend fun read(view: WebView, origin: String): OrbProviderListing = suspendCancellableCoroutine { done ->
        fun finish(listing: OrbProviderListing) {
            if (done.isActive) done.resume(listing)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finish(OrbProviderListing.Failed("The web page stopped."))
                return true
            }
        }
        WebViewCompat.addWebMessageListener(view, BRIDGE_OBJECT, setOf(origin)) { _, message, source, isMainFrame, _ ->
            if (isMainFrame && source.toString().trimEnd('/') == origin) finish(OrbProviderPage.parse(message.data.orEmpty()))
        }
        view.loadDataWithBaseURL("$origin/", READER_PAGE, "text/html", "utf-8", null)
    }

    private fun originOf(baseUrl: String): String {
        val uri = Uri.parse(baseUrl)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$port"
    }
}

@Composable
fun rememberOrbProviderReader(): OrbProviderSource {
    val context = LocalContext.current.applicationContext
    return remember(context) { OrbProviderReader(context) }
}
