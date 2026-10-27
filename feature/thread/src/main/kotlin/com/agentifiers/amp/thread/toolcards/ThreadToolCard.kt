package com.agentifiers.amp.thread.toolcards

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.markdown.Markdown
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

data class ThreadRef(val id: ThreadId, val title: String?)

/** [subject] is the title, message, question, or query the call carries; [threads] every thread it names. */
data class ThreadToolCall(val subject: String?, val threads: List<ThreadRef>, val result: String?)

object ThreadToolCard : ToolCard<ThreadToolCall> {
    override val names = listOf(
        "create_thread", "send_thread_message", "wait_for_threads", "read_thread", "find_thread", "read_messages", "search_messages",
    )

    private val SUBJECT = listOf("title", "message", "question", "goal", "query")

    /** A bare id or any URL holding one, like `https://ampcode.com/threads/T-…`. */
    private val THREAD_ID = Regex("T-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    override fun decode(call: ToolCall): ThreadToolCall? {
        val input = call.input.value
        val result = call.run.result
        val refs = LinkedHashMap<ThreadId, String?>()
        fun add(raw: String?, title: String?) {
            val id = raw?.let { THREAD_ID.find(it)?.value }?.let(ThreadId::parse) ?: return
            if (refs[id] == null) refs[id] = title
        }
        add(input["thread"].string, null)
        add(input["threadID"].string, null)
        input["threads"].list.forEach { add(it.string, null) }
        val created = if (call.name.raw == "create_thread") input.text("title") else null
        add(result["threadID"].string, created)
        add(result["url"].string, created)
        result["threads"].list.forEach { add(it.string ?: it["id"].string, it.text("title")) }
        val subject = SUBJECT.firstNotNullOfOrNull { input.text(it) }
        val prose = result.prose()
        if (subject == null && refs.isEmpty() && prose == null) return null
        return ThreadToolCall(subject, refs.map { (id, title) -> ThreadRef(id, title) }, prose)
    }

    override fun summary(call: ThreadToolCall, run: ToolRun): String =
        call.subject?.let(::firstLine) ?: if (call.threads.isEmpty()) "" else count(call.threads.size, "thread")

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Body(call: ThreadToolCall, run: ToolRun, ctx: ToolCardContext) {
        call.subject?.let { FindText(it, ctx) }
        if (call.threads.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (thread in call.threads) {
                    AssistChip(
                        onClick = { ctx.navigate(PaneDestination.Thread(thread.id)) },
                        label = { Text(thread.title ?: thread.id.raw, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag(ThreadTags.toolThread(thread.id)),
                    )
                }
            }
        }
        call.result?.let { Markdown(it, highlight = ctx.highlight, onLink = ctx.openUrl) }
    }
}
