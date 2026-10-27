package com.agentifiers.amp.model

/**
 * The one label a tool goes by: in the transcript's tool card, on the approval gate, and in the approval
 * notification (UX-29). The web's label where it differs from Title Case, else MCP "<server> · <tool>", else the
 * name in Title Case (ampcode.com's web bundle, `function ue`).
 */
fun ToolName.displayName(): String = LABELS[raw] ?: mcpLabel(raw) ?: titleCase(raw)

/** Only the labels Title Case would get wrong; every other card name reads right through [titleCase]. */
private val LABELS = mapOf(
    "shell_command" to "Bash",
    "async_shell_command" to "Bash",
    "run_terminal_command" to "Bash",
    "edit_file" to "Edit",
    "create_file" to "Create",
    "write_file" to "Create",
    "read_file" to "Read",
    "finder" to "Search",
    "ripgrep" to "Grep",
    "Task" to "Subagent",
    "subagent_stop" to "Stop Subagent",
    "read_web_page" to "Web Page",
    "todo_read" to "Read TODOs",
    "todo_write" to "Update TODOs",
)

private fun mcpLabel(name: String): String? {
    val rest = name.removePrefix("mcp__").takeIf { it != name } ?: return null
    val split = rest.indexOf("__").takeIf { it > 0 } ?: return null
    return "${rest.substring(0, split)} · ${rest.substring(split + 2)}"
}

private val ACRONYMS = mapOf("github" to "GitHub", "gitlab" to "GitLab", "ci" to "CI", "oauth" to "OAuth", "url" to "URL")

private fun titleCase(name: String): String = name.split(Regex("[_\\s-]+")).filter { it.isNotEmpty() }.joinToString(" ") { word ->
    val lower = word.lowercase()
    ACRONYMS[lower] ?: lower.replaceFirstChar { it.uppercaseChar() }
}
