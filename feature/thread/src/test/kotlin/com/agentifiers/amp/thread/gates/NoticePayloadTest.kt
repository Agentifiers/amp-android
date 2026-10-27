package com.agentifiers.amp.thread.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticePayloadTest {
    @Test
    fun `a notice ending in JSON shows its lead-in and one line per leaf`() {
        val payload = noticePayload("""mcp-question answered {"action":"accept","content":{"title":"Crash on launch","points":3,"urgent":false,"team":"app"},"tags":["a","b"]}""")
        assertEquals("mcp-question answered", payload?.lead)
        assertEquals(
            listOf("action: accept", "content.title: Crash on launch", "content.points: 3", "content.urgent: false", "content.team: app", "tags: a, b"),
            payload?.lines,
        )
    }

    @Test
    fun `plain text and broken JSON stay as written`() {
        assertNull(noticePayload("deploy-key received 20 characters"))
        assertNull(noticePayload("half {\"action\":"))
        assertNull(noticePayload("a list [1, 2]"))
    }
}
