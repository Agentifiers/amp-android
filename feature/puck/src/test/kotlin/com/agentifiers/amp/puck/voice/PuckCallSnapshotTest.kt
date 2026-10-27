package com.agentifiers.amp.puck.voice

import com.agentifiers.amp.sync.api.PuckVoiceState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PuckCallSnapshotTest {
    @Test
    fun `each voice state reads as the word the call screen shows`() {
        assertEquals(
            listOf("Connecting", "Connecting", "Listening", "You're speaking", "Puck speaking", "Puck speaking", "Thinking", "Ended", "Ended: Out of credits"),
            listOf(
                PuckVoiceState.Idle,
                PuckVoiceState.Connecting,
                PuckVoiceState.Live(userSpeaking = false, puckSpeaking = false, transcriptTail = ""),
                PuckVoiceState.Live(userSpeaking = true, puckSpeaking = false, transcriptTail = ""),
                PuckVoiceState.Live(userSpeaking = false, puckSpeaking = true, transcriptTail = ""),
                PuckVoiceState.Live(userSpeaking = true, puckSpeaking = true, transcriptTail = ""),
                PuckVoiceState.Thinking(transcriptTail = "hi"),
                PuckVoiceState.Ended(null),
                PuckVoiceState.Ended("Out of credits"),
            ).map { it.words() },
        )
    }

    @Test
    fun `a muted frame is zeros of the length read, and an open mic sends what it read`() {
        val buffer = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), uplinkFrame(buffer, 4, muted = true))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), uplinkFrame(buffer, 4, muted = false))
    }

    @Test
    fun `switching route cycles through the endpoints Telecom offers`() {
        assertEquals("speaker", nextRoute(listOf("earpiece", "speaker", "bluetooth"), "earpiece"))
        assertEquals("earpiece", nextRoute(listOf("earpiece", "speaker", "bluetooth"), "bluetooth"))
        assertEquals("earpiece", nextRoute(listOf("earpiece", "speaker"), null))
        assertNull(nextRoute(listOf("earpiece"), "earpiece"))
    }
}
