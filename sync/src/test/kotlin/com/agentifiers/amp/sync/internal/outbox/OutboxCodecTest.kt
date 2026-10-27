package com.agentifiers.amp.sync.internal.outbox

import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AttachmentDraft
import com.agentifiers.amp.model.ChangesAction
import com.agentifiers.amp.model.Command
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.EditId
import com.agentifiers.amp.model.GateId
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
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Instant

class OutboxCodecTest {
    private val sent = MessageId("M-Dhm6ABrxGfqFPd1YyU6N01")
    private val queued = QueuedMessageId("M-Q8nYz2b6PbRsVnKpLwXy01")

    /** One value of every Durable leaf and the exact row JSON it must produce. */
    private val cases: List<Pair<Command.Durable, String>> = listOf(
        ThreadIntent.Send(Draft.text("Rename add to add_numbers in calc.py and run it"), SendMode.Auto, sent) to
            """{"v":1,"type":"Send","messageId":"M-Dhm6ABrxGfqFPd1YyU6N01","mode":"Auto","text":"Rename add to add_numbers in calc.py and run it","attachments":[]}""",
        ThreadIntent.Send(
            Draft("look", persistentListOf(AttachmentDraft("content://media/1", "image/png", UploadState.Ready("https://ampcode.com/user-content/attachments/a.png")))),
            SendMode.Steer, sent,
        ) to """{"v":1,"type":"Send","messageId":"M-Dhm6ABrxGfqFPd1YyU6N01","mode":"Steer","text":"look","attachments":[{"uri":"content://media/1","mediaType":"image/png","url":"https://ampcode.com/user-content/attachments/a.png"}]}""",
        ThreadIntent.EditMessage(sent, "add tests too", EditId("M-Ed1tIdAAAAAAAAAAAAAAAA")) to
            """{"v":1,"type":"EditMessage","target":"M-Dhm6ABrxGfqFPd1YyU6N01","text":"add tests too","editId":"M-Ed1tIdAAAAAAAAAAAAAAAA"}""",
        ThreadIntent.SteerQueued(queued) to """{"v":1,"type":"SteerQueued","queued":"M-Q8nYz2b6PbRsVnKpLwXy01"}""",
        ThreadIntent.RemoveQueued(queued) to """{"v":1,"type":"RemoveQueued","queued":"M-Q8nYz2b6PbRsVnKpLwXy01"}""",
        ThreadIntent.ReorderQueue(persistentListOf(queued, QueuedMessageId("M-2"))) to
            """{"v":1,"type":"ReorderQueue","order":["M-Q8nYz2b6PbRsVnKpLwXy01","M-2"]}""",
        ThreadIntent.Interrupt to """{"v":1,"type":"Interrupt"}""",
        ThreadIntent.Retry to """{"v":1,"type":"Retry"}""",
        ThreadIntent.DismissError to """{"v":1,"type":"DismissError"}""",
        ThreadIntent.React(sent, "+1", on = true) to """{"v":1,"type":"React","message":"M-Dhm6ABrxGfqFPd1YyU6N01","reaction":"+1","on":true}""",
        ThreadIntent.Answer(GateResponses.approval(GateId("toolu_01"), ToolCallId("toolu_01"), false, "use git mv", JsonValue.Null)) to
            """{"v":1,"type":"Answer","response":{"gate":"toolu_01","kind":"Approval","call":"toolu_01","accepted":false,"denyFeedback":"use git mv","askAnswers":null}}""",
        ThreadIntent.Answer(GateResponses.pluginAnswer(GateId("req-7"), PluginOutcome.Result(JsonValue.Str("staging")))) to
            """{"v":1,"type":"Answer","response":{"gate":"req-7","kind":"PluginAnswer","result":"staging"}}""",
        ThreadIntent.Answer(GateResponses.pluginAnswer(GateId("req-8"), PluginOutcome.Cancelled("dismissed"))) to
            """{"v":1,"type":"Answer","response":{"gate":"req-8","kind":"PluginAnswer","cancelled":"dismissed"}}""",
        ThreadIntent.Answer(
            GateResponses.elicitation(GateId("el-1"), true, JsonValue.Obj(persistentMapOf("count" to JsonValue.Num(2.0), "ok" to JsonValue.Bool(true)))),
        ) to """{"v":1,"type":"Answer","response":{"gate":"el-1","kind":"Elicitation","accepted":true,"values":{"count":2.0,"ok":true}}}""",
        ThreadIntent.Answer(GateResponses.mcpServer(GateId("mcp-1"), "linear", "abc123", "npx linear-mcp", McpApprovalScope.Project)) to
            """{"v":1,"type":"Answer","response":{"gate":"mcp-1","kind":"McpServer","serverName":"linear","specHash":"abc123","command":"npx linear-mcp","scope":"Project"}}""",
        ThreadIntent.Rename("android-design-probe") to """{"v":1,"type":"Rename","title":"android-design-probe"}""",
        ThreadIntent.Pin(true) to """{"v":1,"type":"Pin","pinned":true}""",
        ThreadIntent.Color(ThreadColor.Blue) to """{"v":1,"type":"Color","color":"Blue"}""",
        ThreadIntent.SetLabels(persistentListOf(LabelName("spike"), LabelName("android"))) to """{"v":1,"type":"SetLabels","labels":["spike","android"]}""",
        ThreadIntent.Snooze(SnoozeUntil.OneHour) to """{"v":1,"type":"Snooze","until":{"kind":"OneHour"}}""",
        ThreadIntent.Snooze(SnoozeUntil.At(Instant.parse("2026-10-24T09:00:00Z"))) to
            """{"v":1,"type":"Snooze","until":{"kind":"At","at":"2026-10-24T09:00:00Z"}}""",
        ThreadIntent.Snooze(null) to """{"v":1,"type":"Snooze","until":null}""",
        ThreadIntent.Archive(true) to """{"v":1,"type":"Archive","archived":true}""",
        ThreadIntent.Delete to """{"v":1,"type":"Delete"}""",
        ThreadIntent.MarkRead(sent) to """{"v":1,"type":"MarkRead","upTo":"M-Dhm6ABrxGfqFPd1YyU6N01"}""",
        ThreadIntent.MarkUnread to """{"v":1,"type":"MarkUnread"}""",
        ThreadIntent.DismissRecap to """{"v":1,"type":"DismissRecap"}""",
        ThreadIntent.SetFeature("fast", true) to """{"v":1,"type":"SetFeature","feature":"fast","enabled":true}""",
        ThreadIntent.SetVisibility(Visibility.Groups(persistentListOf("grp_1"))) to
            """{"v":1,"type":"SetVisibility","visibility":{"kind":"Groups","groupIds":["grp_1"]}}""",
        ThreadIntent.OpenToCollaborators(3600) to """{"v":1,"type":"OpenToCollaborators","ttlSeconds":3600}""",
        ThreadIntent.Comment(sent, TextSelection(0, 4, 9, "add_n"), "why?") to
            """{"v":1,"type":"Comment","anchor":"M-Dhm6ABrxGfqFPd1YyU6N01","selection":{"textIndex":0,"start":4,"end":9,"quoted":"add_n"},"text":"why?"}""",
        ThreadIntent.RunChangesAction(ChangesAction.Ship, "Ship it", sent) to """{"v":1,"type":"RunChangesAction","action":"Ship","prompt":"Ship it","messageId":"M-Dhm6ABrxGfqFPd1YyU6N01"}""",
        ThreadIntent.RunPluginCommand("spike-dialog", "spike-dialog") to """{"v":1,"type":"RunPluginCommand","plugin":"spike-dialog","commandId":"spike-dialog"}""",
        ThreadIntent.SetNetRoute(null) to """{"v":1,"type":"SetNetRoute","exitNode":null}""",
        ThreadIntent.SetPortalHostname(5173, "probe") to """{"v":1,"type":"SetPortalHostname","port":5173,"hostname":"probe"}""",
        ThreadIntent.RestartOrbProcesses to """{"v":1,"type":"RestartOrbProcesses"}""",
        ThreadIntent.RetryFailedSetup to """{"v":1,"type":"RetryFailedSetup"}""",
        ThreadIntent.ReportBug("transcript froze", includeDiagnostics = true) to
            """{"v":1,"type":"ReportBug","text":"transcript froze","includeDiagnostics":true}""",
        AccountCommand.ShareRunner(RunnerId("android-spike"), share = false) to """{"v":1,"type":"ShareRunner","runner":"android-spike","share":false}""",
        AccountCommand.SetAutomationEnabled("auto_1", enabled = true) to """{"v":1,"type":"SetAutomationEnabled","id":"auto_1","enabled":true}""",
        AccountCommand.DeleteAutomation("auto_1") to """{"v":1,"type":"DeleteAutomation","id":"auto_1"}""",
        AccountCommand.SetShipBehavior(ProjectId("prj_1"), ShipBehavior.Custom("open a draft PR")) to
            """{"v":1,"type":"SetShipBehavior","project":"prj_1","behavior":{"kind":"Custom","prompt":"open a draft PR"}}""",
        AccountCommand.EditVocabulary(persistentListOf("Rivet"), persistentListOf()) to """{"v":1,"type":"EditVocabulary","add":["Rivet"],"remove":[]}""",
        AccountCommand.CreateOAuthClient("ci", OAuthClientType.ServerSide, persistentListOf("threads:read"), persistentListOf("https://example.com/cb")) to
            """{"v":1,"type":"CreateOAuthClient","name":"ci","clientType":"ServerSide","scopes":["threads:read"],"redirectUris":["https://example.com/cb"]}""",
        AccountCommand.ClearOrbSnapshots(ProjectId("prj_1")) to """{"v":1,"type":"ClearOrbSnapshots","project":"prj_1"}""",
    )

    @Test
    fun everyDurableCommandEncodesToItsRowJson() {
        cases.forEach { (command, json) -> assertEquals(json, OutboxCodec.encode(command)) }
    }

    @Test
    fun everyRowJsonDecodesToTheSameCommand() {
        cases.forEach { (command, json) -> assertEquals(json, command, OutboxCodec.decode(json)) }
    }

    @Test
    fun theCasesCoverEveryDurableLeaf() {
        val types = cases.map { (_, json) -> Regex("\"type\":\"(\\w+)\"").find(json)!!.groupValues[1] }.toSet()
        assertEquals(38, types.size)
    }

    @Test
    fun aRowFromAnotherVersionOrOfAnUnknownTypeDoesNotDecode() {
        assertNull(OutboxCodec.decode("""{"v":2,"type":"Interrupt"}"""))
        assertNull(OutboxCodec.decode("""{"v":1,"type":"Teleport"}"""))
        assertNull(OutboxCodec.decode("""{"v":1,"type":"Send","messageId":"M-1","mode":"Auto"}"""))
        assertNull(OutboxCodec.decode("not json"))
        assertNull(OutboxCodec.decode("""{"v":1,"type":"WakeExecutor"}"""))
    }
}
