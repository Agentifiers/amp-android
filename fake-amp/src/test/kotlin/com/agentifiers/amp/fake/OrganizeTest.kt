package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class OrganizeTest {

    @Test
    fun `fleet-organize lists the contract's 40 threads with their organize fields`() = FakeAmp.start("fleet-organize").use { server ->
        val wire = Wire(server)
        val rows = rows(wire)
        assertEquals(40, rows.size)
        assertEquals(listOf("Pinned roadmap", true, false, "thread_workspace_shared"), summary(rows.getValue(t("06"))))
        assertEquals(listOf("Archived spike", false, true, "thread_workspace_shared"), summary(rows.getValue(t("32"))))
        assertEquals(listOf("Teammate spike", false, false, "private"), summary(rows.getValue(t("39"))))
        assertEquals(listOf("Unlisted demo", false, false, "public_unlisted"), summary(rows.getValue(t("40"))))
        assertEquals("user_01JTEAMMATE00000000000000", rows.getValue(t("39")).string("creatorUserID"))
        assertEquals("user_01EXAMPLE00000000000000000", rows.getValue(t("01")).string("creatorUserID"))
        assertEquals(
            JsonArray(listOf(json("threadID" to "T-f1ee7000-0000-4000-8000-000000000001", "type" to "subagent", "role" to "parent"))),
            rows.getValue(t("35"))["relationships"],
        )
        assertEquals(JsonArray(emptyList()), rows.getValue(t("01"))["relationships"])
        assertEquals(listOf("puck", "puck", "low"), listOf("37", "38", "01").map { rows.getValue(t(it)).string("agentMode") })
        val newest = rows.getValue(t("01")).long("userLastInteractedAt")!!
        assertEquals(719_640_000L, newest - rows.getValue(t("06")).long("userLastInteractedAt")!!)
        assertEquals(newest, rows.getValue(t("01")).long("created"))

        val recent = recent(wire)
        assertEquals(listOf("working", "working", "idle", "error", "idle"), listOf("01", "02", "03", "04", "05").map { recent.getValue(t(it)).string("state") })
        val indicator = recent.getValue(t("03")).obj("indicator")!!
        assertEquals("action-required" to "Bash: ./gradlew migrate", indicator.string("kind") to indicator.string("description"))
        assertEquals(JsonNull, recent.getValue(t("05"))["indicator"])
        assertEquals(listOf(true, false), listOf("05", "08").map { recent.getValue(t(it)).bool("hasUnreadMessages") })
        assertEquals(listOf(true, false), listOf("07", "08").map { recent.getValue(t(it)).bool("pinned") })
        assertEquals("Approve migration", recent.getValue(t("03")).string("title"))

        assertEquals(json("id" to "proj-android", "name" to "amp-android"), wire.internal("getThreadProject", json("thread" to t("01")))["result"])
        assertEquals(json("id" to "proj-relay", "name" to "relay"), wire.internal("getThreadProject", json("thread" to t("02")))["result"])
        assertEquals(JsonNull, wire.internal("getThreadProject", json("thread" to t("21")))["result"])
        assertEquals(listOf("ui", "bug"), labelNames(wire, t("09")))
    }

    @Test
    fun `every organize write shows in the next read`() = FakeAmp.start("fleet-organize").use { server ->
        val wire = Wire(server)
        val pinned = wire.internal("updateThread", json("thread" to t("08"), "pinned" to true))
        assertEquals(
            json(
                "ok" to true,
                "result" to json(
                    "threadID" to "T-f1ee7000-0000-4000-8000-000000000008", "title" to "Sidebar polish", "pinned" to true,
                    "visibility" to "workspace", "labels" to listOf("ui"), "archived" to false,
                ),
            ),
            pinned,
        )
        assertEquals(true, rows(wire).getValue(t("08")).bool("pinned"))
        assertEquals(true, recent(wire).getValue(t("08")).bool("pinned"))

        val snoozed = wire.internal("updateThread", json("thread" to t("08"), "snooze" to "one_hour")).obj("result")!!
        assertEquals("one_hour", snoozed.string("snooze"))
        val colored = wire.internal("updateThread", json("thread" to t("08"), "color" to null)).obj("result")!!
        assertEquals(JsonNull, colored["color"])

        val labels = wire.internal("setThreadLabels", json("thread" to t("08"), "labels" to listOf("ui", "polish")))
        assertEquals(listOf("ui", "polish"), (labels["result"] as JsonArray).map { (it as JsonObject).string("name") })
        assertEquals(listOf("ui", "polish"), labelNames(wire, t("08")))
        val uiOn08 = (wire.internal("getThreadLabels", json("thread" to t("08")))["result"] as JsonArray).first()
        val uiOn01 = (wire.internal("getThreadLabels", json("thread" to t("01")))["result"] as JsonArray).first()
        assertEquals(uiOn01, uiOn08)

        val shared = wire.internal("updateThread", json("thread" to t("11"), "visibility" to "public_unlisted")).obj("result")!!
        assertEquals("unlisted", shared.string("visibility"))
        assertEquals("public_unlisted", rows(wire).getValue(t("11")).obj("meta")!!.string("visibility"))

        wire.internal("archiveThread", json("thread" to t("08"), "archived" to true))
        assertEquals(true, rows(wire).getValue(t("08")).bool("archived"))

        assertEquals(json("ok" to true), wire.internal("deleteThread", json("thread" to t("22"))))
        val afterDelete = rows(wire)
        assertEquals(39, afterDelete.size)
        assertFalse(t("22") in afterDelete)
        assertFalse(t("22") in recent(wire))
        val again = wire.internal("deleteThread", json("thread" to t("22")))
        assertEquals(false to "not-found", again.bool("ok") to again.obj("error")!!.string("code"))

        val report = wire.internal("openCustomBugReport", json("threadID" to t("10"))).obj("result")!!.string("bugReportID")!!
        assertTrue(report, report.startsWith("bug-"))
        val metadata = json("description" to "Crashes on unfold", "includeDiagnostics" to true)
        val attach = json("bugReportID" to report, "threadID" to t("10"), "files" to emptyList<Any>(), "metadata" to metadata)
        assertEquals(json("ok" to true), wire.internal("attachBugReportDiagnostics", attach))
        val unknown = wire.internal("attachBugReportDiagnostics", attach + mapOf("bugReportID" to element("bug-unknown")))
        assertEquals("not-found", unknown.obj("error")!!.string("code"))
    }

    @Test
    fun `socket organize requests update the summary and the next connect burst`() = FakeAmp.start("fleet-organize").use { server ->
        val wire = Wire(server)
        val socket = wire.threadSocket(t("08"))
        val resume = socket.request("client_resume", json("version" to 0))
        socket.readUntil { it.string("id") == resume }

        assertEquals(json("ok" to true), answer(socket, "client_mark_thread_unread", json()))
        assertEquals(true, recent(wire).getValue(t("08")).bool("hasUnreadMessages"))
        assertEquals(json("ok" to true), answer(socket, "client_mark_message_read", json("messageId" to "M-androidFakeTest00000020")))
        assertEquals(false, recent(wire).getValue(t("08")).bool("hasUnreadMessages"))

        val before = Instant.now()
        val open = socket.request("client_set_thread_open", json("ttlSeconds" to 3600))
        val frames = socket.readUntil { it.string("id") == open }
        assertEquals(json("ok" to true), frames.last().obj("result"))
        val state = frames.first { it.isEvent("thread_open_state") }.params()
        assertEquals(1L, state.long("openStateSeq"))
        val expires = Duration.between(before, Instant.parse(state.string("openExpiresAt")))
        assertTrue("$expires", expires >= Duration.ofSeconds(3600) && expires < Duration.ofSeconds(3610))

        assertEquals(json("ok" to true), answer(socket, "client_update_thread_feature", json("feature" to "recaps", "enabled" to false)))
        assertEquals(json("ok" to true), answer(socket, "client_dismiss_recap", json()))
        val selection = json("textIndex" to 0, "start" to 0, "end" to 4, "quoted" to "Done")
        val comment = json("commentId" to "M-androidFakeTest00000021", "anchorMessageId" to "M-androidFakeTest00000022", "content" to "Nice", "selection" to selection)
        assertEquals(json("ok" to true), answer(socket, "client_add_thread_comment", comment))
        socket.close()

        val reconnect = wire.threadSocket(t("08"))
        val again = reconnect.request("client_resume", json("version" to 0))
        val burst = reconnect.readUntil { it.string("id") == again }
        assertEquals(state, burst.single { it.isEvent("thread_open_state") }.params())
        val closed = reconnect.request("client_set_thread_open", json("ttlSeconds" to null))
        val off = reconnect.readUntil { it.string("id") == closed }.first { it.isEvent("thread_open_state") }.params()
        assertEquals(json("openExpiresAt" to null, "openStateSeq" to 2), off)
        reconnect.close()
    }

    @Test
    fun `share-presence connects with Ada Lovelace watching and typing`() = FakeAmp.start("share-presence").use { server ->
        val wire = Wire(server)
        assertEquals("thread_workspace_shared", rows(wire).getValue(RUNNER_THREAD).obj("meta")!!.string("visibility"))
        val socket = wire.threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 28))
        val burst = socket.readUntil { it.string("id") == resume }
        val observers = JsonArray(
            listOf(
                json("userId" to "user_01EXAMPLE00000000000000000", "name" to "user_01EXAMPLE00000000000000000"),
                json("userId" to "user_01JTEAMMATE00000000000000", "name" to "Ada Lovelace"),
            ),
        )
        val seen = burst.filter { it.isEvent("observers") }.map { it.params() }
        assertEquals(2, seen.size)
        for (frame in seen) {
            assertEquals(listOf(2L, true), listOf(frame.long("count"), frame.bool("hasExecutor")))
            assertEquals(observers, frame["observers"])
        }
        val drafts = burst.single { it.isEvent("prompt_drafts") }.params()
        assertEquals(
            json("drafts" to listOf(json("connectionId" to "conn-ada", "userId" to "user_01JTEAMMATE00000000000000", "text" to "Can you also check the relay?"))),
            drafts,
        )
        socket.close()
    }

    @Test
    fun `puck-chat answers a send with the Puck reply and openPuckThread starts a fresh conversation`() =
        FakeAmp.start("puck-chat", frameGapCapMs = 10).use { server ->
            val wire = Wire(server)
            assertEquals(setOf(t("01"), t("37"), t("38")), rows(wire).keys)
            assertEquals("I can start an agent for that. Which project?", puckReply(wire, t("37"), "M-androidFakeTest00000030"))

            val opened = wire.internal("openPuckThread", json("archiveThreadID" to t("37"))).obj("result")!!.string("threadID")!!
            assertTrue(opened, opened.startsWith("T-") && opened != t("37"))
            val rows = rows(wire)
            assertEquals(true, rows.getValue(t("37")).bool("archived"))
            val fresh = rows.getValue(opened)
            assertEquals(listOf("New conversation", "puck", false), listOf(fresh.string("title"), fresh.string("agentMode"), fresh.bool("archived")))
            assertEquals(409, wire.post("/api/thread-actors", json("threadId" to t("37"))).first)
            assertEquals("I can start an agent for that. Which project?", puckReply(wire, opened, "M-androidFakeTest00000031"))
        }

    private fun puckReply(wire: Wire, thread: String, messageId: String): String? {
        val socket = wire.threadSocket(thread)
        val resume = socket.request("client_resume", json("version" to 0))
        socket.readUntil { it.string("id") == resume }
        socket.request("client_append_user_msg", json("content" to listOf(json("type" to "text", "text" to "Start an agent to fix the crease")), "messageId" to messageId))
        val turn = socket.readUntil { it.isEvent("agent_state") && it.params().string("state") == "idle" }
        socket.close()
        val reply = turn.last { it.isEvent("message_added") && it.message()?.string("role") == "assistant" }.message()!!
        return (reply.array("content")!!.single() as JsonObject).string("text")
    }

    private fun answer(socket: Socket, method: String, params: JsonObject): JsonObject? {
        val id = socket.request(method, params)
        return socket.readUntil { it.string("id") == id }.last().obj("result")
    }

    private fun t(nn: String) = "T-f1ee7000-0000-4000-8000-0000000000$nn"

    private fun summary(row: JsonObject) = listOf(row.string("title"), row.bool("pinned"), row.bool("archived"), row.obj("meta")!!.string("visibility"))

    private fun rows(wire: Wire): Map<String, JsonObject> =
        wire.internal("listThreads", json("limit" to 50)).obj("result")!!.array("threads")!!.map { it as JsonObject }.associateBy { it.string("id")!! }

    private fun labelNames(wire: Wire, thread: String): List<String?> =
        (wire.internal("getThreadLabels", json("thread" to thread))["result"] as JsonArray).map { (it as JsonObject).string("name") }

    private fun recent(wire: Wire): Map<String, JsonObject> {
        val token = Wire.parse(wire.post("/api/user-actor-credentials", json("capability" to "read")).second).string("wsToken")!!
        val (status, body) = wire.post(
            "/actors/gateway/userActor/action/getRecentThreads?rvt-key=user_01EXAMPLE00000000000000000",
            json("args" to listOf(json("limit" to 50))),
            mapOf("x-rivet-encoding" to "json", "x-rivet-conn-params" to json("type" to "user", "wsToken" to token).toString()),
        )
        check(status == 200) { "getRecentThreads answered $status" }
        return Wire.parse(body).array("output")!!.map { it as JsonObject }.associateBy { it.string("threadId")!! }
    }
}
