package com.agentifiers.amp.waymote

import com.agentifiers.amp.sync.api.DesktopStream
import com.agentifiers.amp.sync.api.RelayChannel
import com.agentifiers.amp.sync.api.RelayChannelFactory
import com.agentifiers.amp.sync.api.RelayPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One native Desktop session over `amp-desktop-v1`. [channels] hands out the three streams already past the relay
 * handshake, so every payload here is a Waymote frame. Commands may be called from any thread, before or during
 * [run]; they are applied in order on the session loop. A session runs once; reconnecting is a new session.
 */
public class WaymoteSession(
    private val channels: RelayChannelFactory,
    private val media: MediaPlatform,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Connecting)
    public val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val events = Channel<Event>(Channel.UNLIMITED)

    /** Where video renders; null while the pane has no Surface. */
    public fun attach(target: VideoTarget?): Unit = post(Event.Attach(target))

    public fun takeOver(): Unit = post(Event.TakeOver)

    public fun handBack(): Unit = post(Event.HandBack)

    public fun selectDisplay(id: String): Unit = post(Event.SelectDisplay(id))

    public fun writeClipboard(text: String): Unit = post(Event.WriteClipboard(text))

    /** Commits [text] as typed; each newline is an Enter key press. */
    public fun type(text: String): Unit = post(Event.Type(text))

    /** The IME's in-progress composition. */
    public fun preedit(text: String): Unit = post(Event.Preedit(text))

    /** Moves the pointer to a point of the presented video, each axis 0..1. Points outside are dropped. */
    public fun pointAt(x: Float, y: Float) {
        if (x !in 0f..1f || y !in 0f..1f) return
        post(Event.Input(InputEvent.PointerAbsolute((x * POINTER_SPAN).roundToInt(), (y * POINTER_SPAN).roundToInt())))
    }

    public fun button(button: MouseButton, down: Boolean): Unit = post(Event.Input(InputEvent.Button(button, down)))

    public fun click(button: MouseButton) {
        button(button, down = true)
        button(button, down = false)
    }

    /** Moves the pointer by remote pixels. */
    public fun move(dx: Float, dy: Float): Unit = post(Event.Input(InputEvent.PointerRelative(dx, dy)))

    /** Scrolls by remote pixels; positive [dy] scrolls down. */
    public fun wheel(dx: Float, dy: Float): Unit = post(Event.Input(InputEvent.Wheel(dx, dy)))

    /** Whether [androidKeyCode] sends a key struct; Back, volume and media keys stay on the device. */
    public fun handlesKey(androidKeyCode: Int): Boolean = Keyboard.evdev(androidKeyCode) != null

    /** An Android key event: its key code, action, `getUnicodeChar()` and meta state. */
    public fun key(androidKeyCode: Int, action: KeyAction, unicodeChar: Int, metaState: Int): Unit =
        post(Event.Key(androidKeyCode, action, unicodeChar, metaState))

    /** The pane's size in device pixels. Sent as a resize while this viewer has control. */
    public fun resize(widthPx: Int, heightPx: Int, density: Float) {
        RemoteSize.fit(widthPx, heightPx, density)?.let { post(Event.Resize(it)) }
    }

    private fun post(event: Event) {
        events.trySend(event)
    }

    /** Opens the three streams and plays them until the session ends or the caller is cancelled. */
    public suspend fun run(): Unit = coroutineScope {
        val opened = ArrayList<RelayChannel>(3)
        try {
            val video = channels.open(DesktopStream.Video).also(opened::add)
            val audio = channels.open(DesktopStream.Audio).also(opened::add)
            val control = channels.open(DesktopStream.Control).also(opened::add)
            mapOf(DesktopStream.Video to video, DesktopStream.Audio to audio, DesktopStream.Control to control).forEach { (stream, channel) ->
                forward(stream, channel)
            }
            launch {
                while (true) {
                    delay(TICK_MS)
                    events.send(Event.Tick)
                }
            }
            Loop(video, audio, control).play()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = SessionState.Ended(EndReason.Failed(e.message ?: "Could not open the desktop"))
        } finally {
            opened.forEach { it.close() }
            coroutineContext.cancelChildren()
        }
    }

    private fun CoroutineScope.forward(stream: DesktopStream, channel: RelayChannel) = launch {
        val end = try {
            channel.incoming.collect { events.send(Event.Payload(stream, it)) }
            Event.Closed(stream, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Event.Closed(stream, e)
        }
        events.send(end)
    }

    /** The session loop: the only reader of [events] and the only writer of [state] once streaming. */
    private inner class Loop(private val video: RelayChannel, private val audio: RelayChannel, private val control: RelayChannel) {
        private val input = InputEncoder()
        private var view = SessionState.Streaming(
            displays = Displays(emptyList(), selected = null, switching = false, error = null),
            control = Control.Watching,
            video = Video.Waiting,
            audio = Audio.Pending,
            resolution = null,
            quality = null,
            remoteClipboard = null,
        )
        private var ended: EndReason? = null

        private var serverControl = ServerControl.Ready
        private var wantsControl = false
        private var resolvedKeysyms = false
        private var pendingSelect: String? = null
        /** The display-state generation whose first frame on screen the server waits to hear about. */
        private var awaitingPresent: Long? = null
        private var presentedGeneration = 0L

        private var desiredSize: RemoteSize? = null
        private var requestedSize: RemoteSize? = null

        private var mime: String? = null
        private var target: VideoTarget? = null
        private var decoder: VideoDecoder? = null
        private var decoderGeneration = -1L
        private var awaitingKeyframe = true
        private var audioDecoder: AudioDecoder? = null

        private var received = 0
        private var dropped = 0
        private var rendered = 0
        private var pingId = 0L
        private val pings = LinkedHashMap<Long, Long>()
        private var rttMs = 0

        suspend fun play() {
            mutableState.value = view
            try {
                for (event in events) {
                    handle(event)
                    val reason = ended
                    if (reason != null) {
                        mutableState.value = SessionState.Ended(reason)
                        return
                    }
                    mutableState.value = view
                }
            } finally {
                decoder?.close()
                audioDecoder?.close()
            }
        }

        private fun handle(event: Event) {
            when (event) {
                is Event.Payload -> receive(event.stream, event.payload)
                is Event.Closed -> closed(event.stream, event.error)
                Event.Tick -> tick()
                is Event.Rendered -> rendered(event)
                is Event.DecoderFailed -> ended = EndReason.Failed("Video decoder error: ${event.message}")
                is Event.AudioFailed -> {
                    audioDecoder?.close()
                    audioDecoder = null
                    view = view.copy(audio = Audio.Failed("Audio decoder error: ${event.message}"))
                }
                is Event.Attach -> if (event.target !== target) {
                    target = event.target
                    restartVideo()
                }
                Event.TakeOver -> acquire()
                Event.HandBack -> release()
                is Event.SelectDisplay -> select(event.id)
                is Event.WriteClipboard -> if (inputAllowed() && event.text.toByteArray().size <= CLIPBOARD_LIMIT) {
                    sendJson(ControlOut.ClipboardWrite(event.text))
                }
                is Event.Type -> if (inputAllowed()) type(event.text)
                is Event.Preedit -> if (inputAllowed()) sendJson(ControlOut.Text(TextAction.Preedit, event.text, input.nextSequence()))
                is Event.Input -> if (inputAllowed()) sendInput(event.input)
                is Event.Key -> if (inputAllowed()) {
                    val code = Keyboard.evdev(event.androidKeyCode) ?: return
                    val keysym = if (resolvedKeysyms) Keyboard.keysym(event.unicodeChar, event.metaState) else 0
                    sendInput(InputEvent.Key(code, event.action, keysym))
                }
                is Event.Resize -> {
                    desiredSize = event.size
                    requestResize()
                }
            }
        }

        private fun receive(stream: DesktopStream, payload: RelayPayload) {
            when (payload) {
                is RelayPayload.Binary -> when (stream) {
                    DesktopStream.Video -> VideoFrame.parse(payload.bytes)?.let(::videoFrame)
                    DesktopStream.Audio -> AudioFrame.parse(payload.bytes)?.let(::audioFrame)
                    DesktopStream.Control -> Unit
                }
                is RelayPayload.Text -> {
                    val message = ControlIn.parse(payload.json)
                    when (stream) {
                        DesktopStream.Video -> if (message is ControlIn.VideoConfig) videoConfig(message.codec)
                        DesktopStream.Audio -> audioConfig(message)
                        DesktopStream.Control -> message?.let(::controlMessage)
                    }
                }
            }
        }

        private fun closed(stream: DesktopStream, error: Throwable?) {
            if (stream == DesktopStream.Audio) {
                audioDecoder?.close()
                audioDecoder = null
                view = view.copy(audio = Audio.Failed(error?.message ?: "Audio disconnected"))
                return
            }
            ended = error?.message?.let(EndReason::Failed) ?: EndReason.Closed(stream)
        }

        // video

        private fun videoConfig(codec: String) {
            val supported = videoMime(codec)?.takeIf(media::supportsVideo)
            if (supported == null) {
                ended = EndReason.UnsupportedCodec(codec)
                return
            }
            mime = supported
            restartVideo()
        }

        private fun videoFrame(frame: VideoFrame) {
            received++
            val mime = mime ?: return
            if (frame.generation != decoderGeneration || frame.flush) {
                decoderGeneration = frame.generation
                restartVideo()
            }
            decoder?.backlog?.takeIf { it > MAX_BACKLOG }?.let { backlog ->
                dropped += backlog
                restartVideo()
            }
            if (awaitingKeyframe && !frame.keyframe) {
                dropped++
                return
            }
            val current = decoder ?: openDecoder(mime, frame) ?: run {
                dropped++
                return
            }
            awaitingKeyframe = false
            current.decode(frame.bytes, VideoFrame.PAYLOAD, frame.timestampUs)
        }

        private fun openDecoder(mime: String, frame: VideoFrame): VideoDecoder? {
            val target = target ?: return null
            val generation = frame.generation
            val width = frame.width
            val height = frame.height
            return try {
                target.open(
                    mime, width, height,
                    onRendered = { events.trySend(Event.Rendered(generation, width, height)) },
                    onError = { events.trySend(Event.DecoderFailed(it)) },
                )
            } catch (e: Exception) {
                ended = EndReason.Failed("Video decoder error: ${e.message}")
                null
            }?.also { decoder = it }
        }

        private fun restartVideo() {
            decoder?.close()
            decoder = null
            awaitingKeyframe = true
        }

        private fun rendered(event: Event.Rendered) {
            rendered++
            presentedGeneration = event.generation
            view = view.copy(video = Video.Showing(event.width, event.height))
            reportPresented()
        }

        private fun reportPresented() {
            val awaiting = awaitingPresent ?: return
            if (presentedGeneration < awaiting) return
            sendJson(ControlOut.DisplayPresented(presentedGeneration))
            awaitingPresent = null
            view = view.copy(displays = view.displays.copy(switching = false))
        }

        // audio

        private fun audioConfig(message: ControlIn?) {
            audioDecoder?.close()
            audioDecoder = null
            if (message !is ControlIn.AudioConfig || message.version != WIRE_VERSION) {
                view = view.copy(audio = Audio.Failed("Invalid audio configuration"))
                return
            }
            if (!message.enabled) {
                view = view.copy(audio = Audio.Off)
                return
            }
            view = try {
                audioDecoder = media.openAudio(AudioStreamFormat(message.codec, message.sampleRate, message.channels)) {
                    events.trySend(Event.AudioFailed(it))
                }
                view.copy(audio = Audio.Playing)
            } catch (e: Exception) {
                view.copy(audio = Audio.Failed(e.message ?: "Audio decoder error"))
            }
        }

        private fun audioFrame(frame: AudioFrame) {
            val decoder = audioDecoder ?: return
            if (frame.reset) decoder.reset()
            decoder.decode(frame.bytes, AudioFrame.PAYLOAD, frame.timestampUs)
        }

        // control

        private fun controlMessage(message: ControlIn) {
            when (message) {
                is ControlIn.ControlState -> controlState(message)
                is ControlIn.DisplayState -> if (message.valid) displayState(message)
                is ControlIn.Pong -> pings.remove(message.id)?.let { sent -> rttMs = ((nanoTime() - sent) / 1_000_000).toInt() }
                is ControlIn.ResizeApplied -> view = view.copy(resolution = RemoteSize(message.width, message.height, message.scale))
                is ControlIn.Clipboard -> view = view.copy(remoteClipboard = message.text)
                is ControlIn.Quality -> view = view.copy(
                    quality = Quality(message.bitrate.roundToInt(), message.scale.roundToInt(), message.fps.roundToInt()),
                )
                is ControlIn.VideoConfig, is ControlIn.AudioConfig -> Unit
            }
        }

        private fun controlState(message: ControlIn.ControlState) {
            resolvedKeysyms = message.resolvedKeysyms
            val wasActive = serverControl == ServerControl.Active
            serverControl = message.state
            when (message.state) {
                ServerControl.Active -> if (wantsControl) {
                    if (!wasActive) {
                        requestedSize = null
                        requestResize()
                    }
                    sendPendingSelect()
                } else {
                    sendText(RELEASE)
                    serverControl = ServerControl.Ready
                }
                ServerControl.Busy -> if (wantsControl) {
                    val dropSelect = pendingSelect != null
                    pendingSelect = null
                    view = view.copy(displays = view.displays.copy(switching = view.displays.switching && !dropSelect, error = BUSY))
                }
                ServerControl.Ready -> if (wantsControl) sendText(ACQUIRE)
            }
            publishControl()
        }

        private fun displayState(message: ControlIn.DisplayState) {
            sendInput(InputEvent.Reset)
            pendingSelect = null
            awaitingPresent = message.generation.takeIf { !message.switching && it > 0 }
            view = view.copy(
                displays = Displays(
                    list = message.displays.map { RemoteDisplay(it.id, it.name, it.width, it.height, it.isMain) },
                    selected = message.selectedID,
                    switching = message.switching || awaitingPresent != null,
                    error = message.error,
                ),
            )
            reportPresented()
        }

        private fun acquire() {
            wantsControl = true
            sendText(ACQUIRE)
            publishControl()
        }

        private fun release() {
            wantsControl = false
            pendingSelect = null
            sendInput(InputEvent.Reset)
            sendText(RELEASE)
            serverControl = ServerControl.Ready
            publishControl()
        }

        private fun select(id: String) {
            val displays = view.displays
            if (displays.switching || displays.list.none { it.id == id } || id == displays.selected) return
            pendingSelect = id
            awaitingPresent = null
            view = view.copy(displays = displays.copy(switching = true, error = null))
            if (isActive()) sendPendingSelect() else acquire()
        }

        private fun sendPendingSelect() {
            val id = pendingSelect ?: return
            if (!isActive()) return
            sendInput(InputEvent.Reset)
            sendJson(ControlOut.DisplaySelect(id))
            pendingSelect = null
        }

        private fun requestResize() {
            val size = desiredSize ?: return
            if (!isActive()) return
            val last = requestedSize
            if (last != null && last.scale120 == size.scale120 && abs(last.width - size.width) <= RESIZE_SLACK && abs(last.height - size.height) <= RESIZE_SLACK) return
            sendInput(InputEvent.Resize(size.width, size.height, size.scale120))
            requestedSize = size
        }

        private fun type(text: String) {
            text.split('\n').forEachIndexed { index, line ->
                if (index > 0) {
                    sendInput(InputEvent.Key(Keyboard.ENTER, KeyAction.Down, 0))
                    sendInput(InputEvent.Key(Keyboard.ENTER, KeyAction.Up, 0))
                }
                if (line.isNotEmpty()) sendJson(ControlOut.Text(TextAction.Commit, line, input.nextSequence()))
            }
        }

        private fun tick() {
            val now = nanoTime()
            pings.entries.removeAll { now - it.value > PING_EXPIRY_NS }
            pings[++pingId] = now
            sendJson(ControlOut.Ping(pingId))
            sendJson(ControlOut.Feedback(queue = decoder?.backlog ?: 0, dropped = dropped, fps = rendered, rtt = rttMs, active = received > 0))
            dropped = 0
            rendered = 0
            received = 0
        }

        private fun isActive() = wantsControl && serverControl == ServerControl.Active

        private fun inputAllowed() = isActive() && !view.displays.switching

        private fun publishControl() {
            val control = when {
                isActive() -> Control.Active
                serverControl == ServerControl.Busy -> Control.Busy
                wantsControl -> Control.Requesting
                else -> Control.Watching
            }
            view = view.copy(control = control)
        }

        private fun sendInput(event: InputEvent) = control.send(RelayPayload.Binary(input.encode(event)))

        private fun sendJson(message: ControlOut) = control.send(RelayPayload.Text(message.encode()))

        private fun sendText(text: String) = control.send(RelayPayload.Text(text))
    }

    private sealed interface Event {
        class Payload(val stream: DesktopStream, val payload: RelayPayload) : Event
        class Closed(val stream: DesktopStream, val error: Throwable?) : Event
        data object Tick : Event
        class Rendered(val generation: Long, val width: Int, val height: Int) : Event
        class DecoderFailed(val message: String) : Event
        class AudioFailed(val message: String) : Event
        class Attach(val target: VideoTarget?) : Event
        data object TakeOver : Event
        data object HandBack : Event
        class SelectDisplay(val id: String) : Event
        class WriteClipboard(val text: String) : Event
        class Type(val text: String) : Event
        class Preedit(val text: String) : Event
        class Input(val input: InputEvent) : Event
        class Key(val androidKeyCode: Int, val action: KeyAction, val unicodeChar: Int, val metaState: Int) : Event
        class Resize(val size: RemoteSize) : Event
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val POINTER_SPAN = 65535
        const val MAX_BACKLOG = 6
        const val RESIZE_SLACK = 32
        const val CLIPBOARD_LIMIT = 1 shl 20
        const val PING_EXPIRY_NS = 10_000_000_000L
        const val BUSY = "Another viewer is controlling the desktop."
    }
}
