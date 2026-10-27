package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class FilesWorkspaceTest {

    @Test
    fun `files lists the recorded root, reads calc py, rejects a ranged read, and keeps writes`() = FakeAmp.start("files").use { server ->
        val socket = Wire(server).threadSocket(GATE_THREAD)
        val resume = socket.request("client_resume", json("version" to 0))
        socket.readUntil { it.string("id") == resume }

        assertEquals(listOf("__pycache__", "calc.py", ".git"), names(socket.result("client_filesystem_read_directory_v2", json("requestId" to "dir-1", "path" to "."))))
        assertEquals(listOf("__pycache__", "calc.py", ".git"), names(socket.result("client_filesystem_read_directory_v2", json("requestId" to "dir-2", "path" to "/private/tmp/amp-scratch"))))

        val calc = socket.result("client_filesystem_read_file_v2", json("requestId" to "read-1", "path" to "calc.py"))
        assertEquals("def add_numbers(a, b):\n    return a + b\n", String(Base64.getDecoder().decode(calc.string("contentBase64"))))

        val ranged = socket.request("client_filesystem_read_file_v2", json("requestId" to "read-2", "path" to "calc.py", "range" to json("start" to 0, "end" to 16)))
        val rejection = socket.readUntil { it.string("id") == ranged }.last()
        assertEquals(json("code" to -32602, "message" to "Invalid method params"), rejection.obj("error"))

        val written = "written by the fake test\n".encodeToByteArray()
        val write = json("requestId" to "write-1", "path" to "notes/s19-write.txt", "contentBase64" to Base64.getEncoder().encodeToString(written), "overwrite" to false)
        assertEquals(json("requestId" to "write-1", "ok" to true), socket.result("client_filesystem_write_file_v3", write))
        assertEquals(
            json("requestId" to "write-2", "ok" to false, "error" to json("code" to "ALREADY_EXISTS", "message" to "File already exists")),
            socket.result("client_filesystem_write_file_v3", write + mapOf("requestId" to element("write-2"))),
        )

        assertEquals(listOf("__pycache__", "calc.py", ".git", "notes"), names(socket.result("client_filesystem_read_directory_v2", json("requestId" to "dir-3", "path" to "."))))
        assertEquals(listOf("s19-write.txt"), names(socket.result("client_filesystem_read_directory_v2", json("requestId" to "dir-4", "path" to "notes"))))
        assertArrayEquals(written, server.workspaceFile(GATE_THREAD, "/private/tmp/amp-scratch/notes/s19-write.txt"))
        socket.close()
    }

    @Test
    fun `portal keeps the manifest in the workspace and serves its root page after the login hop`() = FakeAmp.start("portal").use { server ->
        val manifest = String(server.workspaceFile(RUNNER_THREAD, ".amp/portals/web.json")!!)
        assertEquals(
            """{"links":[{"label":"Fake portal","url":"https://t-exampleportal000000000000-p8765.onamp.dev/","note":"The S13 portal on port 8765"}]}""",
            manifest,
        )

        val wire = Wire(server)
        val hop = wire.http.newBuilder().followRedirects(false).build()
            .newCall(Request.Builder().url("${server.ampBaseUrl}/portal/t-exampleportal000000000000-p8765/__amp_auth/headless?code=x").build())
            .execute().use { it.code to it.header("Location") }
        assertEquals(302 to "${server.ampBaseUrl}/portal/t-exampleportal000000000000-p8765/", hop)

        val page = wire.http.newCall(Request.Builder().url(hop.second!!).build()).execute().use { Triple(it.code, it.header("Content-Type"), it.body.string()) }
        assertEquals(200 to "text/html; charset=utf-8", page.first to page.second)
        assertEquals(
            "<!doctype html><html><head><title>Fake portal 8765</title></head><body><h1>Fake portal 8765</h1>" +
                "<p>Served by fake-amp for t-exampleportal000000000000-p8765.</p></body></html>",
            page.third,
        )
    }

    /** Sends a `requestId` request and returns its `*_result` params after the `{ok:true}` receipt. */
    private fun Socket.result(method: String, params: JsonObject): JsonObject {
        val id = request(method, params)
        val frames = readUntil { it.isEvent(method.replace(Regex("_v\\d+$"), "") + "_result") }
        assertEquals(json("ok" to true), frames.first { it.string("id") == id }.obj("result"))
        return frames.last().params()
    }

    private fun names(result: JsonObject): List<String> = result.array("entries")!!.map { (it as JsonArray)[0].text() }
}
