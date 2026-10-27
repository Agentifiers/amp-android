package com.agentifiers.amp.sync.internal.outbox

import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AttachmentDraft
import com.agentifiers.amp.model.ChangesAction
import com.agentifiers.amp.model.Command
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.EditId
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.GateResponses
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.LabelName
import com.agentifiers.amp.model.McpApprovalScope
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.OAuthClientType
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.QueuedMessageId
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.SnoozeUntil
import com.agentifiers.amp.model.TextSelection
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.ToolCallId
import com.agentifiers.amp.model.UploadState
import com.agentifiers.amp.model.Visibility
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/**
 * The versioned JSON of an outbox row. It takes [Command.Durable] only, so an Ephemeral command cannot be
 * encoded and never reaches Room. Each leaf has a stable type name and an explicit field list, written by hand
 * because the :core:model types carry no serialization annotations. [decode] returns null for another version,
 * an unknown type, or a missing field; the drainer then rejects the row so the user can send it again.
 */
internal object OutboxCodec {
    const val VERSION: Int = 1

    fun encode(command: Command.Durable): String = buildJsonObject {
        put("v", VERSION)
        put("type", typeOf(command))
        fields(command)
    }.toString()

    fun decode(json: String): Command.Durable? {
        val o = try {
            AmpJson.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        if (o.long("v") != VERSION.toLong()) return null
        return try {
            read(o.string("type") ?: return null, o)
        } catch (_: MissingField) {
            null
        }
    }

    private fun typeOf(command: Command.Durable): String = when (command) {
        is ThreadIntent.Send -> "Send"
        is ThreadIntent.EditMessage -> "EditMessage"
        is ThreadIntent.SteerQueued -> "SteerQueued"
        is ThreadIntent.RemoveQueued -> "RemoveQueued"
        is ThreadIntent.ReorderQueue -> "ReorderQueue"
        ThreadIntent.Interrupt -> "Interrupt"
        ThreadIntent.Retry -> "Retry"
        ThreadIntent.DismissError -> "DismissError"
        is ThreadIntent.React -> "React"
        is ThreadIntent.Answer -> "Answer"
        is ThreadIntent.Rename -> "Rename"
        is ThreadIntent.Pin -> "Pin"
        is ThreadIntent.Color -> "Color"
        is ThreadIntent.SetLabels -> "SetLabels"
        is ThreadIntent.Snooze -> "Snooze"
        is ThreadIntent.Archive -> "Archive"
        ThreadIntent.Delete -> "Delete"
        is ThreadIntent.MarkRead -> "MarkRead"
        ThreadIntent.MarkUnread -> "MarkUnread"
        ThreadIntent.DismissRecap -> "DismissRecap"
        is ThreadIntent.SetFeature -> "SetFeature"
        is ThreadIntent.SetVisibility -> "SetVisibility"
        is ThreadIntent.OpenToCollaborators -> "OpenToCollaborators"
        is ThreadIntent.Comment -> "Comment"
        is ThreadIntent.RunChangesAction -> "RunChangesAction"
        is ThreadIntent.RunPluginCommand -> "RunPluginCommand"
        is ThreadIntent.SetNetRoute -> "SetNetRoute"
        is ThreadIntent.SetPortalHostname -> "SetPortalHostname"
        ThreadIntent.RestartOrbProcesses -> "RestartOrbProcesses"
        ThreadIntent.RetryFailedSetup -> "RetryFailedSetup"
        is ThreadIntent.ReportBug -> "ReportBug"
        is AccountCommand.ShareRunner -> "ShareRunner"
        is AccountCommand.SetAutomationEnabled -> "SetAutomationEnabled"
        is AccountCommand.DeleteAutomation -> "DeleteAutomation"
        is AccountCommand.SetShipBehavior -> "SetShipBehavior"
        is AccountCommand.EditVocabulary -> "EditVocabulary"
        is AccountCommand.CreateOAuthClient -> "CreateOAuthClient"
        is AccountCommand.ClearOrbSnapshots -> "ClearOrbSnapshots"
    }

    private fun JsonObjectBuilder.fields(command: Command.Durable) {
        when (command) {
            is ThreadIntent.Send -> {
                put("messageId", command.messageId.raw)
                put("mode", command.mode.name)
                put("text", command.draft.text)
                putJsonArray("attachments") {
                    command.draft.attachments.forEach { a ->
                        addJsonObject {
                            put("uri", a.localUri)
                            put("mediaType", a.mediaType)
                            (a.upload as? UploadState.Ready)?.let { put("url", it.url) }
                        }
                    }
                }
            }
            is ThreadIntent.EditMessage -> {
                put("target", command.target.raw)
                put("text", command.text)
                put("editId", command.editId.raw)
            }
            is ThreadIntent.SteerQueued -> put("queued", command.id.raw)
            is ThreadIntent.RemoveQueued -> put("queued", command.id.raw)
            is ThreadIntent.ReorderQueue -> putJsonArray("order") { command.order.forEach { add(JsonPrimitive(it.raw)) } }
            ThreadIntent.Interrupt, ThreadIntent.Retry, ThreadIntent.DismissError, ThreadIntent.Delete, ThreadIntent.MarkUnread,
            ThreadIntent.DismissRecap, ThreadIntent.RestartOrbProcesses, ThreadIntent.RetryFailedSetup -> Unit
            is ThreadIntent.React -> {
                put("message", command.message.raw)
                put("reaction", command.reaction)
                put("on", command.on)
            }
            is ThreadIntent.Answer -> put("response", gateResponse(command.response))
            is ThreadIntent.Rename -> put("title", command.title)
            is ThreadIntent.Pin -> put("pinned", command.pinned)
            is ThreadIntent.Color -> put("color", command.color.name)
            is ThreadIntent.SetLabels -> putJsonArray("labels") { command.labels.forEach { add(JsonPrimitive(it.raw)) } }
            is ThreadIntent.Snooze -> put("until", command.until?.let(::snooze) ?: JsonNull)
            is ThreadIntent.Archive -> put("archived", command.archived)
            is ThreadIntent.MarkRead -> put("upTo", command.upTo.raw)
            is ThreadIntent.SetFeature -> {
                put("feature", command.feature)
                put("enabled", command.enabled)
            }
            is ThreadIntent.SetVisibility -> put("visibility", visibility(command.visibility))
            is ThreadIntent.OpenToCollaborators -> put("ttlSeconds", command.ttlSeconds)
            is ThreadIntent.Comment -> {
                put("anchor", command.anchor.raw)
                putJsonObject("selection") {
                    put("textIndex", command.selection.textIndex)
                    put("start", command.selection.start)
                    put("end", command.selection.end)
                    put("quoted", command.selection.quoted)
                }
                put("text", command.text)
            }
            is ThreadIntent.RunChangesAction -> {
                put("action", changesAction(command.action))
                put("prompt", command.prompt)
                put("messageId", command.messageId.raw)
            }
            is ThreadIntent.RunPluginCommand -> {
                put("plugin", command.plugin)
                put("commandId", command.commandId)
            }
            is ThreadIntent.SetNetRoute -> put("exitNode", command.exitNode?.raw)
            is ThreadIntent.SetPortalHostname -> {
                put("port", command.port)
                put("hostname", command.hostname)
            }
            is ThreadIntent.ReportBug -> {
                put("text", command.text)
                put("includeDiagnostics", command.includeDiagnostics)
            }
            is AccountCommand.ShareRunner -> {
                put("runner", command.runner.raw)
                put("share", command.share)
            }
            is AccountCommand.SetAutomationEnabled -> {
                put("id", command.id)
                put("enabled", command.enabled)
            }
            is AccountCommand.DeleteAutomation -> put("id", command.id)
            is AccountCommand.SetShipBehavior -> {
                put("project", command.project.raw)
                put("behavior", shipBehavior(command.behavior))
            }
            is AccountCommand.EditVocabulary -> {
                put("add", strings(command.add))
                put("remove", strings(command.remove))
            }
            is AccountCommand.CreateOAuthClient -> {
                put("name", command.name)
                put("clientType", command.type.name)
                put("scopes", strings(command.scopes))
                put("redirectUris", strings(command.redirectUris))
            }
            is AccountCommand.ClearOrbSnapshots -> put("project", command.project.raw)
        }
    }

    private fun read(type: String, o: JsonObject): Command.Durable? = when (type) {
        "Send" -> ThreadIntent.Send(
            Draft(o.req("text"), o.list("attachments").map { a -> attachment(a as? JsonObject ?: throw MissingField) }.toPersistentList()),
            SendMode.entries.firstOrNull { it.name == o.req("mode") } ?: throw MissingField,
            MessageId(o.req("messageId")),
        )
        "EditMessage" -> ThreadIntent.EditMessage(MessageId(o.req("target")), o.req("text"), EditId(o.req("editId")))
        "SteerQueued" -> ThreadIntent.SteerQueued(QueuedMessageId(o.req("queued")))
        "RemoveQueued" -> ThreadIntent.RemoveQueued(QueuedMessageId(o.req("queued")))
        "ReorderQueue" -> ThreadIntent.ReorderQueue(o.stringList("order").map(::QueuedMessageId).toPersistentList())
        "Interrupt" -> ThreadIntent.Interrupt
        "Retry" -> ThreadIntent.Retry
        "DismissError" -> ThreadIntent.DismissError
        "React" -> ThreadIntent.React(MessageId(o.req("message")), o.req("reaction"), o.reqBool("on"))
        "Answer" -> ThreadIntent.Answer(gateResponse(o.obj("response") ?: throw MissingField))
        "Rename" -> ThreadIntent.Rename(o.req("title"))
        "Pin" -> ThreadIntent.Pin(o.reqBool("pinned"))
        "Color" -> ThreadIntent.Color(ThreadColor.entries.firstOrNull { it.name == o.req("color") } ?: throw MissingField)
        "SetLabels" -> ThreadIntent.SetLabels(o.stringList("labels").map(::LabelName).toPersistentList())
        "Snooze" -> ThreadIntent.Snooze(o.obj("until")?.let(::snooze))
        "Archive" -> ThreadIntent.Archive(o.reqBool("archived"))
        "Delete" -> ThreadIntent.Delete
        "MarkRead" -> ThreadIntent.MarkRead(MessageId(o.req("upTo")))
        "MarkUnread" -> ThreadIntent.MarkUnread
        "DismissRecap" -> ThreadIntent.DismissRecap
        "SetFeature" -> ThreadIntent.SetFeature(o.req("feature"), o.reqBool("enabled"))
        "SetVisibility" -> ThreadIntent.SetVisibility(visibility(o.obj("visibility") ?: throw MissingField))
        "OpenToCollaborators" -> ThreadIntent.OpenToCollaborators(o.long("ttlSeconds")?.toInt())
        "Comment" -> {
            val s = o.obj("selection") ?: throw MissingField
            ThreadIntent.Comment(
                MessageId(o.req("anchor")),
                TextSelection(s.reqInt("textIndex"), s.reqInt("start"), s.reqInt("end"), s.req("quoted")),
                o.req("text"),
            )
        }
        "RunChangesAction" -> ThreadIntent.RunChangesAction(changesAction(o.req("action")), o.req("prompt"), MessageId(o.req("messageId")))
        "RunPluginCommand" -> ThreadIntent.RunPluginCommand(o.req("plugin"), o.req("commandId"))
        "SetNetRoute" -> ThreadIntent.SetNetRoute(o.string("exitNode")?.let(::RunnerId))
        "SetPortalHostname" -> ThreadIntent.SetPortalHostname(o.reqInt("port"), o.req("hostname"))
        "RestartOrbProcesses" -> ThreadIntent.RestartOrbProcesses
        "RetryFailedSetup" -> ThreadIntent.RetryFailedSetup
        "ReportBug" -> ThreadIntent.ReportBug(o.req("text"), o.reqBool("includeDiagnostics"))
        "ShareRunner" -> AccountCommand.ShareRunner(RunnerId(o.req("runner")), o.reqBool("share"))
        "SetAutomationEnabled" -> AccountCommand.SetAutomationEnabled(o.req("id"), o.reqBool("enabled"))
        "DeleteAutomation" -> AccountCommand.DeleteAutomation(o.req("id"))
        "SetShipBehavior" -> AccountCommand.SetShipBehavior(ProjectId(o.req("project")), shipBehavior(o.obj("behavior") ?: throw MissingField))
        "EditVocabulary" -> AccountCommand.EditVocabulary(o.stringList("add").toPersistentList(), o.stringList("remove").toPersistentList())
        "CreateOAuthClient" -> AccountCommand.CreateOAuthClient(
            o.req("name"),
            OAuthClientType.entries.firstOrNull { it.name == o.req("clientType") } ?: throw MissingField,
            o.stringList("scopes").toPersistentList(),
            o.stringList("redirectUris").toPersistentList(),
        )
        "ClearOrbSnapshots" -> AccountCommand.ClearOrbSnapshots(ProjectId(o.req("project")))
        else -> null
    }

    private fun attachment(a: JsonObject): AttachmentDraft =
        AttachmentDraft(a.req("uri"), a.req("mediaType"), a.string("url")?.let(UploadState::Ready) ?: UploadState.Pending)

    private fun changesAction(action: ChangesAction): String = when (action) {
        ChangesAction.Ship -> "Ship"
        ChangesAction.PushBranch -> "PushBranch"
        ChangesAction.Review -> "Review"
        ChangesAction.Restack -> "Restack"
        ChangesAction.Rebase -> "Rebase"
        ChangesAction.FixCi -> "FixCi"
    }

    private fun changesAction(name: String): ChangesAction = when (name) {
        "Ship" -> ChangesAction.Ship
        "PushBranch" -> ChangesAction.PushBranch
        "Review" -> ChangesAction.Review
        "Restack" -> ChangesAction.Restack
        "Rebase" -> ChangesAction.Rebase
        "FixCi" -> ChangesAction.FixCi
        else -> throw MissingField
    }

    private fun snooze(until: SnoozeUntil): JsonObject = buildJsonObject {
        when (until) {
            SnoozeUntil.OneHour -> put("kind", "OneHour")
            SnoozeUntil.NextReply -> put("kind", "NextReply")
            SnoozeUntil.Indefinitely -> put("kind", "Indefinitely")
            is SnoozeUntil.At -> {
                put("kind", "At")
                put("at", until.instant.toString())
            }
        }
    }

    private fun snooze(o: JsonObject): SnoozeUntil = when (o.req("kind")) {
        "OneHour" -> SnoozeUntil.OneHour
        "NextReply" -> SnoozeUntil.NextReply
        "Indefinitely" -> SnoozeUntil.Indefinitely
        "At" -> SnoozeUntil.At(Instant.parse(o.req("at")))
        else -> throw MissingField
    }

    private fun visibility(v: Visibility): JsonObject = buildJsonObject {
        when (v) {
            Visibility.Private -> put("kind", "Private")
            Visibility.Workspace -> put("kind", "Workspace")
            Visibility.Unlisted -> put("kind", "Unlisted")
            is Visibility.Groups -> {
                put("kind", "Groups")
                put("groupIds", strings(v.groupIds))
            }
        }
    }

    private fun visibility(o: JsonObject): Visibility = when (o.req("kind")) {
        "Private" -> Visibility.Private
        "Workspace" -> Visibility.Workspace
        "Unlisted" -> Visibility.Unlisted
        "Groups" -> Visibility.Groups(o.stringList("groupIds").toPersistentList())
        else -> throw MissingField
    }

    private fun shipBehavior(b: ShipBehavior): JsonObject = buildJsonObject {
        when (b) {
            ShipBehavior.Ship -> put("kind", "Ship")
            ShipBehavior.PushBranch -> put("kind", "PushBranch")
            is ShipBehavior.Custom -> {
                put("kind", "Custom")
                put("prompt", b.prompt)
            }
        }
    }

    private fun shipBehavior(o: JsonObject): ShipBehavior = when (o.req("kind")) {
        "Ship" -> ShipBehavior.Ship
        "PushBranch" -> ShipBehavior.PushBranch
        "Custom" -> ShipBehavior.Custom(o.req("prompt"))
        else -> throw MissingField
    }

    private fun gateResponse(r: GateResponse): JsonObject = buildJsonObject {
        put("gate", r.gate.raw)
        when (r) {
            is GateResponse.Approval -> {
                put("kind", "Approval")
                put("call", r.call.raw)
                put("accepted", r.accepted)
                put("denyFeedback", r.denyFeedback)
                put("askAnswers", json(r.askAnswers))
            }
            is GateResponse.PluginAnswer -> {
                put("kind", "PluginAnswer")
                when (val outcome = r.outcome) {
                    is PluginOutcome.Result -> put("result", json(outcome.value))
                    is PluginOutcome.Cancelled -> put("cancelled", outcome.reason)
                }
            }
            is GateResponse.Elicitation -> {
                put("kind", "Elicitation")
                put("accepted", r.accepted)
                put("values", json(r.values))
            }
            is GateResponse.McpServer -> {
                put("kind", "McpServer")
                put("serverName", r.serverName)
                put("specHash", r.specHash)
                put("command", r.command)
                put("scope", r.scope.name)
            }
        }
    }

    private fun gateResponse(o: JsonObject): GateResponse {
        val gate = GateId(o.req("gate"))
        return when (o.req("kind")) {
            "Approval" -> GateResponses.approval(
                gate, ToolCallId(o.req("call")), o.reqBool("accepted"), o.string("denyFeedback"), value(o["askAnswers"] ?: throw MissingField),
            )
            "PluginAnswer" -> GateResponses.pluginAnswer(
                gate,
                o.string("cancelled")?.let(PluginOutcome::Cancelled) ?: PluginOutcome.Result(value(o["result"] ?: throw MissingField)),
            )
            "Elicitation" -> GateResponses.elicitation(gate, o.reqBool("accepted"), value(o["values"] ?: throw MissingField))
            "McpServer" -> GateResponses.mcpServer(
                gate, o.req("serverName"), o.req("specHash"), o.req("command"),
                McpApprovalScope.entries.firstOrNull { it.name == o.req("scope") } ?: throw MissingField,
            )
            else -> throw MissingField
        }
    }

    private fun json(v: JsonValue): JsonElement = when (v) {
        JsonValue.Null -> JsonNull
        is JsonValue.Bool -> JsonPrimitive(v.v)
        is JsonValue.Num -> JsonPrimitive(v.v)
        is JsonValue.Str -> JsonPrimitive(v.v)
        is JsonValue.Arr -> JsonArray(v.items.map(::json))
        is JsonValue.Obj -> JsonObject(v.fields.mapValues { json(it.value) })
    }

    private fun value(e: JsonElement): JsonValue = when (e) {
        JsonNull -> JsonValue.Null
        is JsonPrimitive -> when {
            e.isString -> JsonValue.Str(e.content)
            e.booleanOrNull != null -> JsonValue.Bool(e.booleanOrNull!!)
            else -> JsonValue.Num(e.doubleOrNull ?: throw MissingField)
        }
        is JsonArray -> JsonValue.Arr(e.map(::value).toPersistentList())
        is JsonObject -> JsonValue.Obj(e.mapValues { value(it.value) }.toPersistentMap())
    }

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private object MissingField : RuntimeException() {
        private fun readResolve(): Any = MissingField
    }

    private fun JsonObject.req(key: String): String = string(key) ?: throw MissingField
    private fun JsonObject.reqBool(key: String): Boolean = bool(key) ?: throw MissingField
    private fun JsonObject.reqInt(key: String): Int = long(key)?.toInt() ?: throw MissingField
    private fun JsonObject.list(key: String): JsonArray = array(key) ?: throw MissingField
    private fun JsonObject.stringList(key: String): List<String> =
        list(key).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw MissingField }
}
