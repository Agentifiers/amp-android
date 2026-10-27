package com.agentifiers.amp.sync.internal.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayFrameTest {
    private val channel = "relay-d4435c1d-ee77-4032-9bb7-c38d27a64587"
    private val readyBytes = byteArrayOf(0xAE.toByte(), 0x01, 0x02, 0x02, 0x2A) + channel.encodeToByteArray() + """{"type":"ready"}""".encodeToByteArray()

    @Test
    fun decodesTheS08ReadyFrame() {
        val frame = RelayFrame.decode(readyBytes)!!

        assertEquals(RelayKind.FromExecutor, frame.kind)
        assertEquals(false, frame.binary)
        assertEquals(true, frame.fin)
        assertEquals(channel, frame.channelId)
        assertEquals("""{"type":"ready"}""", frame.payload.decodeToString())
    }

    @Test
    fun encodesTheS08ReadyFrameByteForByte() {
        val frames = RelayFrame.chunks(RelayKind.FromExecutor, binary = false, channel, """{"type":"ready"}""".encodeToByteArray())

        assertEquals(1, frames.size)
        assertArrayEquals(readyBytes, frames.single().encode())
    }

    @Test
    fun aBinaryInputFrameSetsFlagsThree() {
        val bytes = RelayFrame.chunks(RelayKind.ToExecutor, binary = true, channel, "pwd\r".encodeToByteArray()).single().encode()

        assertEquals(listOf(0xAE, 0x01, 0x01, 0x03, 0x2A), bytes.take(5).map { it.toInt() and 0xff })
    }

    @Test
    fun rejectsOtherMagicVersionKindAndShortFrames() {
        assertNull(RelayFrame.decode(byteArrayOf(0x7B, 0x01, 0x02, 0x02, 0x01, 0x61)))
        assertNull(RelayFrame.decode(byteArrayOf(0xAE.toByte(), 0x02, 0x02, 0x02, 0x01, 0x61)))
        assertNull(RelayFrame.decode(byteArrayOf(0xAE.toByte(), 0x01, 0x03, 0x02, 0x01, 0x61)))
        assertNull(RelayFrame.decode(byteArrayOf(0xAE.toByte(), 0x01, 0x02, 0x02, 0x05, 0x61)))
    }

    @Test
    fun splitsAtOneMillionBytesWithFinOnlyOnTheLast() {
        val payload = ByteArray(2_500_000) { (it % 251).toByte() }
        val frames = RelayFrame.chunks(RelayKind.ToExecutor, binary = true, channel, payload)

        assertEquals(listOf(1_000_000, 1_000_000, 500_000), frames.map { it.payload.size })
        assertEquals(listOf(false, false, true), frames.map { it.fin })
    }

    @Test
    fun reassemblesChunksPerChannelUntilFin() {
        val other = "relay-00000000-0000-0000-0000-000000000000"
        val payload = ByteArray(2_500_000) { (it % 251).toByte() }
        val mine = RelayFrame.chunks(RelayKind.FromExecutor, binary = true, channel, payload).map { RelayFrame.decode(it.encode())!! }
        val theirs = RelayFrame.chunks(RelayKind.FromExecutor, binary = false, other, "x".encodeToByteArray()).single()
        val reassembler = RelayReassembler()

        assertNull(reassembler.accept(mine[0]))
        assertEquals("x", reassembler.accept(theirs)!!.payload.decodeToString())
        assertNull(reassembler.accept(mine[1]))
        val whole = reassembler.accept(mine[2])!!

        assertEquals(true, whole.binary)
        assertArrayEquals(payload, whole.payload)
    }

    @Test
    fun dropsAMessagePastSixteenMebibytesAndRecoversOnTheNextOne() {
        val reassembler = RelayReassembler()
        val chunk = ByteArray(1_000_000)
        repeat(16) { assertNull(reassembler.accept(RelayFrame(RelayKind.FromExecutor, true, fin = false, channel, chunk))) }
        assertNull(reassembler.accept(RelayFrame(RelayKind.FromExecutor, true, fin = true, channel, ByteArray(777_217))))

        val next = reassembler.accept(RelayFrame(RelayKind.FromExecutor, true, fin = true, channel, "ok".encodeToByteArray()))
        assertEquals("ok", next!!.payload.decodeToString())
    }
}
