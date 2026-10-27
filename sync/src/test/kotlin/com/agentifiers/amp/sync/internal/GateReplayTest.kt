package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.ChoiceGate
import com.agentifiers.amp.model.ElicitField
import com.agentifiers.amp.model.ElicitationGate
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateOutcome
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.Link
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.PluginNotice
import com.agentifiers.amp.model.ReadOnlyReason
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.UnsupportedWhy
import com.agentifiers.amp.sync.internal.gates.Capability
import com.agentifiers.amp.sync.internal.gates.GateBook
import com.agentifiers.amp.sync.internal.gates.GateDecoders
import com.agentifiers.amp.sync.internal.gates.GateDesk
import com.agentifiers.amp.sync.internal.gates.GateFact
import com.agentifiers.amp.sync.internal.gates.GateRequest
import com.agentifiers.amp.sync.internal.gates.GateWire
import com.agentifiers.amp.sync.internal.thread.ThreadFold
import com.agentifiers.amp.sync.internal.thread.ThreadReducer
import com.agentifiers.amp.sync.internal.thread.project
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.FrameParser
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import com.agentifiers.amp.sync.internal.wire.ThreadFact
import com.agentifiers.amp.sync.internal.wire.WireDecoders
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GateReplayTest {
    private val thread = ThreadId.parse("T-01a16738-2ec2-7354-98e9-688bc413f079")!!

    private fun lines(capture: String): List<JsonObject> =
        File(System.getProperty("amp.captures"), "$capture.jsonl").readLines().map { AmpJson.parseToJsonElement(it) as JsonObject }

    private fun JsonObject.str(key: String) = (this[key] as JsonPrimitive).content

    /** Thread socket frames received up to and including capture line [through]. */
    private fun factsThrough(capture: String, through: Int): List<ThreadFact> = lines(capture)
        .filter { it.str("dir") == "recv" && it.str("ch").startsWith("thread") && it.str("n").toInt() <= through }
        .flatMap { FrameParser.parse((it["data"] as JsonObject).toString()) }
        .filterIsInstance<InboundFrame.Event>()
        .map(WireDecoders::decode)

    private fun book(capture: String, through: Int): GateBook =
        factsThrough(capture, through).fold(ThreadFold.empty(), ThreadReducer::reduce).gates

    /** The `params` the spike sent on capture line [n]. */
    private fun sentParams(capture: String, n: Int): JsonObject =
        (lines(capture).single { it.str("n") == "$n" }["data"] as JsonObject)["params"] as JsonObject

    @Test
    fun pluginConfirmFromS15bIsOnePendingGateWithTheRecordedText() {
        val gate = book("s15b-plugin-dialog", 64).pending(Capability.Write).single() as PluginGate.Confirm

        assertEquals(GateId("p-4bca076e-1d84-48d2-9b25-6323cc208118-2"), gate.id)
        assertEquals("Spike confirm", gate.title)
        assertEquals("Allow the spike to continue?", gate.body)
        assertEquals("Allow", gate.confirmLabel)
        assertEquals(Answerability.Answerable, gate.answerability)
    }

    @Test
    fun confirmingAnswersWithTheExactS15bFrame() {
        val gate = book("s15b-plugin-dialog", 64).pending(Capability.Write).single() as PluginGate.Confirm
        val request = GateWire.request(gate.respond(confirmed = true, file = null), attempt = 1) as GateRequest.Plugin

        assertEquals("executor_plugin_message", request.method)
        assertEquals(sentParams("s15b-plugin-dialog", 66), request.params)
        assertEquals("plugin-ui-respond-p-4bca076e-1d84-48d2-9b25-6323cc208118-2-1", request.replyId)
    }

    @Test
    fun settleClearsTheConfirmAndTheInputArrivesAndAnswersWithTheS15bText() {
        assertEquals(emptyList<Any>(), book("s15b-plugin-dialog", 67).pending(Capability.Write))
        val input = book("s15b-plugin-dialog", 69).pending(Capability.Write).single() as PluginGate.Input
        assertEquals("Spike input", input.title)
        assertEquals("Type anything", input.helpText)
        assertEquals("Submit", input.submitLabel)

        val request = GateWire.request(input.respond("hello from android"), attempt = 2) as GateRequest.Plugin
        assertEquals(sentParams("s15b-plugin-dialog", 70), request.params)
    }

    @Test
    fun cancellingAnswersWithTheS15cErrorFrame() {
        val input = book("s15c-plugin-dialog-detached", 113).pending(Capability.Write).single() as PluginGate.Input
        val request = GateWire.request(input.cancel("cancelled by spike"), attempt = 1) as GateRequest.Plugin

        assertEquals(sentParams("s15c-plugin-dialog-detached", 114), request.params)
    }

    @Test
    fun reconnectSnapshotListsOnlyThePendingDialog() {
        val pending = book("s15c-plugin-dialog-detached", 101).pending(Capability.Write)

        assertEquals(listOf(GateId("p-4bca076e-1d84-48d2-9b25-6323cc208118-5")), pending.map { it.id })
    }

    @Test
    fun commandListDropsHiddenCommandsAndRunningOneSendsTheS15bFrame() {
        val commands = book("s15b-plugin-dialog", 52).commands
        assertEquals(
            listOf("disable-permissions-permanent", "disable-permissions-session", "show-permissions-state", "spike-dialog", "thread.set-visibility"),
            commands.map { it.id },
        )
        val spike = commands.single { it.id == "spike-dialog" }
        val request = GateWire.runCommand(thread, spike.plugin, spike.id, "spike-command-d5f4279b-a3f9-41b7-a909-c0d11cb0632b")

        assertEquals(sentParams("s15b-plugin-dialog", 63), request.params)
    }

    @Test
    fun notifyBecomesANoticeAndTheSettledGateIsRecordedAsAnsweredHere() {
        val desk = GateDesk()
        var fold = ThreadFold.empty()
        for (fact in factsThrough("s15b-plugin-dialog", 73)) {
            val before = fold
            fold = ThreadReducer.reduce(fold, fact)
            if (fact is ThreadFact.Gate) {
                if (fact.fact is GateFact.PluginRaised) desk.sending((fact.fact as GateFact.PluginRaised).id, "Answered by you")
                desk.observe(fact.fact, before.gates, fold.gates)
            }
        }
        val state = desk.decorate(project(thread, fold, Link.Live, persistentListOf(), persistentListOf()), fold.gates)

        assertEquals(listOf(PluginNotice(1, "spike-dialog confirmed=true text=hello from android")), state.pluginNotices)
        assertEquals(listOf("Spike confirm", "Spike input"), state.gateRecords.map { it.title })
        assertEquals(listOf(GateOutcome.ByYou("Answered by you"), GateOutcome.ByYou("Answered by you")), state.gateRecords.map { it.outcome })
        assertEquals(emptyList<Any>(), state.gates)
    }

    private val approvalItem = AmpJson.parseToJsonElement(
        """{"approvals":[{"id":"approval-TU-1","toolCallId":"TU-1","toolName":"shell_command","args":{"command":"rm -rf build","workdir":"/tmp/x"},
           "reason":"Matches an ask rule for shell commands","toAllow":["rm -rf build"],"context":"thread","timestamp":"2026-10-23T05:00:00.000Z"}]}""",
    ) as JsonObject

    @Test
    fun approvalShowsTheWholeCommandAndAnswersAllowAndDenyOnTheSocket() {
        val book = GateBook.Empty.apply(GateDecoders.approvalQueue(approvalItem)!!)
        val gate = book.pending(Capability.Write).single() as ToolApprovalGate
        assertEquals("rm -rf build", gate.summary)
        assertEquals("Matches an ask rule for shell commands", gate.reason)

        val allow = GateWire.request(gate.approve(), 1) as GateRequest.Socket
        assertEquals("client_tool_approval_response", allow.method)
        assertEquals(AmpJson.parseToJsonElement("""{"toolCallId":"TU-1","accepted":true}"""), allow.params)
        val deny = GateWire.request(gate.deny("use make clean"), 1) as GateRequest.Socket
        assertEquals(AmpJson.parseToJsonElement("""{"toolCallId":"TU-1","accepted":false,"input":{"denyFeedback":"use make clean"}}"""), deny.params)
    }

    @Test
    fun askUserChoiceAnswersOneStringPerQuestion() {
        val body = AmpJson.parseToJsonElement(
            """{"approvals":[{"id":"a","toolCallId":"TU-2","toolName":"ask_user_choice","context":"thread","timestamp":"2026-10-23T05:00:00.000Z",
               "args":{"questions":[{"question":"Which targets?","options":[{"label":"linux"},{"label":"macos"}],"multiSelect":true}]}}]}""",
        ) as JsonObject
        val gate = GateBook.Empty.apply(GateDecoders.approvalQueue(body)!!).pending(Capability.Write).single() as ChoiceGate
        val request = GateWire.request(gate.answer(mapOf("Which targets?" to listOf("linux", "macos")), emptyMap()), 1) as GateRequest.Socket

        assertEquals(
            AmpJson.parseToJsonElement("""{"toolCallId":"TU-2","accepted":true,"input":{"askAnswers":{"Which targets?":"linux, macos"}}}"""),
            request.params,
        )
    }

    @Test
    fun elicitationFormAnswersTheMcpAcceptShape() {
        val body = AmpJson.parseToJsonElement(
            """{"message":{"type":"event","event":"plugin.ui.request_added","data":{"request":{"requestId":"e-1","method":"elicitation/create",
               "params":{"serverName":"linear","message":"File the issue?","requestedSchema":{"type":"object","properties":{
               "title":{"type":"string","title":"Title"},"points":{"type":"integer"},"urgent":{"type":"boolean"},"team":{"type":"string","enum":["web","app"]}}}},
               "target":{"kind":"interactive"},"status":"pending","createdAt":"Fri, 23 Oct 2026 05:32:17 GMT"}}}}""",
        ) as JsonObject
        val gate = (GateDecoders.pluginMessage(body) as GateFact.PluginRaised).gate as ElicitationGate.Form
        assertEquals(listOf("title", "points", "urgent", "team"), gate.fields.map { it.key })
        assertEquals(ElicitField.OneOf("team", "team", persistentListOf("web", "app")), gate.fields[3])

        val values = mapOf("title" to JsonValue.Str("Crash"), "points" to JsonValue.Num(3.0), "urgent" to JsonValue.Bool(true), "team" to JsonValue.Str("app"))
        val request = GateWire.request(gate.submit(values), 1) as GateRequest.Plugin
        assertEquals(
            AmpJson.parseToJsonElement(
                """{"message":{"type":"request","id":"plugin-ui-respond-e-1-1","method":"plugin.ui.respond",
                   "params":{"requestId":"e-1","result":{"action":"accept","content":{"title":"Crash","points":3,"urgent":true,"team":"app"}}}}}""",
            ),
            request.params,
        )
    }

    @Test
    fun undecodableGatesBecomeUnsupportedAndContributorsCannotApprove() {
        val unknown = AmpJson.parseToJsonElement(
            """{"message":{"type":"event","event":"plugin.ui.request_added","data":{"request":{"requestId":"p-9","method":"ui.wizard","params":{},"status":"pending"}}}}""",
        ) as JsonObject
        val broken = AmpJson.parseToJsonElement("""{"approvals":[{"id":"a-1","toolCallId":"TU-3","toolName":"shell_command"}]}""") as JsonObject
        val book = GateBook.Empty.apply(GateDecoders.pluginMessage(unknown)!!).apply(GateDecoders.approvalQueue(broken)!!)
            .apply(GateDecoders.approvalQueue(approvalItem)!!.let { GateFact.Approvals((it as GateFact.Approvals).gates + (GateDecoders.approvalQueue(broken) as GateFact.Approvals).gates) })

        val whys = book.pending(Capability.Write).filterIsInstance<UnsupportedGate>().associate { it.id.raw to it.why }
        assertEquals(mapOf("p-9" to UnsupportedWhy.UnknownKind, "TU-3" to UnsupportedWhy.DecodeFailed), whys)
        val approval = book.pending(Capability.Contribute).single { it.id == GateId("TU-1") }
        assertEquals(Answerability.ReadOnly(ReadOnlyReason.ContributorCannotApprove), approval.answerability)
    }
}
