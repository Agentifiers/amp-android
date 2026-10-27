package com.agentifiers.amp.fake

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class DesktopScenarioTest {
    private val video = "relay-0b1a6f0e-desktop-stream"
    private val control = "relay-0b1a6f0e-desktop-control"
    private val audio = "relay-0b1a6f0e-desktop-audio"

    @Test
    fun `desktop observers offer the relay desktop`() = FakeAmp.start("desktop").use { server ->
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 0))
        val frames = socket.readUntil { it.string("id") == resume }
        val observers = frames.filter { it.isEvent("observers") }.map { it.params().bool("relayDesktopAvailable") }
        assertEquals(listOf(true, true), observers)
        socket.close()
    }

    @Test
    fun `a stream channel opens, names its codec, and sends a keyframe with a 40-byte header`() = FakeAmp.start("desktop").use { server ->
        val socket = connected(server)
        open(socket, video, "/stream", overJson = true)

        val replies = relay(socket, count = 3)
        assertEquals(Triple(video, false, """{"type":"open"}"""), replies[0].summary())
        assertEquals(Triple(video, false, """{"type":"video-config","codec":"avc1.42C01E"}"""), replies[1].summary())
        val frame = replies[2]
        assertEquals(true, frame.binary)
        assertEquals(
            "020101" + "00" + "0000000000000000" + "0000000000000000" + "01000000" + "8002" + "9001" + "0000000000000000" + "00000000",
            frame.payload.substring(0, 40).hex(),
        )
        assertEquals("0000000109100000000167", frame.payload.substring(40, 51).hex())
        socket.close()
    }

    @Test
    fun `control answers ping with pong, acquire with active, and a display select with the next generation`() = FakeAmp.start("desktop").use { server ->
        val socket = connected(server)
        open(socket, control, "/control")
        val opened = relay(socket, count = 3).map { it.payload.utf8() }
        assertEquals(
            listOf(
                """{"type":"open"}""",
                """{"type":"control-state","state":"ready","resolvedKeysyms":true}""",
                """{"type":"display-state","displays":[{"id":"0","name":"Built-in","width":1280,"height":800,"isMain":true},""" +
                    """{"id":"1","name":"Second","width":1024,"height":768,"isMain":false}],"selectedID":"0","generation":1,"switching":false}""",
            ),
            opened,
        )

        socket.sendBytes(RelayFrame(1, false, control, """{"type":"ping","id":7}""".encodeUtf8()).encode())
        socket.sendBytes(RelayFrame(1, false, control, "acquire".encodeUtf8()).encode())
        socket.sendBytes(RelayFrame(1, false, control, """{"type":"display-select","id":"1"}""".encodeUtf8()).encode())
        socket.sendBytes(RelayFrame(1, true, control, resize(1072, 2400, request = 1, scale120 = 315)).encode())
        socket.sendBytes(RelayFrame(1, false, control, """{"type":"clipboard-write","text":"from the phone"}""".encodeUtf8()).encode())
        val answers = relay(socket, count = 5).map { Wire.parse(it.payload.utf8()) }
        assertEquals(json("type" to "pong", "id" to 7), answers[0] - "serverNanos")
        assertEquals("""{"type":"control-state","state":"active","resolvedKeysyms":true}""", answers[1].toString())
        assertEquals(listOf("1", "2"), listOf(answers[2].string("selectedID"), answers[2]["generation"].toString()))
        assertEquals("""{"type":"resize-applied","request":1,"generation":3,"width":1072,"height":2400,"scale":315}""", answers[3].toString())
        assertEquals("""{"type":"clipboard","text":"from the phone"}""", answers[4].toString())
        socket.close()
    }

    @Test
    fun `audio announces 48 kHz stereo Opus and sends packets with a 24-byte header`() = FakeAmp.start("desktop").use { server ->
        val socket = connected(server)
        open(socket, audio, "/audio")
        val replies = relay(socket, count = 3)
        assertEquals(
            """{"type":"audio-config","version":2,"enabled":true,"codec":"opus","sampleRate":48000,"channels":2}""",
            replies[1].payload.utf8(),
        )
        assertEquals("020201" + "00".repeat(9) + "0000000000000000" + "01000000", replies[2].payload.substring(0, 24).hex())
        assertEquals(DesktopMedia.audio.first(), replies[2].payload.substring(24))
        socket.close()
    }

    private fun connected(server: FakeAmpServer): Socket {
        val socket = Wire(server).threadSocket(RUNNER_THREAD)
        val resume = socket.request("client_resume", json("version" to 64))
        socket.readUntil { it.string("id") == resume }
        return socket
    }

    private fun open(socket: Socket, channel: String, path: String, overJson: Boolean = false) {
        socket.notify("client_executor_relay_open", json("channelId" to channel, "service" to "amp-desktop-v1", "binaryFrames" to true))
        val connect = """{"type":"connect","path":"$path"}"""
        if (overJson) {
            socket.notify("client_executor_relay_data", json("channelId" to channel, "dataBase64" to Base64.getEncoder().encodeToString(connect.toByteArray()), "binary" to false))
        } else {
            socket.sendBytes(RelayFrame(1, false, channel, connect.encodeUtf8()).encode())
        }
    }

    /** The next [count] relay frames the executor sent on this socket. */
    private fun relay(socket: Socket, count: Int): List<RelayFrame> =
        socket.readBytesUntil { frames -> frames.size >= count }.take(count).map { RelayFrame.decode(it)!! }

    private fun RelayFrame.summary() = Triple(channelId, binary, payload.utf8())

    private fun resize(width: Int, height: Int, request: Int, scale120: Int): ByteString =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put(2).put(6).put(0).put(0).putInt(width).putInt(height).putInt((request shl 16) or scale120)
            .array().toByteString()
}
