// :sync  src/test  package amp.sync.internal.thread
// The executable form of invariants I1–I8 (Facts.kt). Runs on the JVM over every capture in
// spikes/protocol/captures/*.jsonl that has thread-channel recv frames, plus the :fake-amp drift
// scenarios. The oracle is the capture fed once, start to finish; every other way of feeding the same
// frames must end in the same cache dump and the same DurableThread.
package amp.sync.internal.thread

import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadReducerReplayTest {

    @Test fun `feeding a capture twice equals feeding it once`() = forEachCapture { frames ->
        val once = Harness().feed(frames)
        val twice = Harness().feed(frames).feed(frames)                       // I1, I3, I6
        assertEquals(once.dump(), twice.dump())
    }

    /**
     * Split at every frame k. Feed 0..k, simulate process death (rebuild DurableThread from the cache
     * alone, new Epoch), then resume the way the server does (S17: replay at s17-resume-cursor.jsonl:10-44,
     * snapshot burst at :45-70, measured): every Sequenced frame, old and new, in capture order, then
     * the snapshot burst. Re-applied Sequenced frames must upsert to the same rows (I1), and the volatile
     * tier must not leak across the restart (I4).
     */
    @Test fun `a resume after any prefix equals no interruption`() = forEachCapture { frames ->
        val oracle = Harness().feed(frames).dump()
        for (k in frames.indices) {
            val resumed = Harness().feed(frames.take(k)).restart().feed(resumeBurst(frames)).dump()
            assertEquals(oracle, resumed, "split at frame $k")
        }
    }

    @Test fun `cache rows and cursor move together`() = forEachCapture { frames ->
        Harness().feed(frames) { write -> assertEquals(write.cursorSeq, maxSeqSoFar(write)) }   // I2
    }

    @Test fun `volatile facts never reach the cache`() = forEachCapture { frames ->
        val types = Harness().feed(frames).cachedTypes()
        assertEquals(emptySet(), types intersect setOf("delta", "agent_state", "tool_progress", "prompt_drafts", "inference_tools"))  // I4
    }

    @Test fun `plugin dialogs never reach the cache`() = forEachCapture { frames ->
        val h = Harness().feed(frames)
        assertEquals(emptySet(), h.cachedAspects() intersect setOf("PluginUi"))                 // I3
    }

    @Test fun `a seq-less frame of a Sequenced type is drift, not state`() {
        val h = Harness().feed(listOf(frame("""{"type":"queued_message_added","queuedMessage":{"messageId":"M-x"}}""")))
        assertEquals(listOf("queued_message_added"), h.durable().drift.malformedTypes())         // I8
        assertEquals(0, h.durable().queue.size)
    }

    @Test fun `an echoed client messageId acknowledges the outbox`() {
        val h = Harness().withOutboxSend("M-client1")
        h.feed(listOf(frame("""{"type":"message_added","seq":5,"message":{"messageId":"M-client1","role":"user"}}""")))
        assertEquals(listOf("M-client1"), h.acknowledged())                                      // I7
    }

    @Test fun `a queued echo acknowledges a steer before it is dequeued`() {
        val h = Harness().withOutboxSend("M-client2")
        h.feed(listOf(frame("""{"type":"queued_message_added","seq":35,"message":{"steer":true,"queuedMessage":{"messageId":"M-client2"}}}""")))
        assertEquals(listOf("M-client2"), h.acknowledged())                                      // I7, shape from s05-steer-interrupt.jsonl:56
    }

    @Test fun `a removed plugin dialog leaves the gates and settles its notification`() {
        val h = Harness().feed(listOf(
            frame("""{"type":"plugin_message","message":{"type":"event","event":"plugin.ui.request_added","data":{"request":{"requestId":"r1","method":"ui.confirm","status":"pending"}}}}"""),
            frame("""{"type":"plugin_message","message":{"type":"event","event":"plugin.ui.request_removed","data":{"requestId":"r1"}}}"""),
        ))
        assertEquals(0, h.durable().gates.size)
        assertEquals(listOf("r1"), h.settledGates())
    }

    private fun forEachCapture(body: (List<Frame>) -> Unit): Unit = TODO("load thread-channel recv lines, in capture order")
    private fun resumeBurst(frames: List<Frame>): List<Frame> = TODO("all Sequenced frames in capture order, then each Snapshot aspect's latest frame")
    private fun maxSeqSoFar(write: amp.store.CacheWrite): Long = TODO()
    private fun frame(json: String): Frame = TODO()
}

/** Real decoders + real ThreadReducer + an in-memory ThreadCache with the same commit semantics as Room. */
private class Harness {
    fun feed(frames: List<Frame>, onCommit: (amp.store.CacheWrite) -> Unit = {}): Harness = TODO()
    fun restart(): Harness = TODO("ThreadReducer.rebuild(cache.load()), Epoch + 1")
    fun withOutboxSend(messageId: String): Harness = TODO()
    fun dump(): String = TODO("cache rows sorted by key + DurableThread minus drift, as stable text")
    fun durable(): DurableThread = TODO()
    fun cachedTypes(): Set<String> = TODO()
    fun cachedAspects(): Set<String> = TODO()
    fun acknowledged(): List<String> = TODO()
    fun settledGates(): List<String> = TODO()
}
private class Frame
private fun amp.model.Degradation.malformedTypes(): List<String> = TODO()
