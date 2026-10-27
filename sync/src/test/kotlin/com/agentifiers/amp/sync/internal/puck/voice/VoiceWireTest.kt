package com.agentifiers.amp.sync.internal.puck.voice

import com.agentifiers.amp.model.MachineTab
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceWireTest {
    private val thread = ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!

    private fun decode(type: String, body: String) = VoiceWire.decode(type, AmpJson.parseToJsonElement(body).jsonObject)

    private fun navigate(destination: String) = (decode("puck_platform_navigation", """{"destination":$destination}""") as VoiceEvent.Navigation).destination

    @Test
    fun `every puck_platform_navigation destination maps to the pane it names`() {
        val id = thread.raw
        assertEquals(PaneDestination.Thread(thread), navigate("""{"type":"thread","threadID":"$id"}"""))
        assertEquals(PaneDestination.Machine(thread, MachineTab.Portal(null)), navigate("""{"type":"thread_view","threadID":"$id","view":"portal"}"""))
        assertEquals(PaneDestination.Machine(thread, MachineTab.Changes(null)), navigate("""{"type":"thread_view","threadID":"$id","view":"diffs"}"""))
        assertEquals(PaneDestination.Machine(thread, MachineTab.Changes("src/app.ts")), navigate("""{"type":"file","threadID":"$id","path":"src/app.ts","view":"diffs"}"""))
        assertEquals(PaneDestination.Machine(thread, MachineTab.Files("src/app.ts")), navigate("""{"type":"file","threadID":"$id","path":"src/app.ts","view":"files"}"""))
        assertEquals(
            PaneDestination.Machine(thread, MachineTab.Portal(8765)),
            navigate("""{"type":"portal","threadID":"$id","url":"https://t-exampleportal000000000000-p8765.onamp.dev/"}"""),
        )
        assertEquals(PaneDestination.Machine(thread, MachineTab.Portal(3000)), navigate("""{"type":"portal","threadID":"$id","url":"http://localhost:3000/app"}"""))
        assertEquals(VoiceEvent.Malformed("puck_platform_navigation"), decode("puck_platform_navigation", """{"destination":{"type":"portal","threadID":"$id","url":"ftp://x/"}}"""))
        assertEquals(VoiceEvent.Malformed("puck_platform_navigation"), decode("puck_platform_navigation", """{"destination":{"type":"thread","threadID":"nope"}}"""))
    }

    @Test
    fun `pcm16_24k audio decodes to bytes and every other downlink format is unplayable`() {
        assertArrayEquals(byteArrayOf(0, 0, -42, 1), (decode("puck_voice_audio", """{"audioBase64":"AADWAQ==","sampleRate":24000}""") as VoiceEvent.Audio).pcm)
        assertArrayEquals(
            byteArrayOf(0, 0, -42, 1),
            (decode("puck_voice_audio", """{"audioBase64":"AADWAQ==","sampleRate":24000,"format":"pcm16_24k"}""") as VoiceEvent.Audio).pcm,
        )
        assertEquals(VoiceEvent.Unplayable("adpcm_ima_16k", 16_000), decode("puck_voice_audio", """{"audioBase64":"AADWAQ==","sampleRate":16000,"format":"adpcm_ima_16k"}"""))
        assertEquals(VoiceEvent.Unplayable("opus_24k", 24_000), decode("puck_voice_audio", """{"audioBase64":"AADWAQ==","sampleRate":24000,"format":"opus_24k"}"""))
        assertEquals(VoiceEvent.Unplayable("pcm16_24k", 16_000), decode("puck_voice_audio", """{"audioBase64":"AADWAQ==","sampleRate":16000}"""))
    }

    @Test
    fun `state carries the error string and other thread events stay with the thread`() {
        assertEquals(
            VoiceEvent.State(VoiceStatus.Error, userSpeaking = false, puckSpeaking = false, error = "Out of credits"),
            decode("puck_voice_state", """{"status":"error","userSpeaking":false,"puckSpeaking":false,"error":"Out of credits"}"""),
        )
        assertEquals(VoiceEvent.Malformed("puck_voice_state"), decode("puck_voice_state", """{"status":"ringing"}"""))
        assertNull(decode("puck_media_presentation", """{"presentation":null}"""))
        assertNull(decode("agent_state", """{"state":"idle"}"""))
    }

    @Test
    fun `uplink splits into notifications of at most 32000 base64 characters on whole samples`() {
        val chunks: List<JsonObject> = VoiceWire.audioParams(ByteArray(50_000) { it.toByte() })
        assertEquals(listOf(32_000, 32_000, 2_668), chunks.map { it.string("audioBase64")!!.length })
        assertEquals(listOf(setOf("audioBase64")), chunks.map { it.keys }.distinct())
        assertEquals("""{"version":"v3"}""", VoiceWire.startParams.toString())
    }
}
