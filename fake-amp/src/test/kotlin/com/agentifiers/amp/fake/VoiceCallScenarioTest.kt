package com.agentifiers.amp.fake

import com.agentifiers.amp.fake.scenarios.VoiceCall
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class VoiceCallScenarioTest {
    @Test
    fun `voice-call answers speech with a transcript, the 440 Hz clip, and a move to the runner thread, then ends on stop`() =
        FakeAmp.start("voice-call", frameGapCapMs = 10).use { server ->
            val socket = Wire(server).threadSocket(VoiceCall.PUCK_THREAD)
            val resume = socket.request("client_resume", json("version" to 0))
            socket.readUntil { it.string("id") == resume }

            val start = socket.request("client_puck_voice_start", json("version" to "v3"))
            val opened = socket.readUntil { it.isEvent("puck_voice_state") && it.params().string("status") == "listening" }
            assertEquals(json(), opened.first { it.string("id") == start }.obj("result"))
            assertEquals(listOf("connecting:false:false", "listening:false:false"), opened.mapNotNull(::voiceState))

            socket.notify("client_puck_voice_audio", json("audioBase64" to pcm(4_800) { 8_000 }))
            repeat(3) { socket.notify("client_puck_voice_audio", json("audioBase64" to pcm(4_800) { 0 })) }
            val answer = socket.readUntil { it.isEvent("puck_platform_navigation") }

            assertEquals(
                listOf("listening:true:false", "listening:false:false", "thinking:false:false", "listening:false:true", "listening:false:false"),
                answer.mapNotNull(::voiceState),
            )
            assertEquals(listOf("Your runner thread finished its checks."), answer.filter { it.isEvent("puck_voice_transcript") }.map { it.params().string("delta") })
            val audio = answer.filter { it.isEvent("puck_voice_audio") }.map { it.params() }
            assertEquals(List(10) { 24_000L }, audio.map { it.long("sampleRate") })
            val clip = audio.map { Base64.getDecoder().decode(it.string("audioBase64")) }.reduce(ByteArray::plus)
            assertEquals(48_000, clip.size)
            assertEquals("1fd3d223670af8f0f30dbea8a2cfafe8dbf947c58a1f5d11e928798270877956", sha256(clip))
            assertEquals(
                json("type" to "thread", "threadID" to RUNNER_THREAD),
                answer.last().params().obj("destination"),
            )

            val stop = socket.request("client_puck_voice_stop", json())
            val ended = socket.readUntil { it.string("id") == stop }
            assertEquals(listOf("ended:false:false"), ended.mapNotNull(::voiceState))
            assertEquals(json(), ended.last().obj("result"))
            socket.close()
        }

    private fun voiceState(frame: JsonObject): String? {
        if (!frame.isEvent("puck_voice_state")) return null
        val state = frame.params()
        return "${state.string("status")}:${state.bool("userSpeaking")}:${state.bool("puckSpeaking")}"
    }

    private fun pcm(bytes: Int, sample: (Int) -> Int): String {
        val out = ByteArray(bytes)
        for (i in 0 until bytes / 2) {
            val value = sample(i)
            out[2 * i] = value.toByte()
            out[2 * i + 1] = (value shr 8).toByte()
        }
        return Base64.getEncoder().encodeToString(out)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
