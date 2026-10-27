package com.agentifiers.amp.puck.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.agentifiers.amp.model.PuckConversationId
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.PuckVoiceChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public sealed interface CallPuckResult {
    public data class Placed(val conversation: PuckConversationId) : CallPuckResult

    /** Another Puck call is on; [conversation] is its conversation. */
    public data class Busy(val conversation: PuckConversationId) : CallPuckResult

    /** RECORD_AUDIO is not granted. Open the Puck pane, whose call button asks for it. */
    public data object NeedsMicrophone : CallPuckResult

    public data class Failed(val reason: String) : CallPuckResult
}

/**
 * Calls Puck in the latest conversation, for the Quick Settings tile and the launcher shortcut. Call it while one of
 * the app's activities is in the foreground: Android lets a call's foreground service use the microphone only then.
 */
public suspend fun callPuck(context: Context, scope: AccountScope): CallPuckResult {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return CallPuckResult.NeedsMicrophone
    PuckCalls.current?.let { return CallPuckResult.Busy(it.conversation) }
    val conversation = when (val opened = scope.puck.open(startFresh = false)) {
        is Outcome.Ok -> opened.value
        is Outcome.Failed -> return CallPuckResult.Failed(opened.error.explanation())
        is Outcome.Refused -> return CallPuckResult.Failed(opened.why)
        Outcome.Unknown -> return CallPuckResult.Failed("Amp did not answer. Try again.")
    }
    return PuckCalls.place(context, conversation, scope.puck.voice(conversation))
}

/** True while this process runs a Puck call; the Quick Settings tile shows it as active. */
public val puckCallActive: Boolean get() = PuckCalls.current != null

/** The one Puck call this process runs, and the last call's snapshot, so the controls can say how it ended. */
internal object PuckCalls {
    private val mutableSnapshot = MutableStateFlow<PuckCallSnapshot?>(null)
    val snapshot: StateFlow<PuckCallSnapshot?> = mutableSnapshot.asStateFlow()

    @Volatile var current: PuckCall? = null
        private set

    @Synchronized
    fun place(context: Context, conversation: PuckConversationId, channel: PuckVoiceChannel): CallPuckResult {
        current?.let { return CallPuckResult.Busy(it.conversation) }
        val call = PuckCall(context.applicationContext, conversation, channel)
        current = call
        mutableSnapshot.value = call.snapshot()
        return try {
            context.startForegroundService(Intent(context, PuckCallService::class.java))
            CallPuckResult.Placed(conversation)
        } catch (_: IllegalStateException) {
            abandon(call, BACKGROUND_REFUSED)
            CallPuckResult.Failed(BACKGROUND_REFUSED)
        }
    }

    fun publish(call: PuckCall, snapshot: PuckCallSnapshot) {
        if (current === call) mutableSnapshot.value = snapshot
    }

    /** Ends a call that never reached Telecom. */
    fun abandon(call: PuckCall, reason: String) {
        call.fail(reason)
        publish(call, call.snapshot())
        finished(call)
    }

    @Synchronized
    fun finished(call: PuckCall) {
        if (current === call) current = null
    }

    fun hangUp() {
        current?.hangUp()
    }

    fun toggleMute() {
        current?.toggleMute()
    }

    fun switchRoute() {
        current?.switchRoute()
    }

    const val BACKGROUND_REFUSED = "Android did not let Amp start a call from the background. Open Amp and try again."
}

private fun EngineError.explanation(): String = when (this) {
    EngineError.Offline -> "You're offline."
    EngineError.Unauthorized -> "Sign in to Amp again."
    EngineError.StepUpRequired -> "This account needs a passkey to act from the app."
    EngineError.PaymentRequired -> "You're out of Amp credits."
    is EngineError.NotFound -> "Amp could not find $what."
    is EngineError.Server -> message
    is EngineError.Drift -> "Amp answered in a way this app does not understand."
    else -> "Puck is not available in this build."
}
