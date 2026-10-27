package com.agentifiers.amp.vt

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class RandomInputTest {
    private val alphabet: List<ByteArray> = listOf(
        "\u001b", "[", "]", "?", ">", ";", ":", "0", "1", "2", "5", "9", "38", "48", "1049", "2004", "h", "l", "m", "H",
        "J", "K", "L", "M", "P", "@", "X", "S", "T", "r", "b", "q", " ", "(", "0", "B", "7", "8", "D", "E", "c", "#",
        "\r", "\n", "\b", "\t", "\u0007", "\u000e", "\u000f", "\u0018", "8;;https://x.y/", "52;c;aGk=", "\u001b\\",
        "a", "Z", "~", "中", "🔀", "e\u0301", "\u200d", "\ufe0f",
    ).map { it.toByteArray() } + listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xe4.toByte()), byteArrayOf(0x80.toByte()))

    @Test
    fun arbitraryBytesResizesAndQueriesNeverBreakTheEngine() {
        for (seed in 1..12) {
            val random = Random(seed)
            val vt = VtEngine(20, 6, scrollbackRows = 50)
            repeat(300) {
                val chunk = buildList { repeat(random.nextInt(1, 300)) { add(alphabet[random.nextInt(alphabet.size)]) } }
                vt.feed(chunk.fold(ByteArray(0)) { acc, b -> acc + b })
                if (random.nextInt(8) == 0) vt.resize(random.nextInt(1, 40), random.nextInt(1, 12))
                val s = vt.snapshot()
                val line = random.nextInt(s.totalLines)
                val col = random.nextInt(s.cols)
                s.selectionText(GridPoint(0, 0), GridPoint(s.totalLines - 1, s.cols - 1))
                s.wordAt(GridPoint(line, col))
                s.linkAt(GridPoint(line, col))
                vt.takeReplies()
                vt.takeEvents()
            }
            vt.resize(10, 3)
            vt.feed("\u0018\u001b[?1049l\u001b[r\u001b[?6l\u001b[?7h\u001b[4l\u001b(B\u000f\u001b]8;;\u001b\\\u001b[0m\u001b[H\u001b[2Jok".toByteArray())
            assertEquals("seed $seed", listOf("ok", "", ""), vt.snapshot().lines.map { it.text })
        }
    }
}
