package com.agentifiers.amp.sync.internal.gates

import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.ChoiceGate
import com.agentifiers.amp.model.ElicitationGate
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.McpServerApprovalGate
import com.agentifiers.amp.model.PluginCommand
import com.agentifiers.amp.model.PluginGate
import com.agentifiers.amp.model.ReadOnlyReason
import com.agentifiers.amp.model.ToolApprovalGate
import com.agentifiers.amp.model.UnsupportedGate
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * The gates the server says are pending. Approvals are a Snapshot aspect, replaced whole by each
 * `tool_approval_queue`. Plugin dialogs are Keyed by requestId and rebuilt whole by `plugin.ui.snapshot`,
 * so the dock never depends on having seen `request_added` (S15c). Pure: applying a fact twice equals once.
 */
internal data class GateBook(
    val approvals: PersistentList<HumanGate>,
    val dialogs: PersistentMap<GateId, HumanGate>,
    val commands: PersistentList<PluginCommand>,
) {
    fun apply(fact: GateFact): GateBook = when (fact) {
        is GateFact.Approvals -> copy(approvals = fact.gates.toPersistentList())
        is GateFact.PluginSnapshot -> copy(dialogs = fact.gates.associateBy { it.id }.toPersistentMap())
        is GateFact.PluginRaised -> copy(dialogs = fact.gate?.let { dialogs.put(fact.id, it) } ?: dialogs.remove(fact.id))
        is GateFact.PluginSettled -> copy(dialogs = dialogs.remove(fact.id))
        is GateFact.PluginRemoved -> copy(dialogs = dialogs.remove(fact.id))
        is GateFact.Commands -> copy(commands = fact.commands.toPersistentList())
        is GateFact.Notice, is GateFact.PluginReply -> this
    }

    /** Oldest first, each lowered to what [viewer] may do (screens.md, "Answerability"). */
    fun pending(viewer: Capability): List<HumanGate> = (approvals + dialogs.values).sortedBy { it.raisedAt }.map { it.answerableBy(viewer) }

    operator fun contains(id: GateId): Boolean = approvals.any { it.id == id } || id in dialogs

    companion object {
        val Empty = GateBook(persistentListOf(), persistentMapOf(), persistentListOf())
    }
}

/** The `cap` claim of the thread socket's wsToken (private-protocol.md:31). */
internal enum class Capability {
    Read, Contribute, Write;

    companion object {
        /** A token without a readable `cap` is treated as `write`; the server still refuses what it must. */
        fun ofClaim(cap: String?): Capability = when (cap) {
            "read" -> Read
            "contribute" -> Contribute
            else -> Write
        }

        /** Reads `cap` from the JWT payload; a token that is not a JWT, such as :fake-amp's, reads as `write`. */
        fun ofWsToken(wsToken: String): Capability {
            val payload = wsToken.split('.').takeIf { it.size == 3 }?.get(1) ?: return Write
            val claims = runCatching { AmpJson.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()) as? JsonObject }.getOrNull()
            return ofClaim(claims?.string("cap"))
        }
    }
}

internal fun HumanGate.answerableBy(viewer: Capability): HumanGate {
    val reason = when (viewer) {
        Capability.Write -> return this
        Capability.Read -> ReadOnlyReason.ReaderOnly
        Capability.Contribute -> when (this) {
            is PluginGate.SecretInput -> ReadOnlyReason.ContributorCannotEnterSecrets
            is PluginGate.Confirm, is PluginGate.Input, is PluginGate.Select, is UnsupportedGate -> return this
            is ToolApprovalGate, is ChoiceGate, is ElicitationGate, is McpServerApprovalGate -> ReadOnlyReason.ContributorCannotApprove
        }
    }
    return withAnswerability(Answerability.ReadOnly(reason))
}

internal fun HumanGate.withAnswerability(answerability: Answerability): HumanGate = when (this) {
    is ToolApprovalGate -> copy(answerability = answerability)
    is ChoiceGate -> copy(answerability = answerability)
    is PluginGate.Confirm -> copy(answerability = answerability)
    is PluginGate.Input -> copy(answerability = answerability)
    is PluginGate.SecretInput -> copy(answerability = answerability)
    is PluginGate.Select -> copy(answerability = answerability)
    is UnsupportedGate -> copy(answerability = answerability)
    is ElicitationGate.Form -> copy(answerability = answerability)
    is ElicitationGate.OpenUrl -> copy(answerability = answerability)
    is McpServerApprovalGate -> copy(answerability = answerability)
}
