package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.model.AttachmentDraft
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.OutboxState
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.UploadState
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.MachineHarness.Companion.RUNNER_THREAD
import com.agentifiers.amp.sync.internal.attachments.AttachmentUploadsEngine
import com.agentifiers.amp.sync.internal.net.Outcome as NetOutcome
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.array
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)

    @Test
    fun uploadThenSendCarriesTheImageBlockAndTheFileLink() = runBlocking {
        FakeAmp.start("attachments", frameGapCapMs = 20).use { server ->
            MachineHarness(server).use { harness ->
                val runner = ThreadId.parse(RUNNER_THREAD)!!
                val upload = harness.uploader.upload(runner, "spike-blue.png", "image/png", png.size.toLong(), flowOf(png))
                val url = (upload as Outcome.Ok).value
                assertTrue(url, url.startsWith("${server.ampBaseUrl}/user-content/attachments/") && url.endsWith("-spike-blue.png"))

                val thread = harness.thread(RUNNER_THREAD)
                harness.live(thread)
                val prompt = "What single color fills the attached image? Answer with one word."
                val attachments = persistentListOf(
                    AttachmentDraft("file:///sdcard/Download/spike-blue.png", "image/png", UploadState.Ready(url)),
                    AttachmentDraft("content://com.android.providers.downloads.documents/document/notes.pdf", "application/pdf", UploadState.Ready("$url-notes")),
                )
                val messageId = MessageId.mint()
                thread.dispatch(ThreadIntent.Send(Draft(prompt, attachments), SendMode.Auto, messageId))

                val expected = AmpJson.parseToJsonElement(
                    """
                    [
                      {"type":"text","text":"$prompt\n$url-notes"},
                      {"type":"image","source":{"type":"url","url":"$url"},"sourcePath":"spike-blue.png"}
                    ]
                    """,
                )
                assertEquals(expected, sentContent(harness, messageId))
            }
        }
    }

    @Test
    fun sendWithAnAttachmentStillUploadingIsRejectedWithoutAPendingRow() = runBlocking {
        FakeAmp.start("attachments").use { server ->
            MachineHarness(server).use { harness ->
                val thread = harness.thread(RUNNER_THREAD)
                harness.live(thread)
                val draft = Draft("look", persistentListOf(AttachmentDraft("file:///sdcard/a.png", "image/png", UploadState.Uploading(0.5f))))
                val intent = thread.dispatch(ThreadIntent.Send(draft, SendMode.Auto))

                val state = withTimeout(10_000) { thread.state.first { s -> s.outbox.any { it.id == intent } } }
                assertEquals(OutboxState.Rejected("Attachments are still uploading."), state.outbox.single { it.id == intent }.state)
                assertEquals(emptyList<Any>(), state.pending)
            }
        }
    }

    @Test
    fun uploadStatesEndReadyAndRefuseOverTheLimitWithoutNetwork() = runBlocking {
        FakeAmp.start("attachments").use { server ->
            MachineHarness(server).use { harness ->
                val uploads = AttachmentUploadsEngine(harness.uploader)
                assertEquals(100L * 1024 * 1024, uploads.maxBytes)

                val states = uploads.upload(null, "blue.png", "image/png", png.size.toLong(), flowOf(png)).toList()
                val ready = states.last() as UploadState.Ready
                assertTrue(ready.url, ready.url.startsWith("${server.ampBaseUrl}/user-content/attachments/") && ready.url.endsWith("-blue.png"))
                assertTrue(states.dropLast(1).all { it is UploadState.Uploading })

                val refused = uploads.upload(null, "huge.bin", "application/octet-stream", uploads.maxBytes + 1, flowOf()).toList()
                assertEquals(listOf(UploadState.Failed("Too large to attach (limit 100 MB)")), refused)
            }
        }
    }

    /** The user message's content as the fake stored it, read back through the history page the app also reads. */
    private suspend fun sentContent(harness: MachineHarness, messageId: MessageId): JsonElement? = withTimeout(20_000) {
        var content: JsonElement? = null
        while (content == null) {
            val page = harness.http.internal("getThreadInitialTranscriptPage", buildJsonObject { put("thread", RUNNER_THREAD) })
            content = ((page as? NetOutcome.Ok)?.value as? JsonObject)?.array("messages")
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("protocolMessageID") == messageId.raw }
                ?.get("content")
            if (content == null) delay(100)
        }
        content
    }
}
