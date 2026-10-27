package com.agentifiers.amp.sync.internal.gates

import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.PluginCommand

/** What `tool_approval_queue` and `plugin_message` events say about human gates. */
internal sealed interface GateFact {
    /** `tool_approval_queue{approvals}`: the whole approval queue, replacing the previous one. */
    data class Approvals(val gates: List<HumanGate>) : GateFact

    /** `plugin.ui.snapshot{requests}`: every pending dialog, replacing what the fold had (S15c :101). */
    data class PluginSnapshot(val gates: List<HumanGate>) : GateFact

    /** `plugin.ui.request_added` with status `pending` (S15b :64). Null [gate] when the dialog targets another connection. */
    data class PluginRaised(val id: GateId, val gate: HumanGate?) : GateFact

    /** `plugin.ui.request_settled`, status `responded` or `errored` (S15b :67, S15c :115). */
    data class PluginSettled(val id: GateId) : GateFact

    /** `plugin.ui.request_removed{requestId}`: withdrawn without an answer (read, not observed). */
    data class PluginRemoved(val id: GateId) : GateFact

    /** `ui.notify{message}` (S15b :73). Volatile. */
    data class Notice(val text: String) : GateFact

    /** `command.list{commands}` (S15b :50). */
    data class Commands(val commands: List<PluginCommand>) : GateFact

    /** `plugin_message{message{type:"response", id}}` answering a request this client sent (S15b :68, :74). */
    data class PluginReply(val requestId: String, val failure: String?) : GateFact
}
