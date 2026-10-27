package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A project as `getThreadProject` answers it. */
internal class FakeProject(val id: String, val name: String)

/**
 * One thread of an organized fleet, rendered into the captured `listThreads` row and `getRecentThreads` summary
 * shapes. [state] is the summary's wire state; [needsInput] fills the S15b `action-required` indicator.
 */
internal class OrganizedThread(
    val id: String,
    val title: String,
    val lastActive: Instant,
    val project: FakeProject? = null,
    val state: String = "idle",
    val needsInput: String? = null,
    val unread: Boolean = false,
    val labels: List<String> = emptyList(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val parent: String? = null,
    val agentMode: String = "low",
    val creator: String = USER_ID,
    val visibility: String = "thread_workspace_shared",
) {
    /** Puck conversations play the scripted Puck reply; every other thread opens on the empty S4 thread. */
    fun seed(): FakeThread {
        val script = if (agentMode == PUCK_AGENT_MODE) puckScript(id) else ThreadScript.from(Capture.load("s04-live-thread")).inState(state, agentMode)
        return FakeThread(id, listing(), status(), script, labels = labels, project = project)
    }

    private fun listing(): JsonObject {
        val at = element(lastActive.toEpochMilli())
        val relationships = listOfNotNull(parent?.let { json("threadID" to it, "type" to "subagent", "role" to "parent") })
        return runnerListing() + mapOf(
            "id" to element(id),
            "v" to element(0),
            "created" to at,
            "userLastInteractedAt" to at,
            "title" to element(title),
            "messageCount" to element(0),
            "relationships" to element(relationships),
            "summaryStats" to json("diffStats" to json("added" to 0, "changed" to 0, "deleted" to 0), "messageCount" to 0),
            "agentMode" to element(agentMode),
            "archived" to element(archived),
            "pinned" to element(pinned),
            "creatorUserID" to element(creator),
            "meta" to json("visibility" to visibility, "sharedGroupIDs" to emptyList<Any>()),
        )
    }

    private fun status(): JsonObject {
        val at = element(lastActive.toString())
        val indicator = needsInput?.let {
            json("description" to it, "kind" to "action-required", "observedAt" to DateTimeFormatter.RFC_1123_DATE_TIME.format(lastActive.atOffset(ZoneOffset.UTC)))
        }
        return runnerStatus() + mapOf(
            "threadId" to element(id),
            "title" to element(title),
            "state" to element(state),
            "hasUnreadMessages" to element(unread),
            "indicator" to element(indicator),
            "pinned" to element(pinned),
            "archived" to element(archived),
            "lastUserMessageAt" to at,
            "lastTurnEndedAt" to at,
            "unreadStatusUpdatedAt" to at,
            "updatedAt" to at,
            "latestAssistantMessageID" to element(null),
        )
    }
}

/** The script with its connect-burst `agent_state` set, so an opened thread agrees with its summary. */
internal fun ThreadScript.inState(state: String, agentMode: String): ThreadScript = ThreadScript(
    version, history, greeting,
    burst.map { frame ->
        if (frame.event.type != "agent_state") return@map frame
        BurstFrame(Event("agent_state", frame.event.body + mapOf("state" to element(state), "agentMode" to element(agentMode))), frame.live)
    },
    turns, exchanges,
)

/** [INFERENCE] The web client tells Puck threads apart by `agentMode`; the value is unobserved. */
internal const val PUCK_AGENT_MODE = "puck"

internal const val PUCK_REPLY = "I can start an agent for that. Which project?"
private const val PUCK_TRIGGER = "M-puckTriggerFakeAmp0001"
private const val PUCK_ASSISTANT = "M-puckReplyFakeAmp000001"

/** An empty Puck conversation whose every send plays one text-only turn answering [PUCK_REPLY]. */
internal fun puckScript(threadId: String): ThreadScript {
    val base = ThreadScript.from(Capture.load("s04-live-thread")).inState("idle", PUCK_AGENT_MODE)
    fun state(state: String, messageId: String) = Event("agent_state", json("state" to state, "messageId" to messageId, "agentMode" to PUCK_AGENT_MODE))
    val reply = json(
        "threadId" to threadId,
        "role" to "assistant",
        "content" to listOf(json("type" to "text", "text" to PUCK_REPLY, "blockState" to "complete")),
        "state" to json("type" to "complete"),
        "readAt" to null,
        "messageId" to PUCK_ASSISTANT,
    )
    val turn = Turn(
        PUCK_TRIGGER,
        listOf(
            TurnStep.Emit(100, state("working", PUCK_TRIGGER)),
            TurnStep.Emit(300, Event("message_added", json("message" to reply))),
            TurnStep.Emit(50, state("idle", PUCK_ASSISTANT)),
        ),
        parks = false,
    )
    return ThreadScript(base.version, base.history, base.greeting, base.burst, listOf(turn), base.exchanges)
}

/**
 * The organize methods of `/api/internal`: `updateThread`, `deleteThread`, labels, projects, Puck, and bug reports.
 * Each answer is the whole response body. Runs on the [World] loop.
 */
internal class Organize(private val world: World) {
    private val visibility = HashMap<String, String>()
    private val labels = HashMap<String, List<String>>()
    private val labelBook = HashMap<String, JsonObject>()
    private val bugReports = HashSet<String>()

    fun answer(method: String, params: JsonObject): JsonObject? = when (method) {
        "updateThread" -> on(params.string("thread")) { update(it, params) }
        "deleteThread" -> on(params.string("thread")) {
            world.delete(it.id)
            OK
        }
        "setThreadLabels" -> on(params.string("thread")) { thread ->
            labels[thread.id] = params.array("labels").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            ok(labelsOf(thread))
        }
        "getThreadLabels" -> on(params.string("thread")) { ok(labelsOf(it)) }
        "getThreadProject" -> on(params.string("thread")) { thread -> ok(thread.seed.project?.let { json("id" to it.id, "name" to it.name) }) }
        "openPuckThread" -> openPuck(params.string("archiveThreadID"))
        "openCustomBugReport" -> on(params.string("threadID")) {
            val id = "bug-" + UUID.randomUUID()
            bugReports += id
            ok(json("bugReportID" to id))
        }
        "attachBugReportDiagnostics" -> params.string("bugReportID").let { if (it in bugReports) OK else World.notFound("No bug report $it") }
        else -> null
    }

    /** `meta` for the thread's `listThreads` row, carrying its current visibility. */
    fun listing(thread: ThreadActor): Map<String, JsonElement> {
        val meta = thread.seed.listing.obj("meta") ?: return emptyMap()
        val current = visibility[thread.id] ?: return emptyMap()
        return mapOf("meta" to meta + mapOf("visibility" to element(current)))
    }

    /** Echoes the thread like `captures/s10-thread-ops.jsonl:8`, plus `snooze` and `color` when they were sent. */
    private fun update(thread: ThreadActor, params: JsonObject): JsonObject {
        params.bool("pinned")?.let {
            thread.pinned = it
            world.statusChanged(thread, "pinned" to JsonPrimitive(it))
        }
        params.string("visibility")?.let { visibility[thread.id] = it }
        val current = visibility[thread.id] ?: thread.seed.listing.obj("meta")?.string("visibility")
        val echo = json(
            "threadID" to thread.id,
            "title" to thread.title,
            "pinned" to thread.pinned,
            "visibility" to (ECHO_VISIBILITY[current] ?: current),
            "labels" to namesOf(thread),
            "archived" to thread.archived,
        )
        return ok(echo + listOf("snooze", "color").mapNotNull { key -> params[key]?.let { key to it } }.toMap())
    }

    private fun namesOf(thread: ThreadActor): List<String> = labels[thread.id] ?: thread.seed.labels

    /** Labels belong to the account, so a name keeps one id and creation time across threads. */
    private fun labelsOf(thread: ThreadActor): List<JsonObject> = namesOf(thread).map { name ->
        labelBook.getOrPut(name) { json("id" to UUID.nameUUIDFromBytes(name.toByteArray()).toString(), "name" to name, "createdAt" to Instant.now().toString()) }
    }

    private fun openPuck(archive: String?): JsonObject {
        if (archive != null) {
            val old = world.thread(archive) ?: return World.notFound("No thread $archive")
            old.archived = true
            world.statusChanged(old, "archived" to JsonPrimitive(true))
        }
        val id = "T-" + UUID.randomUUID()
        world.add(OrganizedThread(id, "New conversation", Instant.now(), agentMode = PUCK_AGENT_MODE).seed())
        return ok(json("threadID" to id))
    }

    private inline fun on(id: String?, block: (ThreadActor) -> JsonObject): JsonObject = world.thread(id)?.let(block) ?: World.notFound("No thread $id")

    private fun ok(result: Any?) = json("ok" to true, "result" to result)

    private companion object {
        val OK = json("ok" to true)

        /** `updateThread` echoed `workspace` for a `thread_workspace_shared` thread (S10); the other three are guesses. */
        val ECHO_VISIBILITY = mapOf(
            "private" to "private",
            "thread_workspace_shared" to "workspace",
            "thread_group_shared" to "group",
            "public_unlisted" to "unlisted",
        )
    }
}
