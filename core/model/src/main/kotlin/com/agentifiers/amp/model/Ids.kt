package com.agentifiers.amp.model

import java.security.SecureRandom

@JvmInline
value class ThreadId private constructor(val raw: String) {
    override fun toString(): String = raw

    companion object {
        private val shape = Regex("^T-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun parse(raw: String): ThreadId? = if (shape.matches(raw)) ThreadId(raw) else null
    }
}

/** Protocol message id, `M-` plus 22 base62 characters. Clients mint it for their own sends. */
@JvmInline
value class MessageId(val raw: String) {
    override fun toString(): String = raw

    companion object {
        fun mint(): MessageId = MessageId("M-" + base62(22))
    }
}

/** Client-minted key for client_edit_message, minted once and stored with the outbox row. */
@JvmInline
value class EditId(val raw: String) {
    companion object {
        fun mint(): EditId = EditId("M-" + base62(22))
    }
}

/** Numeric transcript index from paged history (`messageId` in paged results, spike S9). */
@JvmInline
value class MessageIndex(val value: Int)

private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private val random = SecureRandom()

private fun base62(length: Int): String {
    val bytes = ByteArray(length).also(random::nextBytes)
    return bytes.joinToString("") { BASE62[(it.toInt() and 0xff) % 62].toString() }
}

/** Thread actor event sequence. The resume cursor is the highest applied value. */
@JvmInline
value class Seq(val value: Long) : Comparable<Seq> {
    override fun compareTo(other: Seq): Int = value.compareTo(other.value)

    companion object {
        val Zero = Seq(0)
    }
}

@JvmInline
value class ToolCallId(val raw: String)

@JvmInline
value class QueuedMessageId(val raw: String)

/** toolCallId for tool approvals, requestId for plugin UI dialogs. */
@JvmInline
value class GateId(val raw: String)

@JvmInline
value class UserId(val raw: String)

/** Stable local id, one per signed-in identity. */
@JvmInline
value class AccountId(val raw: String)

@JvmInline
value class ProjectId(val raw: String)

@JvmInline
value class RunnerId(val raw: String)

@JvmInline
value class LabelName(val raw: String)

@JvmInline
value class ToolName(val raw: String)

/** Local id returned by ThreadHandle.dispatch and AccountScope.dispatch. */
@JvmInline
value class IntentId(val raw: String) {
    companion object {
        fun mint(): IntentId = IntentId("I-" + base62(16))
    }
}

/** `[A-Za-z0-9_-]{1,128}`. Terminal tab 1 has none (terminal.md). */
@JvmInline
value class TerminalSessionKey private constructor(val raw: String) {
    companion object {
        private val shape = Regex("^[A-Za-z0-9_-]{1,128}$")

        fun parse(raw: String): TerminalSessionKey? = if (shape.matches(raw)) TerminalSessionKey(raw) else null
    }
}

@JvmInline
value class PuckConversationId(val thread: ThreadId)
