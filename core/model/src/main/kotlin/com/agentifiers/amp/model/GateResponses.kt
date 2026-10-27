package com.agentifiers.amp.model

/**
 * Rebuilds a [GateResponse] that was serialized outside this module: a notification token or an outbox row.
 * Gate methods remain the only way to answer a gate the app is showing.
 */
object GateResponses {
    fun approval(gate: GateId, call: ToolCallId, accepted: Boolean, denyFeedback: String?, askAnswers: JsonValue): GateResponse =
        GateResponse.Approval(gate, call, accepted, denyFeedback, askAnswers)

    fun pluginAnswer(gate: GateId, outcome: PluginOutcome): GateResponse = GateResponse.PluginAnswer(gate, outcome)

    fun elicitation(gate: GateId, accepted: Boolean, values: JsonValue): GateResponse = GateResponse.Elicitation(gate, accepted, values)

    fun mcpServer(gate: GateId, serverName: String, specHash: String, command: String, scope: McpApprovalScope): GateResponse =
        GateResponse.McpServer(gate, serverName, specHash, command, scope)
}
