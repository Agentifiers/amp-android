package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant

internal enum class Dir { Send, Recv, Note }

/** One line of a capture in `spikes/protocol/captures`: `{n, t, ch, dir, data}`. */
internal class CaptureLine(val n: Int, val atMs: Long, val channel: String, val dir: Dir, val data: JsonElement) {
    val frame: JsonObject? get() = data as? JsonObject
}

/** A recorded `/api/internal` call: the params sent and the body that came back. */
internal class InternalExchange(val method: String, val params: JsonObject, val body: JsonObject)

/** A capture file, bundled as the resource `captures/<name>.jsonl` by the module's copy task. */
internal class Capture private constructor(val name: String, val lines: List<CaptureLine>) {

    fun channel(name: String): List<CaptureLine> = lines.filter { it.channel == name }

    fun line(n: Int): CaptureLine = lines.first { it.n == n }

    fun internalExchanges(): List<InternalExchange> = pairs { it.startsWith("internal.") }.mapNotNull { (send, recv) ->
        val body = send.frame?.obj("body") ?: return@mapNotNull null
        val reply = recv.frame ?: return@mapNotNull null
        InternalExchange(
            method = send.channel.removePrefix("internal."),
            params = body.obj("params") ?: JsonObject(emptyMap()),
            body = reply.obj("body") ?: return@mapNotNull null,
        )
    }

    /** The body of the response to the first send on [channel]. */
    fun responseBody(channel: String): JsonObject = pairs { it == channel }.first().second.frame!!.obj("body")!!

    private fun pairs(channel: (String) -> Boolean): List<Pair<CaptureLine, CaptureLine>> {
        val open = HashMap<String, CaptureLine>()
        val out = ArrayList<Pair<CaptureLine, CaptureLine>>()
        for (line in lines) {
            if (!channel(line.channel)) continue
            when (line.dir) {
                Dir.Send -> open[line.channel] = line
                Dir.Recv -> open.remove(line.channel)?.let { out += it to line }
                Dir.Note -> Unit
            }
        }
        return out
    }

    companion object {
        private val cache = HashMap<String, Capture>()

        @Synchronized
        fun load(name: String): Capture = cache.getOrPut(name) {
            val stream = Capture::class.java.getResourceAsStream("/captures/$name.jsonl")
                ?: error("capture $name.jsonl is not bundled in :fake-amp")
            val lines = stream.bufferedReader().useLines { text ->
                text.filter { it.isNotBlank() }.map { raw ->
                    val o = FakeJson.parseToJsonElement(raw).jsonObject
                    CaptureLine(
                        n = o.long("n")!!.toInt(),
                        atMs = Instant.parse(o.string("t")!!).toEpochMilli(),
                        channel = o.string("ch")!!,
                        dir = when (o.string("dir")) {
                            "send" -> Dir.Send
                            "recv" -> Dir.Recv
                            else -> Dir.Note
                        },
                        data = o["data"]!!,
                    )
                }.toList()
            }
            Capture(name, lines)
        }
    }
}
