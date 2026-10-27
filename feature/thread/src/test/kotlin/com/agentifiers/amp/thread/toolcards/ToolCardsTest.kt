package com.agentifiers.amp.thread.toolcards

import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.model.ToolRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ToolCardsTest {

    @Test
    fun `byName resolves each name to the one card that claims it`() {
        assertSame(ShellCard, ToolCards.byName["shell_command"])
        assertSame(EditCard, ToolCards.byName["edit_file"])
        assertEquals(null, ToolCards.byName["code_exec"])
    }

    @Test
    fun `a name claimed twice fails the index with both cards named`() {
        val failure = assertThrows(IllegalStateException::class.java) { indexByName(ToolCards.all + ShellCard) }
        assertEquals("Tool name shell_command is claimed by ShellCard and ShellCard", failure.message)
    }

    /** Reads the design's card table, so a name added there without a card (or to the wrong card) fails here. */
    @Test
    fun `every name in the screens md card table resolves to its card, and the generic rows to none`() {
        val cards = mapOf(
            "Shell" to ShellCard, "Edit" to EditCard, "Read and search" to ReadCard, "Subagent" to SubagentCard,
            "Thread" to ThreadToolCard, "Web" to WebCard, "Media" to MediaCard, "Choice" to ChoiceCard, "TODO" to TodoCard,
            "Files" to FilesCard,
        )
        val rows = File("../../docs/design/screens.md").readLines()
            .dropWhile { it != "### Tool cards" }.drop(1).dropWhile { !it.startsWith("| Card |") }.drop(2).takeWhile { it.startsWith("|") }
            .map { row -> row.split("|").map(String::trim).let { it[1] to Regex("`([\\w<>]+)`").findAll(it[2]).map { m -> m.groupValues[1] }.toList() } }

        val handBuilt = rows.filter { it.first in cards }
        assertEquals(cards.keys, handBuilt.map { it.first }.toSet())
        assertEquals(43, handBuilt.sumOf { it.second.size })
        for ((card, names) in handBuilt) for (name in names) assertSame("$card: $name", cards.getValue(card), ToolCards.byName[name])

        val generic = rows.filter { it.first !in cards }.flatMap { it.second }
        assertEquals(listOf("code_exec", "load_plugin", "reload_plugins", "skill", "reload_skills", "reload_mcp", "tool_search", "manage_amp", "load_prompt", "mcp__<server>__<tool>"), generic)
        assertEquals(emptyList<String>(), generic.filter { it in ToolCards.byName })
    }

    @Test
    fun `labels come from displayTitle, else the shared tool name table, the MCP name, or Title Case`() {
        val run = done("ok")
        assertEquals("Bash", toolLabel(call("TU-1", "shell_command", mapOf("command" to "ls"), run)))
        assertEquals("Shell Command Status", toolLabel(call("TU-2", "shell_command_status", mapOf("pid" to 7), run)))
        assertEquals("Run the tests", toolLabel(call("TU-3", "shell_command", mapOf("command" to "ls"), run, displayTitle = "Run the tests")))
        assertEquals("postgres · query", toolLabel(call("TU-4", "mcp__postgres__query", mapOf("sql" to "select 1"), run)))
        assertEquals("Read Thread Changes", toolLabel(call("TU-5", "read_thread_changes", emptyMap(), run)))
        assertEquals("Create Thread", toolLabel(call("TU-6", "create_thread", mapOf("title" to "Child"), run)))
        assertEquals("Read GitHub Issue", toolLabel(call("TU-7", "read_github_issue", emptyMap(), run)))
    }

    @Test
    fun `search text holds the label, the summary, and every input and result string`() {
        val shell = call(
            "TU-034ki98eUguDMwxugsKU2a", "shell_command",
            mapOf("command" to "cat calc.py", "workdir" to "/private/tmp/amp-scratch"),
            done(mapOf("output" to "def add(a, b):\n    return a + b\n", "exitCode" to 0)),
        )
        assertEquals(
            "Bash\ncat calc.py · exit 0\ncat calc.py\n/private/tmp/amp-scratch\ndef add(a, b):\n    return a + b\n",
            toolSearchText(shell),
        )
        val cancelled = call("TU-8", "shell_command", mapOf("command" to "sleep 60"), ToolRun.Cancelled("User canceled"))
        assertEquals("Bash\nsleep 60\nsleep 60\nUser canceled", toolSearchText(cancelled))
    }

    @Test
    fun `paths and URLs cut in the middle, prose and commands at the end`() {
        assertEquals(TextOverflow.MiddleEllipsis, summaryOverflow("feature/thread/src/main/kotlin/Transcript.kt L10-20"))
        assertEquals(TextOverflow.MiddleEllipsis, summaryOverflow("https://ampcode.com/threads/T-01a16738-2ec2-7354-98e9-688bc413f079"))
        assertEquals(TextOverflow.Ellipsis, summaryOverflow("Recall which thread the gallery covers"))
        assertEquals(TextOverflow.Ellipsis, summaryOverflow("cat /private/tmp/amp-scratch/calc.py · exit 0"))
    }
}
