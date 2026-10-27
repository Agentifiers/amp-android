package com.agentifiers.amp.sync.internal.gates

import com.agentifiers.amp.model.ChoiceGate
import com.agentifiers.amp.model.ElicitationGate
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateOutcome
import com.agentifiers.amp.model.GateRecord
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.McpServerApprovalGate
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.PluginNotice
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.ThreadState
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.model.UnsupportedWhy
import com.agentifiers.amp.model.displayName
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Clock

/** How a gate answer or plugin command this client sent ended. */
internal sealed interface GateReply {
    data object Accepted : GateReply
    data class Refused(val reason: String) : GateReply
    data object Lost : GateReply
}

/**
 * One thread engine's view of what this device did about its gates: which answers it sent, which the server
 * refused, the records of gates that left the dock, plugin notices, and the plugin replies it awaits.
 * Confined to the engine's dispatcher like the rest of the engine's state.
 */
internal class GateDesk(private val clock: Clock = Clock.System) {
    var viewer: Capability = Capability.Write

    private val answeredHere = HashMap<GateId, String>()
    private val refused = HashSet<GateId>()
    private val awaiting = HashMap<String, Pair<GateId?, CompletableDeferred<GateReply>>>()
    private var records: PersistentList<GateRecord> = persistentListOf()
    private var notices: PersistentList<PluginNotice> = persistentListOf()
    private var noticeCount = 0L
    private var respondCount = 0

    fun nextAttempt(): Int = ++respondCount

    /** Call after the fold applied [fact]; [before] and [after] are the fold's books around it. */
    fun observe(fact: GateFact, before: GateBook, after: GateBook) {
        when (fact) {
            is GateFact.Notice -> notices = (notices + PluginNotice(++noticeCount, fact.text)).takeLast(KEEP).toPersistentList()
            is GateFact.PluginReply -> awaiting.remove(fact.requestId)?.second?.complete(fact.failure?.let(GateReply::Refused) ?: GateReply.Accepted)
            else -> Unit
        }
        val now = clock.now()
        val departed = before.pending(Capability.Write).filter { it.id !in after }
        for (gate in departed) {
            val outcome = answeredHere.remove(gate.id)?.let(GateOutcome::ByYou)
                ?: if (fact is GateFact.PluginRemoved && fact.id == gate.id) GateOutcome.Withdrawn else GateOutcome.Elsewhere
            refused.remove(gate.id)
            records = (records + GateRecord(gate.id, gate.title, outcome, now)).takeLast(KEEP).toPersistentList()
            awaiting.values.filter { it.first == gate.id }.forEach { it.second.complete(GateReply.Accepted) }
        }
    }

    /** Before sending, so a settle that arrives ahead of the reply (S15b :67 before :68) is still credited to this device. */
    fun sending(gate: GateId, line: String) {
        answeredHere[gate] = line
    }

    /** The server refused this device's answer; the gate falls back to Answer on web. */
    fun refused(gate: GateId) {
        answeredHere.remove(gate)
        refused += gate
    }

    fun unsent(gate: GateId) {
        answeredHere.remove(gate)
    }

    /** A reply for [replyId] completes the result, as does [gate] leaving the dock. */
    fun expectReply(replyId: String, gate: GateId?): CompletableDeferred<GateReply> =
        CompletableDeferred<GateReply>().also { awaiting[replyId] = gate to it }

    fun forget(replyId: String) {
        awaiting.remove(replyId)
    }

    /** The socket ended: nothing in flight can be answered on it any more. */
    fun socketEnded() {
        awaiting.values.forEach { it.second.complete(GateReply.Lost) }
        awaiting.clear()
    }

    fun decorate(state: ThreadState, book: GateBook): ThreadState = state.copy(
        gates = book.pending(viewer).map { gate ->
            if (gate.id in refused) UnsupportedGate(gate.id, gate.raisedAt, gate.answerability, gate.source, gate.title, UnsupportedWhy.AnswerRejected) else gate
        }.toPersistentList(),
        gateRecords = records,
        pluginNotices = notices,
        pluginCommands = book.commands,
    )

    private companion object {
        const val KEEP = 20
    }
}

internal val HumanGate.title: String
    get() = when (this) {
        is ToolApprovalGate -> tool.displayName()
        is ChoiceGate -> questions.firstOrNull()?.prompt ?: "Question"
        is PluginGate.Confirm -> title
        is PluginGate.Input -> title
        is PluginGate.SecretInput -> title
        is PluginGate.Select -> title
        is UnsupportedGate -> kindLabel
        is ElicitationGate.Form -> message
        is ElicitationGate.OpenUrl -> message
        is McpServerApprovalGate -> serverName
    }

/** The record line the dock keeps once the gate this answer settles leaves. */
internal val GateResponse.line: String
    get() = when (this) {
        is GateResponse.Approval -> when {
            askAnswers is JsonValue.Obj -> "Answered by you"
            accepted -> "Allowed by you"
            else -> "Denied by you"
        }
        is GateResponse.PluginAnswer -> when (val outcome = outcome) {
            is PluginOutcome.Cancelled -> "Cancelled by you"
            is PluginOutcome.Result -> when (outcome.value) {
                JsonValue.Bool(true) -> "Confirmed by you"
                JsonValue.Bool(false) -> "Declined by you"
                else -> "Answered by you"
            }
        }
        is GateResponse.Elicitation -> if (accepted) "Answered by you" else "Declined by you"
        is GateResponse.McpServer -> "Allowed by you"
    }
