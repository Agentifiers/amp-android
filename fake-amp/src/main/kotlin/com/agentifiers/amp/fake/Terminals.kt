package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import okhttp3.WebSocket
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.ScheduledFuture

/** A relay frame (transport.md, "Relay frames share the thread socket"; `spikes/protocol/lib.ts:126-145`). */
internal class RelayFrame(val kind: Int, val binary: Boolean, val channelId: String, val payload: ByteString) {
    fun encode(): ByteString {
        val id = channelId.encodeUtf8()
        return Buffer()
            .writeByte(MAGIC).writeByte(1).writeByte(kind)
            .writeByte((if (binary) 1 else 0) or FIN)
            .writeByte(id.size).write(id).write(payload)
            .readByteString()
    }

    companion object {
        const val MAGIC = 0xAE
        const val FIN = 2
        const val TO_EXECUTOR = 1
        const val FROM_EXECUTOR = 2

        fun decode(bytes: ByteString): RelayFrame? {
            if (bytes.size < 5 || (bytes[0].toInt() and 0xff) != MAGIC || bytes[1].toInt() != 1) return null
            val idLength = bytes[4].toInt() and 0xff
            if (bytes.size < 5 + idLength) return null
            return RelayFrame(
                kind = bytes[2].toInt(),
                binary = (bytes[3].toInt() and 1) == 1,
                channelId = bytes.substring(5, 5 + idLength).utf8(),
                payload = bytes.substring(5 + idLength),
            )
        }
    }
}

/** What the S8 shell printed, split into the opening screen and the prompt it redraws after each command. */
private object Shell {
    private val output: String by lazy { Capture.load("s08-terminal").line(96).frame!!.string("ptyOutput")!! }
    private const val PASTE_ON = "\u001b[?2004h"
    private const val PROMPT_START = "\u001b[1m\u001b[7m%"

    val banner: String by lazy { output.substring(0, output.indexOf(PASTE_ON) + PASTE_ON.length) }
    val prompt: String by lazy { output.substring(output.lastIndexOf(PROMPT_START)) }

    /** `pwd && git status --short` printed this in S8, between its echo and the next prompt. */
    val statusOutput: String by lazy {
        val afterEcho = output.indexOf("\r\r\n") + 3
        output.substring(afterEcho, output.indexOf(PROMPT_START, afterEcho))
    }

    const val CWD = "/private/tmp/amp-scratch"
    const val EXIT_ERROR = "tmux client exited with status 1"

    fun run(command: String): String = when (command.trim()) {
        "" -> ""
        "pwd" -> "$CWD\r\n"
        "pwd && git status --short" -> statusOutput
        else -> "zsh: command not found: ${command.trim().substringBefore(' ')}\r\n"
    }
}

/**
 * The executor relay, played as the S8 shell. `amp-terminal-v1` answers `ready` to `open`, draws the recorded banner,
 * echoes typed bytes, runs a few commands (`pwd`, `echo`, `stty size`, `cat`, `amp-fake-flood`), and closes on `exit`.
 * `amp-exec-v1` answers every command with exit status 0. Every control payload lands in [World.terminalControls].
 */
internal class Terminals(private val actor: ThreadActor, private val world: World) {
    private class Channel(val socket: WebSocket, val service: String) {
        val line = StringBuilder()
        var opened = false
        var cols = 80
        var rows = 24
        var catting = false
        var flood: ScheduledFuture<*>? = null

        /** Input typed while a flood runs: echoed by the tty at once, read by the shell after the flood, like real typeahead. */
        val typeahead = StringBuilder()
    }

    private val channels = HashMap<String, Channel>()

    fun drop(socket: WebSocket) {
        channels.values.filter { it.socket == socket }.forEach { it.flood?.cancel(false) }
        channels.values.removeAll { it.socket == socket }
    }

    fun control(socket: WebSocket, method: String, params: JsonObject) {
        val channelId = params.string("channelId") ?: return
        when (method) {
            "client_executor_relay_open" -> channels[channelId] = Channel(socket, params.string("service") ?: TERMINAL)
            "client_executor_relay_close" -> channels.remove(channelId)?.flood?.cancel(false)
            "client_executor_relay_data" -> {
                val data = params.string("dataBase64")?.decodeBase64() ?: return
                if (params.bool("binary") == true) input(channelId, data) else controlPayload(channelId, data)
            }
        }
    }

    fun binary(socket: WebSocket, bytes: ByteString) {
        val frame = RelayFrame.decode(bytes)?.takeIf { it.kind == RelayFrame.TO_EXECUTOR } ?: return
        if (frame.channelId !in channels) channels[frame.channelId] = Channel(socket, TERMINAL)
        if (frame.binary) input(frame.channelId, frame.payload) else controlPayload(frame.channelId, frame.payload)
    }

    private fun controlPayload(channelId: String, payload: ByteString) {
        val channel = channels[channelId] ?: return
        world.terminalControls += payload.utf8()
        val control = runCatching { FakeJson.parseToJsonElement(payload.utf8()) as? JsonObject }.getOrNull() ?: return
        if (channel.service == EXEC) return sendControl(channelId, channel, json("type" to "exit", "status" to 0))
        when (control.string("type")) {
            "open" -> if (!channel.opened) {
                channel.opened = true
                control.long("cols")?.let { channel.cols = it.toInt() }
                control.long("rows")?.let { channel.rows = it.toInt() }
                sendControl(channelId, channel, json("type" to "ready"))
                sendOutput(channelId, channel, Shell.banner)
            }
            "resize" -> {
                control.long("cols")?.let { channel.cols = it.toInt() }
                control.long("rows")?.let { channel.rows = it.toInt() }
            }
        }
    }

    private fun input(channelId: String, bytes: ByteString) {
        val channel = channels[channelId] ?: return
        for (char in bytes.utf8()) {
            when {
                channel.flood != null -> {
                    channel.typeahead.append(char)
                    sendOutput(channelId, channel, if (char == '\r') "\r\n" else char.toString())
                }
                char == '\u0003' -> {
                    channel.line.clear()
                    channel.catting = false
                    sendOutput(channelId, channel, "^C\r\n" + Shell.prompt)
                }
                char == '\r' && channel.catting -> {
                    sendOutput(channelId, channel, "\r\n" + channel.line + "\r\n")
                    channel.line.clear()
                }
                char == '\r' -> {
                    val command = channel.line.toString().trim()
                    channel.line.clear()
                    if (command == "exit") return exit(channelId, channel)
                    run(channelId, channel, command)
                }
                char == '\u007f' -> if (channel.line.isNotEmpty()) {
                    channel.line.setLength(channel.line.length - 1)
                    sendOutput(channelId, channel, "\b \b")
                }
                else -> {
                    channel.line.append(char)
                    sendOutput(channelId, channel, char.toString())
                }
            }
        }
    }

    private fun run(channelId: String, channel: Channel, command: String) {
        val words = command.split(' ').filter { it.isNotEmpty() }
        when (words.firstOrNull()) {
            "stty" if words.getOrNull(1) == "size" -> sendOutput(channelId, channel, "\r\n${channel.rows} ${channel.cols}\r\n" + Shell.prompt)
            "echo" -> sendOutput(channelId, channel, "\r\n" + words.drop(1).joinToString(" ") + "\r\n" + Shell.prompt)
            "cat" if words.size == 1 -> {
                channel.catting = true
                sendOutput(channelId, channel, "\r\n" + PASTE_OFF)
            }
            "amp-fake-flood" -> {
                sendOutput(channelId, channel, "\r\n")
                flood(channelId, channel, total = (words.getOrNull(1)?.toLongOrNull() ?: 1L) * FLOOD_BYTES_PER_SECOND, sent = 0L, started = System.nanoTime())
            }
            else -> sendOutput(channelId, channel, "\r\n" + Shell.run(command) + Shell.prompt)
        }
    }

    /** [FLOOD_BYTES_PER_SECOND] of numbered 100-byte lines, paced by the wall clock so slow ticks catch up. */
    private fun flood(channelId: String, channel: Channel, total: Long, sent: Long, started: Long) {
        if (channels[channelId] !== channel) return
        if (sent >= total) {
            channel.flood = null
            sendOutput(channelId, channel, "flood done $sent\r\n" + Shell.prompt)
            val typed = channel.typeahead.toString().encodeUtf8()
            channel.typeahead.clear()
            return input(channelId, typed)
        }
        val due = minOf(total, sent + 1_000_000, (System.nanoTime() - started) / 1_000_000 * FLOOD_BYTES_PER_SECOND / 1000)
        val tick = StringBuilder()
        var line = sent / FLOOD_LINE
        while (sent + tick.length < due) {
            tick.append("flood ").append(line.toString().padStart(8, '0')).append(' ').append("x".repeat(FLOOD_LINE.toInt() - 17)).append("\r\n")
            line++
        }
        if (tick.isNotEmpty()) sendOutput(channelId, channel, tick.toString())
        channel.flood = world.schedule(FLOOD_TICK_MS) { flood(channelId, channel, total, sent + tick.length, started) }
    }

    private fun exit(channelId: String, channel: Channel) {
        sendOutput(channelId, channel, "\r\n")
        sendControl(channelId, channel, json("type" to "exit", "error" to Shell.EXIT_ERROR))
        actor.send(channel.socket, Event("client_executor_relay_exit", json("channelId" to channelId)).live())
        channels.remove(channelId)
    }

    private fun sendControl(channelId: String, channel: Channel, control: JsonObject) {
        actor.sendBinary(channel.socket, RelayFrame(RelayFrame.FROM_EXECUTOR, false, channelId, control.toString().encodeUtf8()).encode())
    }

    private fun sendOutput(channelId: String, channel: Channel, text: String) {
        actor.sendBinary(channel.socket, RelayFrame(RelayFrame.FROM_EXECUTOR, true, channelId, text.encodeUtf8()).encode())
    }

    private companion object {
        const val TERMINAL = "amp-terminal-v1"
        const val EXEC = "amp-exec-v1"
        const val PASTE_OFF = "\u001b[?2004l"
        const val FLOOD_BYTES_PER_SECOND = 1_000_000L
        const val FLOOD_TICK_MS = 10L
        const val FLOOD_LINE = 100L
    }
}
