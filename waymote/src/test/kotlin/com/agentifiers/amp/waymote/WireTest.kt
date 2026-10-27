package com.agentifiers.amp.waymote

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireTest {
    @Test
    fun `a video frame header reads flags, timestamp, generation, size and input ack little-endian`() {
        val bytes = hex(
            "020103" + "00" + "0000000000000000" + "0504030201000000" + "07000000" + "0005" + "2003" +
                "0000000000000000" + "04030201" + "0000000109f0",
        )
        val frame = VideoFrame.parse(bytes)!!
        assertEquals(true, frame.keyframe)
        assertEquals(true, frame.flush)
        assertEquals(4_328_719_365L, frame.timestampUs)
        assertEquals(7L, frame.generation)
        assertEquals(1280 to 800, frame.width to frame.height)
        assertEquals(16_909_060L, frame.inputAck)
        assertEquals("0000000109f0", frame.bytes.copyOfRange(VideoFrame.PAYLOAD, frame.bytes.size).toHex())
    }

    @Test
    fun `a delta video frame with a high generation reads as unsigned and other kinds are refused`() {
        val delta = hex("020100" + "00".repeat(9) + "40420f0000000000" + "feffffff" + "8002" + "9001" + "00".repeat(12) + "aa")
        val frame = VideoFrame.parse(delta)!!
        assertEquals(false, frame.keyframe)
        assertEquals(false, frame.flush)
        assertEquals(1_000_000L, frame.timestampUs)
        assertEquals(4_294_967_294L, frame.generation)
        assertEquals(640 to 400, frame.width to frame.height)
        assertNull(VideoFrame.parse(hex("0202" + "00".repeat(40))))
        assertNull(VideoFrame.parse(hex("0201" + "00".repeat(30))))
    }

    @Test
    fun `an audio frame header reads the reset flag, timestamp and generation`() {
        val frame = AudioFrame.parse(hex("020201" + "00".repeat(9) + "204e000000000000" + "03000000" + "fc"))!!
        assertEquals(true, frame.reset)
        assertEquals(20_000L, frame.timestampUs)
        assertEquals(3L, frame.generation)
        assertEquals(AudioFrame.PAYLOAD, frame.bytes.size - 1)
        assertNull(AudioFrame.parse(hex("020101" + "00".repeat(30))))
    }

    @Test
    fun `input structs encode as 16 little-endian bytes with a sequence that reset and resize do not consume`() {
        val input = InputEncoder()
        assertEquals("02010000" + "00800000" + "00400000" + "01000000", input.encode(InputEvent.PointerAbsolute(32768, 16384)).toHex())
        assertEquals("02020100" + "10010000" + "00000000" + "02000000", input.encode(InputEvent.Button(MouseButton.Left, down = true)).toHex())
        assertEquals("02020000" + "11010000" + "00000000" + "03000000", input.encode(InputEvent.Button(MouseButton.Right, down = false)).toHex())
        assertEquals("02030000" + "00000000" + "000080c0" + "04000000", input.encode(InputEvent.Wheel(0f, -4f)).toHex())
        assertEquals("02040200" + "1e000000" + "61000000" + "05000000", input.encode(InputEvent.Key(30, KeyAction.Repeat, 0x61)).toHex())
        assertEquals("02050000" + "00000000" + "00000000" + "00000000", input.encode(InputEvent.Reset).toHex())
        assertEquals("02060000" + "30040000" + "60090000" + "3b010100", input.encode(InputEvent.Resize(1072, 2400, 315)).toHex())
        assertEquals("02080000" + "00008045" + "000080c5" + "06000000", input.encode(InputEvent.PointerRelative(9000f, -4096f)).toHex())
    }

    @Test
    fun `the resize request id wraps past zero`() {
        val input = InputEncoder()
        repeat(65_535) { input.encode(InputEvent.Resize(320, 180, 120)) }
        assertEquals("78000100", input.encode(InputEvent.Resize(320, 180, 120)).toHex().takeLast(8))
    }

    @Test
    fun `a viewport snaps to multiples of 16 within the protocol bounds and scale`() {
        assertEquals(RemoteSize(1072, 2400, 315), RemoteSize.fit(1080, 2400, 2.625f))
        assertEquals(RemoteSize(3840, 2160, 120), RemoteSize.fit(7680, 4320, 1f))
        assertEquals(RemoteSize(2832, 2912, 480), RemoteSize.fit(2840, 2930, 5f))
        assertEquals(RemoteSize(320, 180, 120), RemoteSize.fit(200, 100, 1f))
        assertNull(RemoteSize.fit(0, 600, 2f))
    }

    @Test
    fun `android key codes map to the evdev codes the web client sends`() {
        val mapped = listOf(
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_Q,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_META_LEFT,
        ).map { Keyboard.evdev(it) }
        assertEquals(listOf(1, 2, 11, 14, 16, 28, 30, 50, 57, 59, 88, 103, 111, 125), mapped)
        assertNull(Keyboard.evdev(KeyEvent.KEYCODE_BACK))
        assertNull(Keyboard.evdev(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `keysyms are Latin-1 as is, other code points tagged, and nothing for control chords`() {
        assertEquals(0x61, Keyboard.keysym('a'.code, 0))
        assertEquals(0xe9, Keyboard.keysym('é'.code, KeyEvent.META_SHIFT_ON))
        assertEquals(0x010020ac, Keyboard.keysym('€'.code, 0))
        assertEquals(0, Keyboard.keysym('a'.code, KeyEvent.META_CTRL_ON))
        assertEquals(0, Keyboard.keysym('a'.code, KeyEvent.META_META_ON))
        assertEquals(0x40, Keyboard.keysym('@'.code, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON))
        assertEquals(0, Keyboard.keysym('\n'.code, 0))
    }

    @Test
    fun `control text parses into typed messages and ignores what the client does not read`() {
        assertEquals(ControlIn.VideoConfig("avc1.42C01E"), ControlIn.parse("""{"type":"video-config","codec":"avc1.42C01E","extra":1}"""))
        assertEquals(
            ControlIn.AudioConfig(version = 2, enabled = true, codec = "opus", sampleRate = 48000, channels = 2),
            ControlIn.parse("""{"type":"audio-config","version":2,"enabled":true,"codec":"opus","sampleRate":48000,"channels":2}"""),
        )
        assertEquals(ControlIn.ControlState(ServerControl.Busy, resolvedKeysyms = false), ControlIn.parse("""{"type":"control-state","state":"busy"}"""))
        assertEquals(
            ControlIn.DisplayState(listOf(WireDisplay("0", "Built-in", 1280, 800, true)), "0", 3, switching = false, error = null),
            ControlIn.parse("""{"type":"display-state","displays":[{"id":"0","name":"Built-in","width":1280,"height":800,"isMain":true}],"selectedID":"0","generation":3,"switching":false}"""),
        )
        assertEquals(ControlIn.Pong(9), ControlIn.parse("""{"type":"pong","id":9,"serverNanos":1729000000000000000}"""))
        assertEquals(ControlIn.ResizeApplied(4, 2, 1072, 2400, 315), ControlIn.parse("""{"type":"resize-applied","request":4,"generation":2,"width":1072,"height":2400,"scale":315}"""))
        assertEquals(ControlIn.Clipboard("hi"), ControlIn.parse("""{"type":"clipboard","text":"hi"}"""))
        assertEquals(ControlIn.Quality(4500.0, 100.0, 30.0), ControlIn.parse("""{"type":"quality","bitrate":4500,"scale":100,"fps":30}"""))
        assertNull(ControlIn.parse("""{"type":"cursor","shape":"text"}"""))
        assertNull(ControlIn.parse("""{"type":"control-state","state":"asleep"}"""))
        assertNull(ControlIn.parse("acquire"))
    }

    @Test
    fun `client control messages encode with the type first`() {
        assertEquals("""{"type":"ping","id":1}""", ControlOut.Ping(1).encode())
        assertEquals("""{"type":"feedback","queue":2,"dropped":1,"fps":30,"rtt":41,"active":true}""", ControlOut.Feedback(2, 1, 30, 41, true).encode())
        assertEquals("""{"type":"clipboard-write","text":"copied"}""", ControlOut.ClipboardWrite("copied").encode())
        assertEquals("""{"type":"display-select","id":"1"}""", ControlOut.DisplaySelect("1").encode())
        assertEquals("""{"type":"display-presented","generation":2}""", ControlOut.DisplayPresented(2).encode())
        assertEquals("""{"type":"text","action":"preedit","text":"he","sequence":7}""", ControlOut.Text(TextAction.Preedit, "he", 7).encode())
    }

    @Test
    fun `codec strings map to MediaCodec MIME types by sample entry`() {
        assertEquals(
            listOf("video/avc", "video/hevc", "video/hevc", "video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8", "video/av01", null),
            listOf("avc1.42C01E", "hvc1.1.6.L93.B0", "hev1.1.6.L93.B0", "vp09.00.10.08", "vp8", "av01.0.04M.08", "theora").map(::videoMime),
        )
    }
}

internal fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
