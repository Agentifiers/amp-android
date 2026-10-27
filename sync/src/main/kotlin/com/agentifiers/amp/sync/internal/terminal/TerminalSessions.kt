package com.agentifiers.amp.sync.internal.terminal

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.TerminalSessionKey
import com.agentifiers.amp.sync.api.RelayPayload
import com.agentifiers.amp.sync.api.TerminalSession
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI

/**
 * One thread's terminal tabs, one [RelayTerminal] per session key, and the executor facts they share: the
 * workspace root for file staging, who runs the executor for the disabled message, and whether it speaks `amp-exec-v1`.
 */
internal class TerminalSessions(
    val thread: ThreadId,
    val relay: RelayMux,
    val state: StateFlow<ThreadState>,
    val hold: () -> AutoCloseable,
    val upload: AttachmentUpload,
    private val scope: CoroutineScope,
) {
    private val sessions = HashMap<TerminalSessionKey?, RelayTerminal>()

    @Volatile
    var executor: ExecutorKind = ExecutorKind.Unknown
        private set

    @Volatile
    private var workspaceRoot: String? = null

    @Volatile
    private var execUnsupported = false

    fun session(key: TerminalSessionKey?): TerminalSession = synchronized(sessions) {
        sessions.getOrPut(key) { RelayTerminal(key, this, scope) }
    }

    fun forget(session: RelayTerminal) {
        synchronized(sessions) { if (sessions[session.key] === session) sessions.remove(session.key) }
    }

    /** Every thread event passes here. */
    fun onEvent(type: String, body: JsonObject) {
        when (type) {
            "environment_update" -> body.obj("environment")?.string("workspaceRoot")?.let { workspaceRoot = pathOf(it) }
            "executor_status" -> body.obj("details")?.obj("executionEnvironment")?.let { executor = ExecutorKind.of(it) }
        }
    }

    /**
     * Step 3 of terminal.md, "File upload by paste or drop": `amp files get <url> --output <path>` through `amp-exec-v1`.
     * True only on exit status 0. After "Unsupported executor relay service" this thread's executor is never asked again.
     */
    suspend fun saveOnExecutor(url: String, output: String): Boolean {
        val cwd = workspaceRoot
        if (execUnsupported || cwd == null) return false
        val command = buildJsonObject {
            put("program", "amp")
            putJsonArray("args") { listOf("files", "get", url, "--output", output).forEach(::add) }
            put("cwd", cwd)
        }
        relay.open(EXEC_SERVICE).use { stream ->
            if (!stream.send(RelayPayload.Text(command.toString()))) return false
            return withTimeoutOrNull(EXEC_TIMEOUT_MS) {
                val reply = stream.incoming.mapNotNull { payload -> (payload as? RelayPayload.Text)?.let(::execReply) }.firstOrNull()
                if (reply == null) {
                    val end = stream.end.await()
                    if (end is RelayEnd.Exited && end.error?.startsWith(UNSUPPORTED) == true) execUnsupported = true
                }
                reply?.string("type") == "exit" && reply.long("status") == 0L
            } ?: false
        }
    }

    private fun execReply(payload: RelayPayload.Text): JsonObject? =
        (runCatching { AmpJson.parseToJsonElement(payload.json) }.getOrNull() as? JsonObject)?.takeIf { it.string("type") in EXEC_REPLIES }

    /** `environment.workspaceRoot` is a `file://` URI (s08-terminal.jsonl:38); `cwd` wants the path. */
    private fun pathOf(root: String): String? =
        if (root.startsWith("file:")) runCatching { URI(root).path }.getOrNull() else root.takeIf { it.startsWith("/") }

    private companion object {
        const val EXEC_SERVICE = "amp-exec-v1"
        const val EXEC_TIMEOUT_MS = 300_000L
        const val UNSUPPORTED = "Unsupported executor relay service"
        val EXEC_REPLIES = setOf("exit", "error")
    }
}
