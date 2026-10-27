package com.agentifiers.amp.thread.toolcards

import com.agentifiers.amp.model.ImageSource
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ToolRun
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each card against a capture, a thread export, or the scripted `thread-tools` shapes. */
class CardDecodeTest {
    private val scratch = "/private/tmp/amp-scratch"

    @Test
    fun `shell decodes the S4 cat call and its exit code`() {
        val call = call(
            "TU-034ki98eUguDMwxugsKU2a", "shell_command",
            mapOf("command" to "cat calc.py", "workdir" to scratch),
            done(mapOf("output" to "def add(a, b):\n    return a + b\n", "exitCode" to 0)),
        )
        val shell = ShellCard.decode(call)!!
        assertEquals(ShellCall(ShellTarget.Command("cat calc.py", scratch), "def add(a, b):\n    return a + b\n", 0), shell)
        assertEquals("def add(a, b):", shell.output!!.lines().first())
        assertEquals("cat calc.py · exit 0", ShellCard.summary(shell, call.run))
        assertEquals("cat calc.py · exit 0", toolSummary(call))

        val status = call("TU-2", "shell_command_status", mapOf("pid" to 4242), ToolRun.Active(jv(mapOf("output" to "building…\n"))))
        assertEquals(ShellCall(ShellTarget.Process(4242), "building…\n", null), ShellCard.decode(status))
        assertEquals("PID 4242", toolSummary(status))
    }

    @Test
    fun `edit decodes the research edit_file diff and the S4 create_file content`() {
        val diff = "```diff\n--- $scratch/calc.py\toriginal\n+++ $scratch/calc.py\tmodified\n@@ -1,2 +1,2 @@\n def add(a, b):\n-    return a - b\n+    return a + b\n```"
        val edit = call(
            "TU-034khTjwPVX5H0Qlge0AFg", "edit_file",
            mapOf("path" to "$scratch/calc.py", "new_str" to "    return a + b", "old_str" to "    return a - b"),
            done(mapOf("diff" to diff, "lineRange" to listOf(2, 2))),
        )
        assertEquals(EditCall("$scratch/calc.py", EditChange.Replace("    return a - b", "    return a + b", diff)), EditCard.decode(edit))
        assertEquals("/private/tmp/amp-scratch/calc.py · +1 -1", toolSummary(edit))

        val create = call(
            "TU-034ki9Cph3nw4Kf6V029Vz", "create_file",
            mapOf("content" to "def add_numbers(a, b):\n    return a + b\n", "path" to "$scratch/calc.py"),
            done("Successfully created file /private/tmp/amp-scratch/calc.py"),
        )
        assertEquals(EditCall("$scratch/calc.py", EditChange.Create("def add_numbers(a, b):\n    return a + b\n")), EditCard.decode(create))
        assertEquals("Create", toolLabel(create))
        assertEquals("/private/tmp/amp-scratch/calc.py · 2 lines", toolSummary(create))

        val patch = call(
            "TU-3", "apply_patch",
            mapOf("patchText" to "*** Begin Patch\n*** Update File: calc.py\n@@\n-x\n+y\n*** Add File: test_calc.py\n+import calc\n*** End Patch"),
            ToolRun.Pending,
        )
        assertEquals("calc.py and 1 more", toolSummary(patch))
    }

    @Test
    fun `read decodes a ranged Read and a Grep`() {
        val read = call(
            "TU-ToolsGallery0000000004", "Read",
            mapOf("path" to "$scratch/calc.py", "read_range" to listOf(1, 2)),
            done(mapOf("absolutePath" to "$scratch/calc.py", "content" to "def add_numbers(a, b):\n    return a + b\n")),
        )
        assertEquals(ReadCall(ReadTarget.File("$scratch/calc.py", 1..2), "def add_numbers(a, b):\n    return a + b\n"), ReadCard.decode(read))
        assertEquals("/private/tmp/amp-scratch/calc.py L1-2", toolSummary(read))

        val grep = call("TU-5", "Grep", mapOf("pattern" to "add_numbers", "path" to scratch), done(listOf("calc.py:1:def add_numbers(a, b):")))
        assertEquals(ReadCall(ReadTarget.Search("add_numbers", scratch), "calc.py:1:def add_numbers(a, b):"), ReadCard.decode(grep))
        assertEquals("\"add_numbers\" in /private/tmp/amp-scratch", toolSummary(grep))
    }

    @Test
    fun `subagent decodes a Task and a librarian query with context`() {
        val task = call(
            "TU-ToolsGallery0000000005", "Task",
            mapOf("description" to "Check for other callers", "prompt" to "Search $scratch for any remaining callers of `add`."),
            done("No other callers."),
        )
        assertEquals(
            SubagentCall(SubagentKind.Task, "Check for other callers", "Search /private/tmp/amp-scratch for any remaining callers of `add`.", "No other callers."),
            SubagentCard.decode(task),
        )
        assertEquals("Subagent", toolLabel(task))
        assertEquals("Check for other callers", toolSummary(task))

        val librarian = call("TU-6", "librarian", mapOf("query" to "How does coil load data URIs?", "context" to "Android app"), ToolRun.Pending)
        assertEquals(
            SubagentCall(SubagentKind.Librarian, "How does coil load data URIs?", "Context: Android app\n\nQuery: How does coil load data URIs?", null),
            SubagentCard.decode(librarian),
        )
    }

    @Test
    fun `thread tool decodes the threads a call names and a created thread's URL`() {
        val gallery = ThreadId.parse("T-0a0b0c0d-0000-7000-8000-000000000002")!!
        val read = call(
            "TU-ToolsGallery0000000006", "read_thread",
            mapOf("threadID" to gallery.raw, "goal" to "Recall which tools the gallery covers"),
            done("The gallery thread shows one call per tool card."),
        )
        assertEquals(
            ThreadToolCall("Recall which tools the gallery covers", listOf(ThreadRef(gallery, null)), "The gallery thread shows one call per tool card."),
            ThreadToolCard.decode(read),
        )
        assertEquals("Recall which tools the gallery covers", toolSummary(read))

        val child = ThreadId.parse("T-01a166e9-3fa0-77ca-b925-383e88ba52ac")!!
        val create = call(
            "TU-7", "create_thread", mapOf("title" to "Port calc to Kotlin"),
            done(mapOf("url" to "https://ampcode.com/threads/${child.raw}", "agentMode" to "smart")),
        )
        assertEquals(listOf(ThreadRef(child, "Port calc to Kotlin")), ThreadToolCard.decode(create)!!.threads)

        val wait = call("TU-8", "wait_for_threads", mapOf("threads" to listOf(child.raw, gallery.raw)), ToolRun.Pending)
        assertEquals("2 threads", toolSummary(wait))
    }

    @Test
    fun `web decodes a page read and a search's result links`() {
        val page = call(
            "TU-ToolsGallery0000000007", "read_web_page",
            mapOf("url" to "https://ampcode.com/manual", "objective" to "Find the list of built-in tools"),
            done("# Owner's Manual"),
        )
        assertEquals(WebCall(WebTarget.Page("https://ampcode.com/manual", "Find the list of built-in tools"), emptyList(), "# Owner's Manual"), WebCard.decode(page))
        assertEquals("https://ampcode.com/manual", toolSummary(page))

        val search = call(
            "TU-9", "web_search", mapOf("query" to "coil data uri"),
            done(mapOf("results" to listOf(mapOf("title" to "Coil", "url" to "https://coil-kt.github.io/coil/"), mapOf("title" to "Spec", "url" to "https://www.rfc-editor.org/rfc/rfc2397")))),
        )
        assertEquals(
            listOf(WebLink("https://coil-kt.github.io/coil/", "Coil"), WebLink("https://www.rfc-editor.org/rfc/rfc2397", "Spec")),
            WebCard.decode(search)!!.links,
        )
        assertEquals("coil data uri · 2 results", toolSummary(search))
    }

    @Test
    fun `media decodes a painter base64 image and gives an imageless result to the generic card`() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFklEQVR42mP4H+VMEmIY1TCqYfhqAADECZwQYE/cowAAAABJRU5ErkJggg=="
        val painter = call(
            "TU-ToolsGallery0000000008", "painter", mapOf("prompt" to "A small orange square"),
            done(listOf(mapOf("type" to "image", "mimeType" to "image/png", "data" to png, "width" to 16, "height" to 16))),
        )
        assertEquals(MediaCall("A small orange square", listOf(MediaImage(ImageSource.Inline("image/png", png), null))), MediaCard.decode(painter))
        assertEquals("A small orange square · 1 image", toolSummary(painter))
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), (modelOf(ImageSource.Inline("image/png", png)) as ByteArray).take(4).map { it.toInt() and 0xFF })

        val view = call("TU-10", "view_media", mapOf("path" to "shot.png"), done(listOf(mapOf("type" to "image", "mimeType" to "image/png", "url" to "https://ampcode.com/user-content/shot.png"))))
        assertEquals(listOf(MediaImage(ImageSource.Url("https://ampcode.com/user-content/shot.png"), null)), MediaCard.decode(view)!!.images)

        val empty = call("TU-11", "view_media", mapOf("path" to "notes.txt"), done("not an image"))
        assertEquals(null, MediaCard.decode(empty))
        assertEquals("notes.txt", toolSummary(empty))
    }

    @Test
    fun `choice decodes the selected option and askAnswers`() {
        val choice = call(
            "TU-ToolsGallery0000000009", "ask_user_choice",
            mapOf("question" to "Keep the old name as an alias?", "options" to listOf("Yes", "No")),
            done("User selected option 2: No", mapOf("accepted" to true)),
        )
        assertEquals(ChoiceCall("Keep the old name as an alias?", listOf("Yes", "No"), ChoiceAnswer.Chosen("No")), ChoiceCard.decode(choice))
        assertEquals("Keep the old name as an alias? → No", toolSummary(choice))

        val answered = call("TU-12", "ask_user_choice", mapOf("question" to "Which runner?"), done("", mapOf("accepted" to true, "askAnswers" to listOf("mac-mini"))))
        assertEquals(ChoiceAnswer.Chosen("mac-mini"), ChoiceCard.decode(answered)!!.answer)
        assertEquals("Which runner? → dismissed", toolSummary(call("TU-13", "ask_user_choice", mapOf("question" to "Which runner?"), done("User dismissed the question"))))
    }

    @Test
    fun `todo decodes the list todo_write carries`() {
        val todo = call(
            "TU-ToolsGallery0000000010", "todo_write",
            mapOf(
                "todos" to listOf(
                    mapOf("id" to "1", "content" to "Rename add to add_numbers", "status" to "completed"),
                    mapOf("id" to "2", "content" to "Run calc.py", "status" to "in_progress"),
                    mapOf("id" to "3", "content" to "Update the README", "status" to "todo"),
                ),
            ),
            done("Updated the TODO list"),
        )
        assertEquals(
            TodoCall(
                listOf(
                    TodoItem("Rename add to add_numbers", true, "completed"),
                    TodoItem("Run calc.py", false, "in_progress"),
                    TodoItem("Update the README", false, "todo"),
                ),
            ),
            TodoCard.decode(todo),
        )
        assertEquals("Update TODOs", toolLabel(todo))
        assertEquals("1 of 3 done", toolSummary(todo))
    }

    @Test
    fun `files decodes an upload and a download from another thread`() {
        val upload = call("TU-ToolsGallery0000000011", "upload_thread_file", mapOf("path" to "$scratch/calc.py"), done(mapOf("remotePath" to "calc.py")))
        assertEquals(FilesCall(FileDirection.Upload, "$scratch/calc.py", "calc.py", null), FilesCard.decode(upload))
        assertEquals("/private/tmp/amp-scratch/calc.py → calc.py", toolSummary(upload))

        val download = call(
            "TU-14", "download_thread_file",
            mapOf("path" to "report.md", "thread" to "T-01a166e9-3fa0-77ca-b925-383e88ba52ac"),
            done(mapOf("localPath" to ".amp/in/report.md")),
        )
        assertEquals(
            FilesCall(FileDirection.Download, ".amp/in/report.md", "report.md", ThreadId.parse("T-01a166e9-3fa0-77ca-b925-383e88ba52ac")),
            FilesCard.decode(download),
        )
        assertEquals("report.md → .amp/in/report.md", toolSummary(download))
    }

    @Test
    fun `generic shows the unknown tool error, the empty marker, and pretty input`() {
        val unknown = call(
            "TU-034khTf8VtAoY7C4A1l5Ln", "read_thread_changes",
            mapOf("thread" to "T-01a166e9-3fa0-77ca-b925-383e88ba52ac", "destination" to ".amp/in/recovered"),
            ToolRun.Error("Unknown tool: read_thread_changes. Neither the server nor the connected executor provides a tool with this name.", jv(null)),
        )
        assertEquals(null, decoded(unknown))
        assertEquals(
            GenericOutput.Failed("Unknown tool: read_thread_changes. Neither the server nor the connected executor provides a tool with this name."),
            GenericToolCard.output(unknown.run),
        )
        assertEquals(
            "error: Unknown tool: read_thread_changes. Neither the server nor the connected executor provides a tool with this name.",
            statusText(unknown.run),
        )
        assertEquals(null, statusText(done("ok")))
        assertEquals("T-01a166e9-3fa0-77ca-b925-383e88ba52ac", toolSummary(unknown))
        assertEquals(
            "{\n  \"thread\": \"T-01a166e9-3fa0-77ca-b925-383e88ba52ac\",\n  \"destination\": \".amp/in/recovered\"\n}",
            unknown.input.value.pretty(),
        )

        assertEquals("(no tool output)", GenericToolCard.output(done(""))!!.text)
        assertEquals("(no tool output)", GenericToolCard.output(done(null))!!.text)
        assertEquals(GenericOutput.Text("calc.py"), GenericToolCard.output(done(listOf(mapOf("type" to "text", "text" to "calc.py")))))
        assertEquals(GenericOutput.Json("{\n  \"ok\": true,\n  \"count\": 2\n}"), GenericToolCard.output(done(mapOf("ok" to true, "count" to 2))))

        val undecodable = call("TU-15", "shell_command", mapOf("script" to "ls"), done(mapOf("output" to "", "exitCode" to 0)))
        assertEquals(null, decoded(undecodable))
        assertEquals("ls", toolSummary(undecodable))
    }
}
