package com.agentifiers.amp.sync.internal.puck.voice

import com.agentifiers.amp.sync.internal.wire.InboundFrame

/** What a thread engine hands Puck voice. Both run on the engine's confined thread, in frame order. */
internal interface VoiceTap {
    /** True when the event is voice's, so the thread fold never sees it. */
    fun onEvent(event: InboundFrame.Event): Boolean

    /** The socket that carried the call is gone; reconnecting does not resume a call. */
    fun onSocketEnded()
}
