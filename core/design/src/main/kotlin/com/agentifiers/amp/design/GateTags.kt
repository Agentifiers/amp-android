package com.agentifiers.amp.design

import com.agentifiers.amp.model.GateId

/**
 * The gate dock. Only the expanded gate renders the action, field, and option tags, so they carry no
 * gate id and still match one node.
 */
object GateTags {
    const val dock = "gate-dock"
    fun gate(id: GateId) = "gate-${id.raw}"

    const val approvalAllow = "approval-allow"
    const val approvalDeny = "approval-deny"
    const val approvalDenyFeedback = "approval-deny-feedback"
    const val approvalDenySend = "approval-deny-send"

    fun field(key: String) = "gate-field-$key"
    fun option(index: Int) = "gate-option-$index"
    const val submit = "gate-submit"
    const val submitBlocker = "gate-submit-blocker"
    const val decline = "gate-decline"
    const val answerOnWeb = "answer-on-web"

    const val source = "gate-source"
    const val readOnlyReason = "gate-readonly-reason"
    fun choiceOther(question: Int) = "gate-field-other-$question"
    fun fieldOption(key: String, index: Int) = "gate-field-$key-option-$index"
    const val secretToggle = "gate-secret-toggle"
    const val countdown = "gate-countdown"
    const val openUrl = "gate-open-url"
    const val mcpAlwaysForMe = "gate-mcp-always-for-me"
    fun record(id: GateId) = "gate-record-${id.raw}"
    const val answeredElsewhere = "gate-answered-elsewhere"
    const val notice = "gate-notice"
    const val noticeDismiss = "gate-notice-dismiss"
}
