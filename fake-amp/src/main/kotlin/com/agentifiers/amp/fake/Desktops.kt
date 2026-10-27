package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.WebSocket
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture

/**
 * The media the desktop fake streams, Java resources under `desktop/`, made with
 * `ffmpeg -f lavfi -i testsrc=size=640x400:rate=10 -t 3 -c:v libx264 -profile:v baseline -level 3.0 -bf 0 -g 10
 * -pix_fmt yuv420p -x264-params aud=1:repeat-headers=1 -f h264 testsrc-640x400.h264` and
 * `ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000:duration=3 -ac 2 -c:a libopus -b:a 32k -frame_duration 20 sine-440.opus`.
 */
internal object DesktopMedia {
    const val CODEC = "avc1.42C01E"
    const val WIDTH = 640
    const val HEIGHT = 400
    const val FRAME_MS = 100L
    const val PACKET_MS = 20L

    class AccessUnit(val bytes: ByteString, val keyframe: Boolean)

    /** The H.264 clip cut into access units at each access unit delimiter; every tenth is an IDR with SPS and PPS. */
    val video: List<AccessUnit> by lazy { accessUnits(resource("testsrc-640x400.h264")) }

    /** The Opus packets of the Ogg file, past its OpusHead and OpusTags. */
    val audio: List<ByteString> by lazy { oggPackets(resource("sine-440.opus")).drop(2) }

    private fun resource(name: String): ByteArray =
        DesktopMedia::class.java.getResourceAsStream("/desktop/$name")!!.use { it.readBytes() }

    private fun accessUnits(stream: ByteArray): List<AccessUnit> {
        val nals = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                val prefix = if (i > 0 && stream[i - 1].toInt() == 0) i - 1 else i
                nals += prefix to (stream[i + 3].toInt() and 0x1f)
                i += 3
            } else {
                i++
            }
        }
        val starts = nals.filter { it.second == NAL_AUD }.map { it.first }
        return starts.mapIndexed { index, start ->
            val end = starts.getOrElse(index + 1) { stream.size }
            val keyframe = nals.any { (at, type) -> at in start until end && type == NAL_IDR }
            AccessUnit(stream.copyOfRange(start, end).toByteString(), keyframe)
        }
    }

    private fun oggPackets(bytes: ByteArray): List<ByteString> {
        val packets = ArrayList<ByteString>()
        val packet = ByteArrayOutputStream()
        var page = 0
        while (page + 27 <= bytes.size) {
            check(bytes.copyOfRange(page, page + 4).decodeToString() == "OggS") { "not an Ogg page at $page" }
            val segments = bytes[page + 26].toInt() and 0xff
            var data = page + 27 + segments
            for (s in 0 until segments) {
                val length = bytes[page + 27 + s].toInt() and 0xff
                packet.write(bytes, data, length)
                data += length
                if (length < 255) {
                    packets += packet.toByteArray().toByteString()
                    packet.reset()
                }
            }
            page = data
        }
        return packets
    }

    private const val NAL_AUD = 9
    private const val NAL_IDR = 5
}

/**
 * `amp-desktop-v1` over the actor relay, one desktop per thread. A channel answers `{type:"connect", path}` with
 * `{type:"open"}`, then `/stream` loops the H.264 clip with 40-byte headers, `/audio` loops the Opus packets with
 * 24-byte headers, and `/control` keeps the control and display state the client drives.
 */
internal class Desktops(private val actor: ThreadActor, private val world: World) {
    private class Channel(val id: String, val socket: WebSocket) {
        var path: String? = null
        var task: ScheduledFuture<*>? = null
        var sent = 0L
        var next = 0
    }

    private class Display(val id: String, val name: String, val width: Int, val height: Int, val isMain: Boolean) {
        fun json(): JsonObject = json("id" to id, "name" to name, "width" to width, "height" to height, "isMain" to isMain)
    }

    private val displays = listOf(Display("0", "Built-in", 1280, 800, true), Display("1", "Second", 1024, 768, false))
    private val channels = HashMap<String, Channel>()
    private var controlState = "ready"
    private var selected = displays.first().id
    private var generation = 1L
    private var inputAck = 0L

    fun claims(method: String, params: JsonObject): Boolean =
        if (method == "client_executor_relay_open") params.string("service") == SERVICE else params.string("channelId") in channels

    fun control(socket: WebSocket, method: String, params: JsonObject) {
        val channelId = params.string("channelId") ?: return
        when (method) {
            "client_executor_relay_open" -> channels[channelId] = Channel(channelId, socket)
            "client_executor_relay_close" -> channels.remove(channelId)?.task?.cancel(false)
            "client_executor_relay_data" -> {
                val channel = channels[channelId] ?: return
                val data = params.string("dataBase64")?.decodeBase64() ?: return
                receive(channel, params.bool("binary") == true, data)
            }
        }
    }

    /** False when the frame is not on a desktop channel, so the terminal gets it. */
    fun binary(bytes: ByteString): Boolean {
        val frame = RelayFrame.decode(bytes)?.takeIf { it.kind == RelayFrame.TO_EXECUTOR } ?: return false
        val channel = channels[frame.channelId] ?: return false
        receive(channel, frame.binary, frame.payload)
        return true
    }

    fun drop(socket: WebSocket) {
        channels.values.filter { it.socket == socket }.forEach {
            it.task?.cancel(false)
            channels.remove(it.id)
        }
    }

    private fun receive(channel: Channel, binary: Boolean, payload: ByteString) {
        if (channel.path == null) {
            if (binary) return
            val connect = runCatching { FakeJson.parseToJsonElement(payload.utf8()) as? JsonObject }.getOrNull() ?: return
            if (connect.string("type") != "connect") return
            channel.path = connect.string("path")
            sendText(channel, json("type" to "open"))
            start(channel)
            return
        }
        if (channel.path != "/control") return
        if (binary) input(payload) else controlText(channel, payload.utf8())
    }

    private fun start(channel: Channel) {
        when (channel.path) {
            "/stream" -> {
                sendText(channel, json("type" to "video-config", "codec" to DesktopMedia.CODEC))
                loop(channel, DesktopMedia.FRAME_MS) { sendVideo(channel) }
            }
            "/audio" -> {
                sendText(channel, json("type" to "audio-config", "version" to 2, "enabled" to true, "codec" to "opus", "sampleRate" to 48000, "channels" to 2))
                loop(channel, DesktopMedia.PACKET_MS) { sendAudio(channel) }
            }
            "/control" -> {
                sendText(channel, controlStateJson())
                sendText(channel, displayStateJson())
            }
        }
    }

    private fun loop(channel: Channel, periodMs: Long, step: () -> Unit) {
        step()
        // The world may shut down between steps; a stopped desktop is the right outcome then.
        channel.task = try {
            world.schedule(periodMs) { if (channels[channel.id] === channel) loop(channel, periodMs, step) }
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun sendVideo(channel: Channel) {
        val unit = DesktopMedia.video[channel.next]
        channel.next = (channel.next + 1) % DesktopMedia.video.size
        val header = Buffer()
            .writeByte(2).writeByte(1).writeByte(if (unit.keyframe) 1 else 0).writeByte(0)
            .write(ByteArray(8))
            .writeLongLe(channel.sent++ * DesktopMedia.FRAME_MS * 1000)
            .writeIntLe(generation.toInt())
            .writeShortLe(DesktopMedia.WIDTH).writeShortLe(DesktopMedia.HEIGHT)
            .write(ByteArray(8))
            .writeIntLe(inputAck.toInt())
        sendBinary(channel, header.write(unit.bytes).readByteString())
    }

    private fun sendAudio(channel: Channel) {
        val packet = DesktopMedia.audio[channel.next]
        channel.next = (channel.next + 1) % DesktopMedia.audio.size
        val header = Buffer()
            .writeByte(2).writeByte(2).writeByte(if (channel.sent == 0L) 1 else 0).writeByte(0)
            .write(ByteArray(8))
            .writeLongLe(channel.sent++ * DesktopMedia.PACKET_MS * 1000)
            .writeIntLe(1)
        sendBinary(channel, header.write(packet).readByteString())
    }

    private fun controlText(channel: Channel, text: String) {
        when (text) {
            "acquire" -> setControl("active")
            "release" -> setControl("ready")
            else -> {
                val message = runCatching { FakeJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                when (message.string("type")) {
                    "ping" -> sendText(channel, json("type" to "pong", "id" to message["id"], "serverNanos" to System.currentTimeMillis() * 1_000_000))
                    "clipboard-write" -> message.string("text")?.let { sendText(channel, json("type" to "clipboard", "text" to it)) }
                    "display-select" -> {
                        val id = message.string("id")?.takeIf { id -> displays.any { it.id == id } } ?: return
                        selected = id
                        newGeneration()
                        sendText(channel, displayStateJson())
                    }
                }
            }
        }
    }

    private fun input(payload: ByteString) {
        if (payload.size != 16 || payload[0].toInt() != 2) return
        val fields = Buffer().write(payload.substring(4))
        val a = fields.readIntLe()
        val b = fields.readIntLe()
        val c = fields.readIntLe().toLong() and 0xffffffffL
        when (payload[1].toInt()) {
            KIND_RESET -> Unit
            KIND_RESIZE -> {
                newGeneration()
                val reply = json("type" to "resize-applied", "request" to (c ushr 16), "generation" to generation, "width" to a, "height" to b, "scale" to (c and 0xffff))
                channels.values.filter { it.path == "/control" }.forEach { sendText(it, reply) }
            }
            else -> inputAck = c
        }
    }

    /** A display switch or resize: the next video frame is an IDR of the new generation. */
    private fun newGeneration() {
        generation++
        channels.values.filter { it.path == "/stream" }.forEach { it.next = 0 }
    }

    private fun setControl(state: String) {
        controlState = state
        channels.values.filter { it.path == "/control" }.forEach { sendText(it, controlStateJson()) }
    }

    private fun controlStateJson(): JsonObject = json("type" to "control-state", "state" to controlState, "resolvedKeysyms" to true)

    private fun displayStateJson(): JsonObject = json(
        "type" to "display-state",
        "displays" to JsonArray(displays.map { it.json() }),
        "selectedID" to selected,
        "generation" to generation,
        "switching" to false,
    )

    private fun sendText(channel: Channel, message: JsonObject) {
        actor.sendBinary(channel.socket, RelayFrame(RelayFrame.FROM_EXECUTOR, false, channel.id, message.toString().encodeUtf8()).encode())
    }

    private fun sendBinary(channel: Channel, bytes: ByteString) {
        actor.sendBinary(channel.socket, RelayFrame(RelayFrame.FROM_EXECUTOR, true, channel.id, bytes).encode())
    }

    private companion object {
        const val SERVICE = "amp-desktop-v1"
        const val KIND_RESET = 5
        const val KIND_RESIZE = 6
    }
}
