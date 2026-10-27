// :feature:thread  package amp.thread.tools
// Tool cards. One file per tool family, listed once in [ToolCards]. Decoding is tolerant: decode()
// returns null on any shape surprise and the GenericToolCard renders instead, so a server-side input
// change degrades one card, never the transcript.
package amp.thread.tools

import amp.model.*
import androidx.compose.runtime.Composable

interface ToolCard<T : Any> {
    /** Names this card handles. Aliases share a card (e.g. shell_command, async_shell_command, run_terminal_command, Bash). */
    val names: List<String>
    fun decode(call: ToolCall): T?
    @Composable fun Content(call: T, run: ToolRun, ctx: ToolCardContext)
    /** One-line collapsed summary for StepGroup rows ("Bash · cargo test … exit 101"). */
    fun summary(call: T, run: ToolRun): String
}

/**
 * Everything a card may use. No handle, no engine: cards are pure renderers that emit intents.
 * Opening a pane (diff, file, portal, thread) goes through `navigate`, which the layout places.
 */
interface ToolCardContext {
    val callId: ToolCallId
    val expanded: Boolean
    fun toggle()
    fun navigate(dest: PaneDestination)
    fun dispatch(intent: ThreadIntent)
    /** AccountScope.images from :sync: Bearer to the account's origins, refresh on 401. */
    val images: amp.sync.api.AuthenticatedImageLoader
    /** Children (subagent/nested calls) render through the same map. */
    @Composable fun Children(calls: List<ToolCall>)
}

/**
 * Every built-in card, in one list. ToolCardsTest fails when a tool name appears twice, because
 * `associateBy` would silently keep the last card.
 */
object ToolCards {
    val all: List<ToolCard<*>> = listOf(/* ShellCard, EditCard, ReadCard, SubagentCard, ThreadToolCard, WebCard,
        MediaCard, ChoiceCard, CodeExecCard, TodoCard, FilesCard */)
    val byName: Map<String, ToolCard<*>> = all.flatMap { card -> card.names.map { it to card } }.toMap()
}

/** Fallback for any tool: displayTitle or name, status chip, pretty-printed input and result (collapsed). */
object GenericToolCard {
    @Composable fun Content(call: ToolCall, ctx: ToolCardContext): Unit = TODO()
}

/** Resolve by name in ToolCards.byName → decode → render, with the generic fallback at both steps. */
@Composable
fun ToolCallView(call: ToolCall, ctx: ToolCardContext): Unit = TODO()

// ── Built-in families (one file each in renderers/), with their names ────────
// ShellCard        "shell_command","async_shell_command","run_terminal_command","Bash","shell_command_status","shell_command_kill"
// EditCard         "edit_file","apply_patch","create_file","write_file","delete_file"  → inline diff; "Open in Changes" navigates
// ReadCard         "read_file","Read","finder","ripgrep","Grep","glob","Glob"
// SubagentCard     "Task","oracle","librarian","subagent_stop"                     → Children()
// ThreadToolCard   "create_thread","send_thread_message","wait_for_threads","read_thread","find_thread" → thread chips navigate
// WebCard          "read_web_page","web_search"
// MediaCard        "view_media","present_media","painter"
// ChoiceCard       "ask_user_choice"    → shows answered choices; the live question is a ChoiceGate in the dock
// CodeExecCard     "code_exec"          → nested MCP calls via Children(); program blocks hidden (core.md:431)
// TodoCard         "todo_read","todo_write","task_list"
// FilesCard        "upload_thread_file","download_thread_file","download_thread_changes"
// McpAppCard       matched by nestedToolCalls target.resourceUri with the mcp-app profile, not by name → WebSurface.McpApp
// PluginToolCard   plugin tool rendering metadata → generic structured card
