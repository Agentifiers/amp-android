package com.agentifiers.amp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolNamesTest {
    @Test
    fun shellToolsAllReadBash() {
        assertEquals("Bash", ToolName("shell_command").displayName())
        assertEquals("Bash", ToolName("async_shell_command").displayName())
        assertEquals("Bash", ToolName("run_terminal_command").displayName())
        assertEquals("Bash", ToolName("Bash").displayName())
    }

    @Test
    fun webLabelsWinOverTitleCase() {
        assertEquals("Edit", ToolName("edit_file").displayName())
        assertEquals("Create", ToolName("write_file").displayName())
        assertEquals("Subagent", ToolName("Task").displayName())
        assertEquals("Read TODOs", ToolName("todo_read").displayName())
    }

    @Test
    fun mcpToolsReadServerAndTool() {
        assertEquals("postgres · query", ToolName("mcp__postgres__query").displayName())
    }

    @Test
    fun anyOtherNameReadsInTitleCase() {
        assertEquals("Shell Command Status", ToolName("shell_command_status").displayName())
        assertEquals("Read GitHub Issue", ToolName("read_github_issue").displayName())
        assertEquals("Frobnicate", ToolName("Frobnicate").displayName())
        assertEquals("Mcp Broken", ToolName("mcp__broken").displayName())
    }
}
