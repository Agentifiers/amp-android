package com.agentifiers.amp.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * One line of the `<suggested-replies>` block an agent may end its reply with. A tap sends [text]. [lead] is the words
 * the agent bolded at the start of the line, which Amp's web uses as the button's label; empty when it bolded none.
 */
data class SuggestedReply(val lead: String, val rest: String) {
    val text: String get() = lead + rest
}

/** An assistant text block split the way Amp's clients split it: the prose to show, and the replies its block offers. */
data class AssistantText(val prose: String, val replies: PersistentList<SuggestedReply>) {
    companion object {
        private const val OPEN = "<suggested-replies>"
        private const val CLOSE = "</suggested-replies>"
        private val LIST_MARKER = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")
        private val BOLD_LEAD = Regex("""^\*\*(.+?)\*\*(.*)$""")

        /**
         * Amp's web parser (`ut` in its thread bundle) with the CLI's anchor, `(?:^|\n)<suggested-replies>`: the last
         * opening tag that starts a line opens the block, its own line holds nothing else, and nothing but whitespace
         * follows the closing tag. A block still streaming has no closing tag, and a trailing `<sugg` is the opening tag
         * still arriving; both are hidden. Text that only mentions the tag stays as written.
         */
        fun parse(raw: String): AssistantText {
            val start = lastLineStart(raw)
            if (start < 0) return AssistantText(withoutPartialTag(raw), persistentListOf())
            val body = raw.substring(start + OPEN.length)
            if (body.isNotEmpty() && !body.startsWith('\n')) return AssistantText(raw, persistentListOf())
            val end = body.indexOf(CLOSE)
            if (end >= 0 && body.substring(end + CLOSE.length).isNotBlank()) return AssistantText(raw, persistentListOf())
            val replies = (if (end >= 0) body.substring(0, end) else body).split('\n')
                .map { it.replaceFirst(LIST_MARKER, "").trim() }
                .filter { it.isNotEmpty() && !CLOSE.startsWith(it) }
                .map(::reply)
                .filter { it.text.isNotBlank() }
            return AssistantText(raw.substring(0, start).trimEnd(), replies.toPersistentList())
        }

        private fun lastLineStart(raw: String): Int {
            var at = raw.lastIndexOf(OPEN)
            while (at > 0 && raw[at - 1] != '\n') at = raw.lastIndexOf(OPEN, at - 1)
            return at
        }

        private fun withoutPartialTag(raw: String): String {
            val at = raw.lastIndexOf('<')
            val arriving = at >= 0 && raw.length - at >= 2 && (at == 0 || raw[at - 1] == '\n') && OPEN.startsWith(raw.substring(at))
            return if (arriving) raw.substring(0, at).trimEnd() else raw
        }

        /** Amp sends the line without its `**`; the web leaves a later `**` in place, which this drops too. */
        private fun reply(line: String): SuggestedReply {
            val bold = BOLD_LEAD.matchEntire(line) ?: return SuggestedReply("", line.replace("**", ""))
            return SuggestedReply(bold.groupValues[1].trim(), bold.groupValues[2].replace("**", "").trimEnd())
        }
    }
}

/** Amp's web shows three at most. */
private const val OFFERED = 3

/**
 * The replies Amp's clients offer above the composer: the last turn's, once that turn is done, the agent is not
 * working, and nothing this device sent is still waiting. The composer also hides them while its draft has text.
 */
fun ThreadState.offeredReplies(): List<SuggestedReply> {
    val last = (transcript as? TranscriptView.Ready)?.turns?.lastOrNull() ?: return emptyList()
    if (last.end != TurnEnd.Done || activity is AgentActivity.Busy || pending.isNotEmpty() || link is Link.Stopped) return emptyList()
    return last.replies.take(OFFERED)
}
