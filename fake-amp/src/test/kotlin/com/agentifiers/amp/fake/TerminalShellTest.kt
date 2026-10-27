package com.agentifiers.amp.fake

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalShellTest {
    private val channel = "relay-00000000-0000-4000-8000-000000000001"

    @Test
    fun `the shell records resize, prints stty size, echoes, cats until ctrl-c, floods, and answers amp-exec-v1`() = FakeAmp.start("terminal").use { server ->
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 64))
        socket.readUntil { it.string("id") == resume }
        socket.notify("client_executor_relay_open", json("channelId" to channel, "service" to "amp-terminal-v1", "binaryFrames" to true))
        socket.sendBytes(control("""{"type":"open","cols":80,"rows":24}"""))
        socket.readBytesUntil { frames -> frames.any { RelayFrame.decode(it)?.payload?.utf8() == """{"type":"ready"}""" } }

        socket.sendBytes(control("""{"type":"resize","cols":100,"rows":30}"""))
        socket.sendBytes(input("stty size\r"))
        assertTrue(screen(socket.readBytesUntil { screen(it).contains("30 100\r\n") }).contains("stty size\r\n30 100\r\n"))

        socket.sendBytes(input("echo hello  world\r"))
        socket.readBytesUntil { screen(it).contains("\r\nhello world\r\n") }

        socket.sendBytes(input("cat\r"))
        socket.readBytesUntil { screen(it).contains("\u001b[?2004l") }
        socket.sendBytes(input("pasted\r\u0003"))
        val cat = screen(socket.readBytesUntil { screen(it).contains("^C\r\n") })
        assertTrue(cat.contains("pasted\r\npasted\r\n^C\r\n"))

        val started = System.nanoTime()
        socket.sendBytes(input("amp-fake-flood 1\r"))
        socket.sendBytes(input("typed"))
        val flood = screen(socket.readBytesUntil(timeoutMs = 15_000) { screen(it).contains("flood done") })
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(flood.contains("typed"))
        assertTrue(flood.contains("flood 00009999 "))
        assertTrue(flood.contains("flood done 1000000\r\n"))
        assertTrue("1 MB took $elapsedMs ms", elapsedMs in 900..3_000)
        val afterFlood = flood.substringAfter("flood done 1000000\r\n")
        val replayed = if (afterFlood.endsWith("typed")) afterFlood else afterFlood + screen(socket.readBytesUntil { screen(it).endsWith("typed") })
        assertTrue("typeahead reaches the shell after the flood", replayed.endsWith("typed"))

        val exec = "relay-00000000-0000-4000-8000-000000000002"
        socket.notify("client_executor_relay_open", json("channelId" to exec, "service" to "amp-exec-v1", "binaryFrames" to true))
        socket.sendBytes(RelayFrame(1, false, exec, """{"program":"amp","args":["files","get"],"cwd":"/private/tmp/amp-scratch"}""".encodeUtf8()).encode())
        val reply = socket.readBytesUntil { frames -> frames.any { RelayFrame.decode(it)?.channelId == exec } }.mapNotNull { RelayFrame.decode(it) }.single { it.channelId == exec }
        assertEquals("""{"type":"exit","status":0}""", reply.payload.utf8())

        assertEquals(
            listOf(
                """{"type":"open","cols":80,"rows":24}""",
                """{"type":"resize","cols":100,"rows":30}""",
                """{"program":"amp","args":["files","get"],"cwd":"/private/tmp/amp-scratch"}""",
            ),
            server.terminalControls,
        )
        socket.close()
    }

    private fun control(json: String): ByteString = RelayFrame(1, false, channel, json.encodeUtf8()).encode()

    private fun input(text: String): ByteString = RelayFrame(1, true, channel, text.encodeUtf8()).encode()

    private fun screen(frames: List<ByteString>): String =
        frames.mapNotNull { RelayFrame.decode(it) }.filter { it.binary && it.channelId == channel }.joinToString("") { it.payload.utf8() }
}
