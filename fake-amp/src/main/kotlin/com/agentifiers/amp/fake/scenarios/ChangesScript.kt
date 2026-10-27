package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Event
import com.agentifiers.amp.fake.Reply
import com.agentifiers.amp.fake.SocketExchange
import com.agentifiers.amp.fake.Turn
import com.agentifiers.amp.fake.TurnStep
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The head of the S7 repository (`captures/s07-changes.jsonl:62`). */
internal const val S7_HEAD = "4bd66138fb8e40058af10a28e7512761f31a0de3"

/**
 * Scripted live git answers in the S7 shapes (`client_git_command_result{ok, exitCode, stdout, stderr}` and
 * `client_git_diff_snapshot_result{ok, snapshot}`, s07-changes.jsonl:59-71) for the requests the Changes pane makes
 * that S7 never recorded: branch, base ref and head, ahead commits, the Unstaged and Staged sections, staging,
 * intelligent order and Explain. A request repeated with the same params takes the next answer, then the last.
 */
internal object ChangesScript {
    fun command(type: String, stdout: String, vararg extra: Pair<String, String>): SocketExchange = command(type, 0, stdout, "", *extra)

    /** git ran and exited [exitCode], answered in the `ok:true` branch of the result schema, which requires exitCode, stdout and stderr. */
    fun command(type: String, exitCode: Int, stdout: String, stderr: String, vararg extra: Pair<String, String>): SocketExchange = SocketExchange(
        "client_git_command",
        json("operation" to json("type" to type, *extra)),
        listOf(receipt(), result("client_git_command_result", json("ok" to true, "exitCode" to exitCode, "stdout" to stdout, "stderr" to stderr))),
    )

    /** branch, comparison_base_ref, comparison_base_head and ahead_commits, by default a branch with no commits ahead. */
    fun meta(branch: String = "main", baseRef: String = "refs/remotes/origin/main", baseHead: String = S7_HEAD, ahead: List<JsonObject> = emptyList()): List<SocketExchange> = listOf(
        command("branch", "$branch\n"),
        command("comparison_base_ref", "$baseRef\n"),
        command("comparison_base_head", "$baseHead\n"),
        command("ahead_commits", JsonArray(ahead).toString()),
    )

    fun snapshot(base: String, head: String, includeUntracked: Boolean, diffHash: String, files: List<JsonObject>): SocketExchange = SocketExchange(
        "client_git_diff_snapshot",
        json("baseRevision" to base, "headRevision" to head, "includeUntracked" to includeUntracked, "content" to "renderable"),
        listOf(
            receipt(),
            result(
                "client_git_diff_snapshot_result",
                json(
                    "ok" to true,
                    "snapshot" to json(
                        "provider" to "git", "capturedAt" to 1792729764465L, "repositoryRoot" to "/private/tmp/amp-scratch",
                        "repositoryName" to "amp-scratch", "branch" to "main", "head" to S7_HEAD, "baseRevision" to base,
                        "headRevision" to head, "includeUntracked" to includeUntracked, "content" to "renderable",
                        "diffHash" to diffHash, "files" to files,
                    ),
                ),
            ),
        ),
    )

    /**
     * The order request the app builds from [sections] (sectionID, diffHash, revisions, includeUntracked,
     * `{path, changeType, diff}`), answered `result{status:"success", sections}` (s20-changes-params.jsonl:1018-1020).
     */
    fun order(sections: List<OrderSection>, results: List<JsonObject>): SocketExchange =
        order(sections, json("status" to "success", "sections" to results))

    /** The same request answered `result{status:"failure", error}`, the other branch of the web's result schema. */
    fun orderFails(sections: List<OrderSection>, error: String): SocketExchange =
        order(sections, json("status" to "failure", "error" to error))

    private fun order(sections: List<OrderSection>, result: JsonObject): SocketExchange = SocketExchange(
        "client_changes_file_order",
        json("sections" to sections.map { section ->
            json(
                "sectionID" to section.id, "diffHash" to section.diffHash, "baseRevision" to section.base, "headRevision" to section.head,
                "includeUntracked" to section.includeUntracked,
                "files" to section.files.map { json("path" to it.string("path"), "changeType" to it.string("changeType"), "diff" to (it.string("diff") ?: "")) },
            )
        }),
        listOf(receipt(), result("client_changes_file_order_result", json("result" to result))),
    )

    fun explain(params: JsonObject, explanation: String): SocketExchange = SocketExchange(
        "client_explain_diff_selection",
        params,
        listOf(receipt(), result("client_explain_diff_selection_result", json("ok" to true, "explanation" to explanation))),
    )

    /** A turn that starts working and never finishes, so the next send queues behind it. Only `client_cancel` ends it. */
    fun workingTurn(): Turn = Turn(
        trigger = "scripted-working-turn",
        steps = listOf(TurnStep.Emit(200, Event("agent_state", json("state" to "running_tools", "agentMode" to "smart")))),
        parks = true,
    )

    class OrderSection(val id: String, val diffHash: String, val base: String, val head: String, val includeUntracked: Boolean, val files: List<JsonObject>)

    private fun receipt() = Reply.Response(json("ok" to true))

    private fun result(type: String, body: JsonObject) = Reply.Notify(Event(type, body + mapOf("requestId" to element("recorded"))))
}
