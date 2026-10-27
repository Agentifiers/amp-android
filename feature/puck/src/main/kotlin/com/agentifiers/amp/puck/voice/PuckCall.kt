package com.agentifiers.amp.puck.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.sync.api.PuckVoiceChannel
import com.agentifiers.amp.sync.api.PuckVoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One Puck call: a self-managed Telecom audio call around a [PuckVoiceChannel], with the microphone going up and
 * Puck's voice coming down, both PCM16 mono at 24 kHz. [run] lasts as long as the call and is hosted by
 * [PuckCallService], which keeps the process in the foreground.
 */
internal class PuckCall(
    private val context: Context,
    val conversation: PuckConversationId,
    private val channel: PuckVoiceChannel,
) {
    private val muted = MutableStateFlow(false)
    private val endpoint = MutableStateFlow<CallEndpointCompat?>(null)
    private val endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())

    /** Why the phone side failed (Telecom, microphone, speaker); shown in place of the voice state's reason. */
    private val failure = MutableStateFlow<String?>(null)
    private val routeRequests = Channel<Unit>(Channel.CONFLATED)
    private val finished = CompletableDeferred<Unit>()

    @Volatile private var hungUp = false

    fun snapshot(): PuckCallSnapshot = PuckCallSnapshot(
        conversation = conversation,
        voice = failure.value?.let { PuckVoiceState.Ended(it) } ?: channel.state.value.let {
            when {
                it != PuckVoiceState.Idle -> it
                hungUp -> PuckVoiceState.Ended(null)
                else -> PuckVoiceState.Connecting
            }
        },
        muted = muted.value,
        route = endpoint.value?.name?.toString(),
        canSwitchRoute = endpoints.value.size > 1,
    )

    fun hangUp() {
        hungUp = true
        channel.stop()
    }

    fun toggleMute() = muted.update { !it }

    fun switchRoute() {
        routeRequests.trySend(Unit)
    }

    /** Ends the call that never got as far as Telecom. */
    fun fail(reason: String) {
        failure.value = reason
        channel.stop()
    }

    suspend fun run(publish: (PuckCallSnapshot) -> Unit) = coroutineScope {
        val publishing = launch {
            merge(channel.state.map {}, muted.map {}, endpoint.map {}, endpoints.map {}, failure.map {}).collect { publish(snapshot()) }
        }
        try {
            val calls = CallsManager(context)
            calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            calls.addCall(
                CallAttributesCompat(PUCK, Uri.fromParts(SCHEME, conversation.thread.raw, null), CallAttributesCompat.DIRECTION_OUTGOING),
                onAnswer = {},
                onDisconnect = { hangUp() },
                onSetActive = {},
                onSetInactive = {},
            ) {
                launch { inCall(this@addCall) }
            }
            finished.await()
        } catch (e: CancellationException) {
            channel.stop()
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Android could not place the call.")
        } finally {
            publishing.cancel()
            publish(snapshot())
        }
    }

    private suspend fun inCall(call: CallControlScope) {
        try {
            coroutineScope {
                launch { call.isMuted.collect { muted.value = it } }
                launch { call.currentCallEndpoint.collect { endpoint.value = it } }
                launch { call.availableEndpoints.collect { endpoints.value = it } }
                launch { routeRequests.receiveAsFlow().collect { nextRoute(endpoints.value, endpoint.value)?.let { call.requestEndpointChange(it) } } }
                launch(Dispatchers.IO) { speak() }
                launch(Dispatchers.IO) { listen() }
                launch {
                    channel.state.first { it is PuckVoiceState.Live || it is PuckVoiceState.Thinking }
                    call.setActive()
                }
                if (!hungUp && failure.value == null) channel.start()
                if (hungUp || failure.value != null) channel.stop()
                val ended = if (channel.state.value == PuckVoiceState.Idle) PuckVoiceState.Ended(null) else channel.state.filterIsInstance<PuckVoiceState.Ended>().first()
                val cause = when {
                    ended.error != null || failure.value != null -> DisconnectCause.ERROR
                    hungUp -> DisconnectCause.LOCAL
                    else -> DisconnectCause.REMOTE
                }
                call.disconnect(DisconnectCause(cause))
                coroutineContext.cancelChildren()
            }
        } finally {
            finished.complete(Unit)
        }
    }

    /** Microphone to [PuckVoiceChannel.uplink] in 20 ms frames; muted frames are zeros. */
    private suspend fun listen() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return fail(MIC_OFF)
        val frame = SAMPLE_RATE * 2 * FRAME_MS / 1000
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 4),
            )
        } catch (_: SecurityException) {
            return fail(MIC_OFF)
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return fail(MIC_GONE)
            record.startRecording()
            val buffer = ByteArray(frame)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, frame)
                if (read < 0) return fail(MIC_GONE)
                if (read > 0) channel.uplink(uplinkFrame(buffer, read, muted.value))
            }
        } catch (_: IllegalStateException) {
            fail(MIC_GONE)
        } finally {
            record.release()
        }
    }

    /** [PuckVoiceChannel.downlink] to a voice-call AudioTrack, which Telecom routes to the current endpoint. */
    private suspend fun speak() {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE * 2 / 5))
                .build()
        } catch (_: UnsupportedOperationException) {
            return fail(SPEAKER_GONE)
        }
        try {
            track.play()
            channel.downlink.collect { pcm -> track.write(pcm, 0, pcm.size) }
        } catch (_: IllegalStateException) {
            fail(SPEAKER_GONE)
        } finally {
            track.release()
        }
    }

    private companion object {
        const val PUCK = "Puck"
        const val SCHEME = "amp-puck"
        const val SAMPLE_RATE = 24_000
        const val FRAME_MS = 20
        const val MIC_OFF = "Amp needs the microphone to call Puck."
        const val MIC_GONE = "The microphone is not available."
        const val SPEAKER_GONE = "The speaker is not available."
    }
}
