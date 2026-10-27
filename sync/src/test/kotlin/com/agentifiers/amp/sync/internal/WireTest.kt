package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.model.Drift
import com.agentifiers.amp.model.Seq
import com.agentifiers.amp.sync.internal.net.ThreadSocket
import com.agentifiers.amp.sync.internal.thread.ThreadFold
import com.agentifiers.amp.sync.internal.thread.ThreadReducer
import com.agentifiers.amp.sync.internal.wire.FrameParser
import com.agentifiers.amp.sync.internal.wire.InboundFrame
import com.agentifiers.amp.sync.internal.wire.RivetNumber
import com.agentifiers.amp.sync.internal.wire.ThreadFact
import com.agentifiers.amp.sync.internal.wire.WireDecoders
import org.junit.Assert.assertEquals
import org.junit.Test

class WireTest {
    @Test
    fun unknownEventTypeBecomesDriftAndNeverThrows() {
        val event = FrameParser.parse("""{"jsonrpc":"2.0","method":"brand_new_event","params":{"seq":3}}""").single() as InboundFrame.Event
        val fact = WireDecoders.decode(event)

        assertEquals(ThreadFact.Unrecognized("brand_new_event"), fact)
        assertEquals(Drift.Seen(setOf("brand_new_event"), 1), ThreadReducer.reduce(ThreadFold.empty(), fact).drift)
    }

    @Test
    fun sequencedTypeWithoutSeqIsMalformed() {
        val event = FrameParser.parse("""{"type":"cancelled","messageId":"M-x"}""").single() as InboundFrame.Event
        assertEquals(ThreadFact.Malformed("cancelled"), WireDecoders.decode(event))
    }

    @Test
    fun textFrameShapesNormalize() {
        assertEquals(listOf(InboundFrame.Pong), FrameParser.parse("pong"))
        assertEquals(listOf(InboundFrame.Unparseable(8)), FrameParser.parse("not json"))
        val result = FrameParser.parse("""{"jsonrpc":"2.0","method":"client_git_command_result","params":{"requestId":"r-1","ok":true}}""").single() as InboundFrame.Result
        assertEquals("r-1" to "client_git_command_result", result.requestId to result.type)
        assertEquals("""{"requestId":"r-1","ok":true}""", result.body.toString())
        val bare = FrameParser.parse("""{"type":"error_cleared","seq":9}""").single() as InboundFrame.Event
        assertEquals("error_cleared", bare.type)
        assertEquals(Seq(9), bare.seq)
        val response = FrameParser.parse("""{"jsonrpc":"2.0","id":"thread-client-1-0","error":{"code":-32602,"message":"Invalid method params"}}""").single()
        assertEquals("Invalid method params", (response as InboundFrame.Response).error?.message)
    }

    @Test
    fun decoderRegistryHasNoDuplicateTypes() {
        val types = WireDecoders.entries.map { it.first }
        assertEquals(types.size, types.toSet().size)
    }

    @Test
    fun integersFromTwoToTheThirtyFirstGoOutAsDotZeroLiterals() {
        assertEquals("2147483647", RivetNumber.encode(2147483647).toString())
        assertEquals("2147483648.0", RivetNumber.encode(2147483648).toString())
        assertEquals("1792643912226.0", RivetNumber.encode(1792643912226).toString())
    }

    @Test
    fun connParamsUseJavaScriptEncodeUriComponent() {
        assertEquals("%7B%22a%22%3A%22b%20c!*'()~%22%7D", ThreadSocket.encodeURIComponent("""{"a":"b c!*'()~"}"""))
    }
}
