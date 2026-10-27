package com.agentifiers.amp.design

import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.StepKey
import com.agentifiers.amp.model.ThreadId

/** The Puck pane and the share-to-Puck screen. */
object PuckTags {
    const val switcher = "puck-switcher"
    const val switcherInactive = "puck-switcher-inactive"
    fun conversation(id: ThreadId) = "puck-conversation-${id.raw}"
    const val newChat = "puck-new-chat"
    const val newChatFailure = "puck-new-chat-failure"
    const val menu = "puck-menu"
    const val archive = "puck-archive"
    const val context = "puck-context"
    const val call = "puck-call"
    const val media = "puck-media"
    const val shareSend = "share-to-puck-send"

    const val empty = "puck-empty"
    const val link = "puck-link"
    const val transcript = "puck-transcript"
    fun message(id: MessageId) = "puck-message-${id.raw}"
    fun step(key: StepKey) = "puck-step-${key.raw}"
    fun pending(id: MessageId) = "puck-pending-${id.raw}"
    const val input = "puck-input"
    const val send = "puck-send"
    const val voiceStart = "puck-voice-start"
    const val voiceStatus = "puck-voice-status"
    const val voiceMute = "puck-voice-mute"
    const val voiceEnd = "puck-voice-end"
    const val voiceRoute = "puck-voice-route"
    const val voiceMicDenied = "puck-voice-mic-denied"
}
