package com.agentifiers.amp.sync.internal.catalog

import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.NewThreadSpec
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.WorktreeChoice
import com.agentifiers.amp.sync.internal.outbox.appendParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * `client_spawn_executor` params, or null when the thread gets no machine. The runner shape is measured (S4,
 * `captures/s04-live-thread.jsonl:30`). The orb fields `projectID` and `environmentResource`, and the runner's
 * `runnerWorktree`, are read from the CLI's schema (private-protocol.md:137, :482) and not observed. The schema has
 * no provider field (web chunk `b0f88c20.js`, search ``type:o(`client_spawn_executor`)``), so an orb spawned here
 * runs on the provider Amp picks for the thread.
 */
internal fun spawnParams(executor: ExecutorChoice): JsonObject? = when (executor) {
    ExecutorChoice.NoMachine -> null
    is ExecutorChoice.Runner -> buildJsonObject {
        put("requestId", UUID.randomUUID().toString())
        putJsonObject("executor") {
            put("type", "runner")
            put("runnerId", executor.runner.raw)
            put("workingDirectory", executor.directory)
            when (val worktree = executor.worktree) {
                WorktreeChoice.InPlace -> Unit
                is WorktreeChoice.NewWorktree -> put("runnerWorktree", worktree.branch)
            }
        }
        executor.project?.let { put("projectID", it.raw) }
    }
    is ExecutorChoice.Orb -> buildJsonObject {
        put("requestId", UUID.randomUUID().toString())
        executor.project?.let { put("projectID", it.raw) }
        put("environmentResource", executor.size.key)
    }
}

/**
 * Internal `createRemoteExecutorThread` params for an orb on a self-hosted provider: the call behind
 * `amp -ox "<prompt>" --orb-provider <name>`. It creates the thread with its first message and spawns the orb on
 * [provider], whose `orbProvider` schema is `"builtin" | {configID} | {name}` (web chunk `1476a75e.js`, search
 * ``environmentResource:y(N),orbProvider:y``). A listed provider goes as `{configID}`, as the web composer sends it
 * (`cf27bdbc.js`, search ``selectOrbProvider(e===`builtin`?e:{configID:e})``); a typed one goes as `{name}`.
 */
internal fun selfHostedThreadParams(spec: NewThreadSpec, orb: ExecutorChoice.Orb, provider: OrbProvider.SelfHosted, messageId: MessageId): JsonObject =
    JsonObject(
        appendParams(spec.firstMessage, messageId, steer = false, agentMode = spec.mode.key) + buildJsonObject {
            orb.project?.let { put("projectID", it.raw) }
            put("environmentResource", orb.size.key)
            putJsonObject("orbProvider") {
                when (provider) {
                    is OrbProvider.SelfHosted.Listed -> put("configID", provider.configId.raw)
                    is OrbProvider.SelfHosted.Named -> put("name", provider.name)
                }
            }
            if (spec.labels.isNotEmpty()) putJsonArray("labels") { spec.labels.forEach { add(JsonPrimitive(it.raw)) } }
        },
    )
