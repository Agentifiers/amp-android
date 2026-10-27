// :core:model  package amp.model
// Identifiers. Value classes so a ThreadId can never be passed where a MessageId is expected.
package amp.model

import kotlin.jvm.JvmInline

/** `T-<uuid>`. Validated once at the boundary (deep link, wire decode); trusted inside. */
@JvmInline value class ThreadId private constructor(val raw: String) {
    companion object {
        fun parse(raw: String): ThreadId? = TODO("accept ^T-[0-9a-f-]{36}$, else null")
    }
}

/** Protocol message id `M-…` (the `protocolMessageID`). Clients mint these for their own sends. */
@JvmInline value class MessageId(val raw: String) {
    companion object {
        /** Client-minted, used as the idempotency key for client_append_user_msg (spike S5 line 55). */
        fun mint(): MessageId = TODO("M- + 22 char base62 random, same shape as the web client")
    }
}

/** Client-minted key for client_edit_message, minted once and stored with the outbox row. */
@JvmInline value class EditId(val raw: String) {
    companion object { fun mint(): EditId = TODO("same shape as MessageId.mint()") }
}

/** Numeric transcript index from paged history (`messageId` in paged results, spike S9). */
@JvmInline value class MessageIndex(val value: Int)

/** Monotonic actor event sequence. The resume cursor is the highest applied Seq. */
@JvmInline value class Seq(val value: Long) : Comparable<Seq> {
    override fun compareTo(other: Seq): Int = value.compareTo(other.value)
    companion object { val Zero = Seq(0) }
}

@JvmInline value class ToolCallId(val raw: String)
@JvmInline value class QueuedMessageId(val raw: String)
@JvmInline value class GateId(val raw: String)            // toolCallId for approvals, requestId for plugin UI
@JvmInline value class UserId(val raw: String)
@JvmInline value class AccountId(val raw: String)          // stable local id; one per signed-in identity
@JvmInline value class ProjectId(val raw: String)
@JvmInline value class RunnerId(val raw: String)
@JvmInline value class LabelName(val raw: String)
@JvmInline value class ToolName(val raw: String)
@JvmInline value class IntentId(val raw: String)           // local id returned by ThreadHandle.dispatch
@JvmInline value class TerminalSessionKey(val raw: String) // [A-Za-z0-9_-]{1,128}; terminal tab 1 has none (terminal.md)
@JvmInline value class PuckConversationId(val thread: ThreadId)
