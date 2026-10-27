package com.agentifiers.amp.waymote

import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.media.AudioFormat as PcmFormat

/** The device's MediaCodec decoders and AudioTrack. */
public object AndroidMedia : MediaPlatform {
    private val decoderTypes: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
            .flatMap { info -> info.supportedTypes.map { it.lowercase() } }.toSet()
    }

    override fun supportsVideo(mime: String): Boolean = mime.lowercase() in decoderTypes

    override fun openAudio(format: AudioStreamFormat, onError: (String) -> Unit): AudioDecoder {
        require(format.codec.equals("opus", ignoreCase = true)) { "Unsupported audio codec ${format.codec}" }
        require(format.sampleRate > 0 && format.channels in 1..2) { "Unsupported audio format ${format.sampleRate} Hz, ${format.channels} channels" }
        return OpusDecoder(format, onError)
    }
}

/**
 * Renders onto [surface]. Call [release] from the Surface's destroyed callback, before it returns: it stops the
 * codec that draws into the Surface, and later [open] calls return null.
 */
public class SurfaceTarget(private val surface: Surface) : VideoTarget {
    private val lock = Any()
    private var live: VideoDecoder? = null
    private var released = false

    override fun open(mime: String, width: Int, height: Int, onRendered: () -> Unit, onError: (String) -> Unit): VideoDecoder? =
        synchronized(lock) {
            if (released) return null
            live?.close()
            SurfaceDecoder(mime, width, height, surface, onRendered, onError).also { live = it }
        }

    public fun release(): Unit = synchronized(lock) {
        released = true
        live?.close()
        live = null
    }
}

private class SurfaceDecoder(mime: String, width: Int, height: Int, surface: Surface, onRendered: () -> Unit, onError: (String) -> Unit) : VideoDecoder {
    private val codec = AsyncCodec(
        codec = MediaCodec.createDecoderByType(mime),
        format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 1 shl 20))
        },
        surface = surface,
        threadName = "waymote-video",
        onOutput = { codec, index, info ->
            val render = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
            codec.releaseOutputBuffer(index, render)
            if (render) onRendered()
        },
        onError = onError,
    )

    override fun decode(bytes: ByteArray, offset: Int, timestampUs: Long) = codec.queue(bytes, offset, timestampUs)

    override val backlog: Int get() = codec.backlog

    override fun close() = codec.close()
}

/** Opus through MediaCodec into a streaming AudioTrack, both on the codec's own thread. */
private class OpusDecoder(format: AudioStreamFormat, onError: (String) -> Unit) : AudioDecoder {
    private var track: AudioTrack? = null

    private val codec = AsyncCodec(
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS),
        format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, format.sampleRate, format.channels).apply {
            setByteBuffer("csd-0", opusHead(format.sampleRate, format.channels))
            setByteBuffer("csd-1", nanos(0))
            setByteBuffer("csd-2", nanos(SEEK_PREROLL_NS))
        },
        surface = null,
        threadName = "waymote-audio",
        onOutput = { codec, index, info ->
            val pcm = codec.getOutputBuffer(index)
            if (pcm != null && info.size > 0) {
                pcm.position(info.offset).limit(info.offset + info.size)
                trackFor(codec.outputFormat).write(pcm, info.size, AudioTrack.WRITE_BLOCKING)
            }
            codec.releaseOutputBuffer(index, false)
        },
        onError = onError,
        onRelease = {
            track?.release()
            track = null
        },
    )

    private fun trackFor(output: MediaFormat): AudioTrack = track ?: run {
        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val mask = if (output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) PcmFormat.CHANNEL_OUT_MONO else PcmFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(rate, mask, PcmFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(PcmFormat.Builder().setEncoding(PcmFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also {
                it.play()
                track = it
            }
    }

    override fun decode(bytes: ByteArray, offset: Int, timestampUs: Long) = codec.queue(bytes, offset, timestampUs)

    override fun reset() = codec.flush {
        track?.pause()
        track?.flush()
        track?.play()
    }

    override fun close() = codec.close()

    private companion object {
        const val SEEK_PREROLL_NS = 80_000_000L

        /** RFC 7845 identification header: no pre-skip, unity gain, channel mapping family 0. */
        fun opusHead(sampleRate: Int, channels: Int): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(channels.toByte())
            putShort(0)
            putInt(sampleRate)
            putShort(0)
            put(0)
            flip()
        }

        fun nanos(value: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).flip() as ByteBuffer
    }
}

/**
 * A MediaCodec in async mode. Every codec call runs on one HandlerThread, so callbacks, queued packets, flushes and
 * release never race; [close] waits for the release so a Surface can be destroyed right after.
 */
private class AsyncCodec(
    private val codec: MediaCodec,
    format: MediaFormat,
    surface: Surface?,
    threadName: String,
    private val onOutput: (MediaCodec, Int, MediaCodec.BufferInfo) -> Unit,
    private val onError: (String) -> Unit,
    private val onRelease: () -> Unit = {},
) {
    private class Packet(val bytes: ByteArray, val offset: Int, val timestampUs: Long)

    private val thread = HandlerThread(threadName).apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = ArrayDeque<Packet>()
    private val inputs = ArrayDeque<Int>()
    private val queued = AtomicInteger()
    private var released = false

    val backlog: Int get() = queued.get()

    init {
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (released) return
                    inputs.addLast(index)
                    pump()
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (released) return
                    guarded { onOutput(codec, index, info) }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = onError(e.diagnosticInfo)

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            },
            handler,
        )
        try {
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (e: RuntimeException) {
            codec.release()
            thread.quitSafely()
            throw e
        }
    }

    fun queue(bytes: ByteArray, offset: Int, timestampUs: Long) {
        queued.incrementAndGet()
        handler.post {
            if (released) return@post
            pending.addLast(Packet(bytes, offset, timestampUs))
            pump()
        }
    }

    fun flush(then: () -> Unit) {
        handler.post {
            if (released) return@post
            queued.addAndGet(-pending.size)
            pending.clear()
            inputs.clear()
            guarded {
                codec.flush()
                then()
                codec.start()
            }
        }
    }

    fun close() {
        val done = CountDownLatch(1)
        val posted = handler.post {
            if (!released) {
                released = true
                runCatching { codec.stop() }
                codec.release()
                onRelease()
            }
            thread.quitSafely()
            done.countDown()
        }
        if (posted) done.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun pump() = guarded {
        while (pending.isNotEmpty() && inputs.isNotEmpty()) {
            val index = inputs.removeFirst()
            val packet = pending.removeFirst()
            queued.decrementAndGet()
            val buffer = codec.getInputBuffer(index) ?: continue
            val size = packet.bytes.size - packet.offset
            buffer.clear()
            buffer.put(packet.bytes, packet.offset, size)
            codec.queueInputBuffer(index, 0, size, packet.timestampUs, 0)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            onError(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 1_000L
    }
}
