package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.ThreadId

/**
 * The two calls an MCP App page may reach through its postMessage bridge (auth-and-security.md, WebView rules).
 * Both are internal RPC methods the web app uses (`docs/protocol/private-protocol.md:506-507`); no capture exists.
 */
public interface McpApps {
    /** `mcpAppReadResource{serverID, uri, toolName}`: the app's HTML. */
    public suspend fun readResource(thread: ThreadId, serverId: String, uri: String, toolName: String?): Outcome<String>

    /** `mcpAppCallTool{serverID, name, arguments}`. [argumentsJson] is the page's JSON object; the result is JSON text for the page. */
    public suspend fun callTool(thread: ThreadId, serverId: String, name: String, argumentsJson: String): Outcome<String>
}
