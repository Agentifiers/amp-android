package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.runtime.Composable
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.ToolCall
import com.agentifiers.amp.model.ToolRun

data class TodoItem(val content: String, val done: Boolean, val status: String?)

/** The list a legacy TODO call wrote or read. TODOs are a removed feature; these cards only replay older threads. */
data class TodoCall(val items: List<TodoItem>)

object TodoCard : ToolCard<TodoCall> {
    override val names = listOf("todo_read", "todo_write", "task_list")

    private val DONE = setOf("completed", "done")

    /** `todo_write {todos}` carries the list in its input; `todo_read` and `task_list` return it, bare or under a key. */
    override fun decode(call: ToolCall): TodoCall? {
        val input = call.input.value
        val result = call.run.result
        val list = sequenceOf(input["todos"], input["tasks"], result, result["todos"], result["tasks"]).firstOrNull { candidate ->
            candidate.list.isNotEmpty() && candidate.list.all { itemOf(it) != null }
        } ?: return null
        return TodoCall(list.list.map { itemOf(it)!! })
    }

    private fun itemOf(value: JsonValue): TodoItem? {
        val content = value.text("content") ?: value.text("title") ?: return null
        val status = value.text("status")
        return TodoItem(content, status?.lowercase() in DONE, status)
    }

    override fun summary(call: TodoCall, run: ToolRun): String = "${call.items.count { it.done }} of ${call.items.size} done"

    @Composable
    override fun Body(call: TodoCall, run: ToolRun, ctx: ToolCardContext) {
        for (item in call.items) {
            val mark = if (item.done) "☑" else "☐"
            val status = item.status?.takeUnless { item.done }?.let { " ($it)" }.orEmpty()
            FindText("$mark ${item.content}$status", ctx, color = if (item.done) AmpTheme.colors.text.inkMuted else AmpTheme.colors.text.ink)
        }
    }
}
