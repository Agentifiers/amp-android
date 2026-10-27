package com.agentifiers.amp.sync.internal.puck

import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.PuckDirectory
import com.agentifiers.amp.sync.api.PuckVoiceChannel
import com.agentifiers.amp.sync.internal.fleet.FleetEngine
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.puck.voice.PuckVoiceEngine
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import java.util.concurrent.ConcurrentHashMap
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.agentifiers.amp.sync.internal.net.Outcome as Http

/**
 * Puck conversations are threads (collab-apps.md:207). The list comes from `listThreads`, because
 * `listPuckConversations` is web-remote; `openPuckThread` starts or reopens one.
 */
internal class PuckEngine(
    private val http: AmpHttp,
    private val fleet: FleetEngine,
    private val scope: CoroutineScope,
    private val onUnauthorized: () -> Unit,
    private val engineOf: (ThreadId) -> ThreadEngine,
) : PuckDirectory {
    private val calls = ConcurrentHashMap<PuckConversationId, PuckVoiceEngine>()

    override val conversations: StateFlow<PersistentList<ThreadSummary>> = fleet.threads.map { view ->
        (view as? com.agentifiers.amp.model.FleetView.Ready)?.threads.orEmpty()
            .filter { it.isPuck }
            .sortedByDescending { it.lastActivity }
            .toPersistentList()
    }.stateIn(scope, SharingStarted.Eagerly, kotlinx.collections.immutable.persistentListOf())

    override suspend fun open(startFresh: Boolean): Outcome<PuckConversationId> {
        val current = conversations.value.firstOrNull()?.id
        val params = buildJsonObject { if (startFresh && current != null) put("archiveThreadID", current.raw) }
        return when (val result = http.internal("openPuckThread", params)) {
            is Http.Ok -> {
                val id = (result.value as? JsonObject)?.let { it.string("threadID") ?: it.string("threadId") }?.let(ThreadId::parse)
                    ?: return Outcome.Failed(EngineError.Drift("openPuckThread"))
                fleet.refresh()
                Outcome.Ok(PuckConversationId(id))
            }
            Http.Unauthorized -> Outcome.Failed(EngineError.Unauthorized).also { onUnauthorized() }
            is Http.Failed -> Outcome.Failed(EngineError.Server(result.status?.toString() ?: "network", result.reason))
        }
    }

    /** One channel per conversation, so every window shows the same call. */
    override fun voice(conversation: PuckConversationId): PuckVoiceChannel =
        calls.computeIfAbsent(conversation) { PuckVoiceEngine(engineOf(it.thread), scope) }
}
