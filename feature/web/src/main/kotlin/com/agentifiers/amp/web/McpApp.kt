package com.agentifiers.amp.web

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LocalPaneSurface
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.McpApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

private const val MCP_APP_HOST = "mcp-app.amp.invalid"
private const val MCP_APP_ORIGIN = "https://$MCP_APP_HOST"
private const val BRIDGE_OBJECT = "ampHost"

private sealed interface McpPage {
    data object Loading : McpPage
    data class Ready(val html: String) : McpPage
    data class Failed(val reason: String) : McpPage
}

/**
 * An MCP App's HTML on a synthetic origin in its own profile. The page reaches Amp only through the `ampHost`
 * message channel, which carries JSON-RPC `tools/call` and `resources/read`.
 */
@Composable
internal fun McpAppView(surface: WebSurface.McpApp, scope: AccountScope, modifier: Modifier = Modifier) {
    val page by produceState<McpPage>(McpPage.Loading, surface) {
        value = scope.mcpApps.readResource(surface.thread, surface.serverId, surface.resourceUri, surface.toolName)
            .fold(ok = { McpPage.Ready(it) }, failed = { McpPage.Failed(it) })
    }
    Column(modifier.background(LocalPaneSurface.current)) {
        Text(
            "${surface.toolName} · ${surface.serverId}",
            color = AmpTheme.colors.text.ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(LocalPaneSurface.current).padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.sm),
        )
        when (val shown = page) {
            McpPage.Loading -> Text("Loading the app…", color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(AmpTheme.spacing.lg))
            is McpPage.Failed -> Text(shown.reason, color = AmpTheme.colors.status.danger, modifier = Modifier.padding(AmpTheme.spacing.lg))
            is McpPage.Ready -> McpAppFrame(surface, shown.html, scope.mcpApps, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun McpAppFrame(surface: WebSurface.McpApp, html: String, apps: McpApps, modifier: Modifier) {
    val prompts = remember { WebPrompts() }
    val coroutines = rememberCoroutineScope()
    val bridged = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    val frame = rememberWebFrame(WebProfiles.MCP_APPS, networked = true, prompts) { uri ->
        if (uri.host == MCP_APP_HOST) Navigation.Load else Navigation.Browser(uri.toString())
    }
    LaunchedEffect(frame) {
        if (bridged) {
            WebViewCompat.addWebMessageListener(frame.view, BRIDGE_OBJECT, setOf(MCP_APP_ORIGIN), McpBridge(surface, apps, prompts, coroutines))
        }
        frame.view.loadDataWithBaseURL("$MCP_APP_ORIGIN/", html, "text/html", "utf-8", null)
    }
    Column(modifier) {
        if (!bridged) {
            Text("This device's WebView can't connect the app to Amp, so its buttons that call tools won't work.", color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(AmpTheme.spacing.md))
        }
        WebFrameView(frame, Modifier.fillMaxWidth().weight(1f))
        NoticeLine(prompts, Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs))
    }
    PromptDialog(prompts)
}

private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private const val SERVER_ERROR = -32000

/**
 * The host end of the page's JSON-RPC channel. Only requests (with an `id`) for `tools/call` and `resources/read`
 * are served; a tool runs only after the user confirms it by name.
 */
private class McpBridge(
    private val surface: WebSurface.McpApp,
    private val apps: McpApps,
    private val prompts: WebPrompts,
    private val scope: CoroutineScope,
) : WebViewCompat.WebMessageListener {
    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val request = try {
            JSONObject(message.data ?: return)
        } catch (notJson: JSONException) {
            return
        }
        if (request.optString("jsonrpc") != "2.0" || !request.has("id") || !request.has("method")) return
        val id = request.get("id")
        val params = request.optJSONObject("params") ?: JSONObject()
        scope.launch { replyProxy.postMessage(answer(id, request.getString("method"), params).toString()) }
    }

    private suspend fun answer(id: Any, method: String, params: JSONObject): JSONObject = when (method) {
        "tools/call" -> callTool(id, params)
        "resources/read" -> readResource(id, params)
        else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
    }

    private suspend fun callTool(id: Any, params: JSONObject): JSONObject {
        val name = params.optString("name").takeIf { it.isNotEmpty() } ?: return error(id, INVALID_PARAMS, "params.name is required")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val allowed = prompts.confirm(
            "Run $name on ${surface.serverId}?",
            "The app in this pane asked to run the tool $name on the MCP server ${surface.serverId}.",
            "Run",
        )
        if (!allowed) return error(id, SERVER_ERROR, "The user declined to run $name.")
        return apps.callTool(surface.thread, surface.serverId, name, arguments.toString())
            .fold(ok = { result(id, jsonValueOf(it)) }, failed = { error(id, SERVER_ERROR, it) })
    }

    private suspend fun readResource(id: Any, params: JSONObject): JSONObject {
        val uri = params.optString("uri").takeIf { it.isNotEmpty() } ?: return error(id, INVALID_PARAMS, "params.uri is required")
        return apps.readResource(surface.thread, surface.serverId, uri, null).fold(
            ok = { text -> result(id, JSONObject().put("contents", JSONArray().put(JSONObject().put("uri", uri).put("text", text)))) },
            failed = { error(id, SERVER_ERROR, it) },
        )
    }

    private fun result(id: Any, value: Any): JSONObject = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", value)

    private fun error(id: Any, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    /** The engine's result is JSON text; a page expects the parsed value, or the text itself when it isn't JSON. */
    private fun jsonValueOf(text: String): Any = try {
        JSONTokener(text).nextValue() ?: JSONObject.NULL
    } catch (notJson: JSONException) {
        text
    }
}
