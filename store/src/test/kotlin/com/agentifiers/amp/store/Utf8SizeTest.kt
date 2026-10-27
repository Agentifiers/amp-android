package com.agentifiers.amp.store

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8SizeTest {
    @Test
    fun asciiIsOneBytePerChar() {
        assertEquals(11, utf8Size("hello world"))
        assertEquals("hello world".encodeToByteArray().size, utf8Size("hello world"))
    }

    @Test
    fun latinAndGreekAreTwoBytesPerChar() {
        assertEquals(10, utf8Size("éñüßλ"))
        assertEquals("éñüßλ".encodeToByteArray().size, utf8Size("éñüßλ"))
    }

    @Test
    fun cjkAndEuroAreThreeBytesPerChar() {
        assertEquals(9, utf8Size("漢字€"))
        assertEquals("漢字€".encodeToByteArray().size, utf8Size("漢字€"))
    }

    @Test
    fun surrogatePairIsFourBytes() {
        assertEquals(8, utf8Size("😀🚀"))
        assertEquals("😀🚀".encodeToByteArray().size, utf8Size("😀🚀"))
    }

    @Test
    fun mixedTextSumsEachClass() {
        assertEquals(1 + 2 + 3 + 4, utf8Size("aé漢😀"))
        assertEquals("aé漢😀".encodeToByteArray().size, utf8Size("aé漢😀"))
    }
}
