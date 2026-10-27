package com.agentifiers.amp.waymote

import android.view.KeyEvent
import com.agentifiers.amp.sync.api.DesktopStream
import com.agentifiers.amp.sync.api.RelayChannel
import com.agentifiers.amp.sync.api.RelayChannelFactory
import com.agentifiers.amp.sync.api.RelayPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class WaymoteSessionTest {
    private class ScriptedChannel : RelayChannel {
        val inbound = Channel<RelayPayload>(Channel.UNLIMITED)
        val sent = ArrayList<RelayPayload>()
        var closed = false
        override val incoming: Flow<RelayPayload> = inbound.receiveAsFlow()
        override fun send(payload: RelayPayload) {
            sent += payload
        }
        override fun close() {
            closed = true
        }

        fun text(json: String) = inbound.trySend(RelayPayload.Text(json))
        fun binary(bytes: ByteArray) = inbound.trySend(RelayPayload.Binary(bytes))

        /** Everything sent, text as is and binary as hex, then forgotten. */
        fun drain(): List<String> = sent.map { if (it is RelayPayload.Text) it.json else (it as RelayPayload.Binary).bytes.toHex() }.also { sent.clear() }
    }

    private class Decoder : VideoDecoder {
        val units = ArrayList<String>()
        var closed = false
        override fun decode(bytes: ByteArray, offset: Int, timestampUs: Long) {
            units += bytes.copyOfRange(offset, bytes.size).toHex() + "@" + timestampUs
        }
        override val backlog = 0
        override fun close() {
            closed = true
        }
    }

    private class Target : VideoTarget {
        val opened = ArrayList<String>()
        val decoders = ArrayList<Decoder>()
        private var render: () -> Unit = {}
        override fun open(mime: String, width: Int, height: Int, onRendered: () -> Unit, onError: (String) -> Unit): VideoDecoder {
            opened += "$mime ${width}x$height"
            render = onRendered
            return Decoder().also { decoders += it }
        }
        fun present() = render()
    }

    private class Media : MediaPlatform {
        val audio = ArrayList<String>()
        override fun supportsVideo(mime: String) = mime == "video/avc"
        override fun openAudio(format: AudioStreamFormat, onError: (String) -> Unit): AudioDecoder {
            require(format.codec == "opus") { "Unsupported audio codec ${format.codec}" }
            return object : AudioDecoder {
                override fun decode(bytes: ByteArray, offset: Int, timestampUs: Long) {
                    audio += bytes.copyOfRange(offset, bytes.size).toHex()
                }
                override fun reset() {
                    audio += "reset"
                }
                override fun close() = Unit
            }
        }
    }

    private class Harness(scope: TestScope) {
        val video = ScriptedChannel()
        val audio = ScriptedChannel()
        val control = ScriptedChannel()
        val media = Media()
        val session = WaymoteSession(
            channels = object : RelayChannelFactory {
                override suspend fun open(stream: DesktopStream): RelayChannel = when (stream) {
                    DesktopStream.Video -> video
                    DesktopStream.Audio -> audio
                    DesktopStream.Control -> control
                }
            },
            media = media,
            nanoTime = { scope.testScheduler.currentTime * 1_000_000 },
        )

        val streaming: SessionState.Streaming get() = session.state.value as SessionState.Streaming
    }

    private fun session(body: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness(this)
        backgroundScope.launch { harness.session.run() }
        runCurrent()
        body(harness)
    }

    private fun TestScope.activate(h: Harness, resolvedKeysyms: Boolean = false) {
        h.session.takeOver()
        runCurrent()
        h.control.text("""{"type":"control-state","state":"active","resolvedKeysyms":$resolvedKeysyms}""")
        runCurrent()
        h.control.drain()
    }

    @Test
    fun `an unsupported video codec ends the session naming the codec`() = session { h ->
        h.video.text("""{"type":"video-config","codec":"vp8"}""")
        runCurrent()
        assertEquals(SessionState.Ended(EndReason.UnsupportedCodec("vp8")), h.session.state.value)
        assertEquals(listOf(true, true, true), listOf(h.video.closed, h.audio.closed, h.control.closed))
    }

    @Test
    fun `video waits for a keyframe, decodes payloads from byte 40, and reports the first presented frame of the display generation`() = session { h ->
        val target = Target()
        h.session.attach(target)
        h.video.text("""{"type":"video-config","codec":"avc1.42C01E"}""")
        h.control.text(TWO_DISPLAYS)
        runCurrent()
        assertEquals(Displays(listOf(BUILT_IN, SECOND), selected = "0", switching = true, error = null), h.streaming.displays)

        h.video.binary(videoFrame(keyframe = false, generation = 1, timestampUs = 0, payload = "0000000141"))
        h.video.binary(videoFrame(keyframe = true, generation = 1, timestampUs = 100_000, payload = "0000000165"))
        h.video.binary(videoFrame(keyframe = false, generation = 1, timestampUs = 200_000, payload = "0000000141aa"))
        runCurrent()
        assertEquals(listOf("video/avc 640x400"), target.opened)
        assertEquals(listOf("0000000165@100000", "0000000141aa@200000"), target.decoders.single().units)
        assertEquals(Video.Waiting, h.streaming.video)

        target.present()
        runCurrent()
        assertEquals(Video.Showing(640, 400), h.streaming.video)
        assertEquals(false, h.streaming.displays.switching)
        assertEquals(listOf("02050000" + "00".repeat(12), """{"type":"display-presented","generation":1}"""), h.control.drain())
    }

    @Test
    fun `a new frame generation restarts the decoder at the next keyframe`() = session { h ->
        val target = Target()
        h.session.attach(target)
        h.video.text("""{"type":"video-config","codec":"avc1.42C01E"}""")
        h.video.binary(videoFrame(keyframe = true, generation = 1, timestampUs = 0, payload = "65"))
        h.video.binary(videoFrame(keyframe = false, generation = 2, timestampUs = 100_000, payload = "41"))
        h.video.binary(videoFrame(keyframe = true, generation = 2, timestampUs = 200_000, payload = "6502"))
        runCurrent()
        assertEquals(listOf(true, false), target.decoders.map { it.closed })
        assertEquals(listOf(listOf("65@0"), listOf("6502@200000")), target.decoders.map { it.units })
    }

    @Test
    fun `take over acquires, resizes to the pane once active, and hand back resets then releases`() = session { h ->
        h.session.resize(1080, 2400, 2.625f)
        h.session.takeOver()
        runCurrent()
        assertEquals(Control.Requesting, h.streaming.control)
        assertEquals(listOf("acquire"), h.control.drain())

        h.control.text("""{"type":"control-state","state":"active"}""")
        runCurrent()
        assertEquals(Control.Active, h.streaming.control)
        assertEquals(listOf("02060000" + "30040000" + "60090000" + "3b010100"), h.control.drain())

        h.session.resize(1090, 2410, 2.625f)
        h.control.text("""{"type":"resize-applied","request":1,"generation":2,"width":1072,"height":2400,"scale":315}""")
        runCurrent()
        assertEquals(emptyList<String>(), h.control.drain())
        assertEquals(RemoteSize(1072, 2400, 315), h.streaming.resolution)

        h.session.handBack()
        runCurrent()
        assertEquals(Control.Watching, h.streaming.control)
        assertEquals(listOf("02050000" + "00".repeat(12), "release"), h.control.drain())
    }

    @Test
    fun `another viewer in control shows busy, and a ready desktop is acquired again`() = session { h ->
        h.control.text(TWO_DISPLAYS)
        h.session.takeOver()
        h.control.text("""{"type":"control-state","state":"busy"}""")
        runCurrent()
        assertEquals(Control.Busy, h.streaming.control)
        assertEquals("Another viewer is controlling the desktop.", h.streaming.displays.error)
        h.control.drain()

        h.control.text("""{"type":"control-state","state":"ready"}""")
        runCurrent()
        assertEquals(Control.Requesting, h.streaming.control)
        assertEquals(listOf("acquire"), h.control.drain())
    }

    @Test
    fun `an active grant this viewer no longer wants is released`() = session { h ->
        h.control.text("""{"type":"control-state","state":"active"}""")
        runCurrent()
        assertEquals(Control.Watching, h.streaming.control)
        assertEquals(listOf("release"), h.control.drain())
    }

    @Test
    fun `selecting a display while watching acquires first and selects once active`() = session { h ->
        h.control.text(TWO_DISPLAYS.replace("\"generation\":1", "\"generation\":0"))
        runCurrent()
        h.control.drain()
        h.session.selectDisplay("1")
        runCurrent()
        assertEquals(true, h.streaming.displays.switching)
        assertEquals(listOf("acquire"), h.control.drain())

        h.control.text("""{"type":"control-state","state":"active"}""")
        runCurrent()
        assertEquals(listOf("02050000" + "00".repeat(12), """{"type":"display-select","id":"1"}"""), h.control.drain())
    }

    @Test
    fun `pointer input is withheld while watching and sent as structs while active`() = session { h ->
        h.session.pointAt(0.5f, 0.25f)
        runCurrent()
        assertEquals(emptyList<String>(), h.control.drain())

        activate(h)
        h.session.pointAt(0.5f, 0.25f)
        h.session.click(MouseButton.Left)
        h.session.move(3.5f, -2f)
        h.session.wheel(0f, 48f)
        h.session.pointAt(1.2f, 0.5f)
        runCurrent()
        assertEquals(
            listOf(
                "02010000" + "00800000" + "00400000" + "01000000",
                "02020100" + "10010000" + "00000000" + "02000000",
                "02020000" + "10010000" + "00000000" + "03000000",
                "02080000" + "00006040" + "000000c0" + "04000000",
                "02030000" + "00000000" + "00004042" + "05000000",
            ),
            h.control.drain(),
        )
    }

    @Test
    fun `typed text commits line by line with Enter presses, sharing the input sequence`() = session { h ->
        activate(h)
        h.session.preedit("l")
        h.session.type("ls\npwd")
        runCurrent()
        assertEquals(
            listOf(
                """{"type":"text","action":"preedit","text":"l","sequence":1}""",
                """{"type":"text","action":"commit","text":"ls","sequence":2}""",
                "02040100" + "1c000000" + "00000000" + "03000000",
                "02040000" + "1c000000" + "00000000" + "04000000",
                """{"type":"text","action":"commit","text":"pwd","sequence":5}""",
            ),
            h.control.drain(),
        )
    }

    @Test
    fun `hardware keys send evdev codes with keysyms only when the server resolves them`() = session { h ->
        activate(h, resolvedKeysyms = true)
        h.session.key(KeyEvent.KEYCODE_A, KeyAction.Down, 'a'.code, 0)
        h.session.key(KeyEvent.KEYCODE_A, KeyAction.Up, 'a'.code, 0)
        h.session.key(KeyEvent.KEYCODE_BACK, KeyAction.Down, 0, 0)
        runCurrent()
        h.control.text("""{"type":"control-state","state":"active","resolvedKeysyms":false}""")
        runCurrent()
        h.session.key(KeyEvent.KEYCODE_B, KeyAction.Down, 'b'.code, 0)
        runCurrent()
        assertEquals(
            listOf(
                "02040100" + "1e000000" + "61000000" + "01000000",
                "02040000" + "1e000000" + "61000000" + "02000000",
                "02040100" + "30000000" + "00000000" + "03000000",
            ),
            h.control.drain(),
        )
    }

    @Test
    fun `every second the session pings and reports feedback, and a pong sets the round trip`() = session { h ->
        val target = Target()
        h.session.attach(target)
        h.video.text("""{"type":"video-config","codec":"avc1.42C01E"}""")
        h.video.binary(videoFrame(keyframe = false, generation = 1, timestampUs = 0, payload = "41"))
        h.video.binary(videoFrame(keyframe = true, generation = 1, timestampUs = 100_000, payload = "65"))
        runCurrent()
        target.present()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            listOf("""{"type":"ping","id":1}""", """{"type":"feedback","queue":0,"dropped":1,"fps":1,"rtt":0,"active":true}"""),
            h.control.drain(),
        )

        advanceTimeBy(40)
        h.control.text("""{"type":"pong","id":1,"serverNanos":1729000000000000000}""")
        runCurrent()
        advanceTimeBy(960)
        runCurrent()
        assertEquals(
            listOf("""{"type":"ping","id":2}""", """{"type":"feedback","queue":0,"dropped":0,"fps":0,"rtt":40,"active":false}"""),
            h.control.drain(),
        )
    }

    @Test
    fun `enabled audio plays opus packets from byte 24, honoring the reset flag`() = session { h ->
        h.audio.text("""{"type":"audio-config","version":2,"enabled":true,"codec":"opus","sampleRate":48000,"channels":2}""")
        h.audio.binary(hex("020201" + "00".repeat(21) + "fc01"))
        h.audio.binary(hex("020200" + "00".repeat(21) + "fc02"))
        runCurrent()
        assertEquals(Audio.Playing, h.streaming.audio)
        assertEquals(listOf("reset", "fc01", "fc02"), h.media.audio)
    }

    @Test
    fun `audio the server disables is off, and an unplayable codec fails audio without ending video`() = session { h ->
        h.audio.text("""{"type":"audio-config","version":2,"enabled":false,"codec":"opus","sampleRate":48000,"channels":2}""")
        runCurrent()
        assertEquals(Audio.Off, h.streaming.audio)
        h.audio.text("""{"type":"audio-config","version":2,"enabled":true,"codec":"aac","sampleRate":48000,"channels":2}""")
        runCurrent()
        assertEquals(Audio.Failed("Unsupported audio codec aac"), h.streaming.audio)
    }

    @Test
    fun `server clipboard and quality show in the state, and clipboard writes need control`() = session { h ->
        h.control.text("""{"type":"clipboard","text":"from the desktop"}""")
        h.control.text("""{"type":"quality","bitrate":4500,"scale":100,"fps":30}""")
        h.session.writeClipboard("too early")
        runCurrent()
        assertEquals("from the desktop", h.streaming.remoteClipboard)
        assertEquals(Quality(4500, 100, 30), h.streaming.quality)
        assertEquals(emptyList<String>(), h.control.drain())

        activate(h)
        h.session.writeClipboard("from the phone")
        runCurrent()
        assertEquals(listOf("""{"type":"clipboard-write","text":"from the phone"}"""), h.control.drain())
    }

    @Test
    fun `a display-state selecting a display outside its list is ignored`() = session { h ->
        h.control.text(TWO_DISPLAYS)
        h.control.text(TWO_DISPLAYS.replace("\"selectedID\":\"0\"", "\"selectedID\":\"9\""))
        runCurrent()
        assertEquals("0", h.streaming.displays.selected)
        assertEquals(listOf("02050000" + "00".repeat(12)), h.control.drain())
    }

    @Test
    fun `a closed control channel ends the session`() = session { h ->
        h.control.inbound.close()
        runCurrent()
        assertEquals(SessionState.Ended(EndReason.Closed(DesktopStream.Control)), h.session.state.value)
    }

    @Test
    fun `a channel that cannot open ends the session with its error`() = runTest {
        val session = WaymoteSession(
            channels = object : RelayChannelFactory {
                override suspend fun open(stream: DesktopStream): RelayChannel = error("Desktop relay refused amp-desktop-v1")
            },
            media = Media(),
        )
        session.run()
        assertEquals(SessionState.Ended(EndReason.Failed("Desktop relay refused amp-desktop-v1")), session.state.value)
    }

    private companion object {
        const val TWO_DISPLAYS =
            """{"type":"display-state","displays":[{"id":"0","name":"Built-in","width":1280,"height":800,"isMain":true},""" +
                """{"id":"1","name":"Second","width":1024,"height":768,"isMain":false}],"selectedID":"0","generation":1,"switching":false}"""
        val BUILT_IN = RemoteDisplay("0", "Built-in", 1280, 800, isMain = true)
        val SECOND = RemoteDisplay("1", "Second", 1024, 768, isMain = false)

        fun videoFrame(keyframe: Boolean, generation: Long, timestampUs: Long, payload: String): ByteArray {
            val header = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
                .put(2).put(1).put(if (keyframe) 1 else 0)
                .putLong(12, timestampUs).putInt(20, generation.toInt()).putShort(24, 640).putShort(26, 400)
            return header.array() + hex(payload)
        }
    }
}
