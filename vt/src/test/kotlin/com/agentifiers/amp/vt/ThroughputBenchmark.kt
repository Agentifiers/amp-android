package com.agentifiers.amp.vt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser throughput on the JVM (terminal.md "Performance targets": 20 MB/s on the TriFold) and an approximate
 * memory cost of a full scrollback. The floors are far below the targets so the test stays stable on loaded CI.
 */
class ThroughputBenchmark {
    private val cols = 98
    private val rows = 45

    @Test
    fun parserThroughput() {
        val workloads = linkedMapOf(
            "s08 replay" to s08PtyOutput(),
            "colored ls" to coloredListing(),
            "full-screen redraw" to fullScreenRedraw(),
        )
        for ((name, chunk) in workloads) {
            run(chunk, WARMUP_BYTES)
            val (seconds, snapshots) = run(chunk, MEASURED_BYTES)
            val mbPerSecond = MEASURED_BYTES / seconds / 1_000_000
            println("vt throughput $name: %.1f MB/s (%d MiB, %.2f s, %d snapshots)".format(mbPerSecond, MEASURED_BYTES shr 20, seconds, snapshots))
            assertTrue("$name ran at $mbPerSecond MB/s", mbPerSecond > 5)
        }
    }

    /** Feeds [chunk] repeatedly until [total] bytes went in, taking a snapshot per 64 KiB as a renderer would. */
    private fun run(chunk: ByteArray, total: Int): Pair<Double, Int> {
        val vt = VtEngine(cols, rows)
        var fed = 0
        var sinceSnapshot = 0
        var snapshots = 0
        var cells = 0L
        val start = System.nanoTime()
        while (fed < total) {
            vt.feed(chunk)
            fed += chunk.size
            sinceSnapshot += chunk.size
            if (sinceSnapshot >= 65_536) {
                cells += vt.snapshot().lines.sumOf { it.columns }
                snapshots++
                sinceSnapshot = 0
            }
        }
        val seconds = (System.nanoTime() - start) / 1e9
        assertEquals(snapshots.toLong() * cols * rows, cells)
        return seconds to snapshots
    }

    @Test
    fun memoryForFullScrollback() {
        val runtime = Runtime.getRuntime()
        val before = settledHeap(runtime)
        val vt = VtEngine(cols, rows, scrollbackRows = 10_000)
        repeat(10_000 + rows) { vt.feed(coloredLine(it)) }
        val after = settledHeap(runtime)
        val snapshot = vt.snapshot()
        assertEquals(10_000, snapshot.scrollback)
        assertEquals("file-1-0.txt  file-1-1.txt", snapshot.history[0].text.substring(0, 26))
        assertEquals(VtColor.palette(1), snapshot.history[0].style(0).fg)
        println("vt memory: ~%.1f MB retained by one session with 10,000 scrollback rows of %d-col colored text (heap delta after GC, approximate)".format((after - before) / 1e6, cols))
    }

    private fun settledHeap(runtime: Runtime): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Exactly [cols] visible characters in seven colors, so every row is full and nothing wraps. */
    private fun coloredLine(n: Int): ByteArray = buildString {
        var width = 0
        var i = 0
        while (true) {
            val word = "file-$n-$i.txt  "
            if (width + word.length > cols) break
            append("\u001b[${31 + i % 7}m").append(word)
            width += word.length
            i++
        }
        append("\u001b[2m").append(".".repeat(cols - width)).append("\u001b[0m\r\n")
    }.toByteArray()

    private fun coloredListing(): ByteArray = buildString {
        for (n in 0 until 200) append(String(coloredLine(n), Charsets.UTF_8))
    }.toByteArray()

    private fun fullScreenRedraw(): ByteArray = buildString {
        append("\u001b[H")
        for (r in 1..rows) {
            append("\u001b[$r;1H\u001b[48;5;${r % 256}m\u001b[38;2;200;${r * 5};100m")
            for (c in 0 until cols) append(('a' + (r + c) % 26))
        }
        append("\u001b[0m")
    }.toByteArray()

    private companion object {
        const val WARMUP_BYTES = 8 shl 20
        const val MEASURED_BYTES = 21 shl 20
    }
}
