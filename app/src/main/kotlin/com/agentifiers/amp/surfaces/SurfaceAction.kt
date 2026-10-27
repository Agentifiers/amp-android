package com.agentifiers.amp.surfaces

import android.content.Context
import android.content.Intent
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadId

/**
 * What a launcher shortcut, a widget tap, or the Quick Settings tile asks the app to do. Each one is an
 * explicit intent to [SurfaceActivity], parsed back here, so the launcher never holds a free-form route.
 * `app/src/surfaces/shortcuts.xml` repeats the action strings of the static shortcuts.
 */
sealed interface SurfaceAction {
    val intentAction: String

    /** Opens a pane in the app's window. */
    sealed interface Navigate : SurfaceAction {
        val destination: PaneDestination
    }

    data object NewThread : Navigate {
        override val intentAction = "com.agentifiers.amp.surface.NEW_THREAD"
        override val destination = PaneDestination.NewThread(prefill = null)
    }

    data object OpenFleet : Navigate {
        override val intentAction = "com.agentifiers.amp.surface.OPEN_FLEET"
        override val destination = PaneDestination.Sidebar
    }

    data class OpenThread(val thread: ThreadId) : Navigate {
        override val intentAction = ACTION_OPEN_THREAD
        override val destination = PaneDestination.Thread(thread)
    }

    /** Starts a voice call with Puck in the latest conversation; the call itself is the system call UI. */
    data object CallPuck : SurfaceAction {
        override val intentAction = "com.agentifiers.amp.surface.CALL_PUCK"
    }

    fun intent(context: Context): Intent = Intent(intentAction).setClass(context, SurfaceActivity::class.java).apply {
        if (this@SurfaceAction is OpenThread) putExtra(EXTRA_THREAD, thread.raw)
    }

    companion object {
        const val ACTION_OPEN_THREAD = "com.agentifiers.amp.surface.OPEN_THREAD"
        const val EXTRA_THREAD = "thread"

        /** Null for an intent no surface of this app builds. */
        fun parse(intent: Intent): SurfaceAction? = when (intent.action) {
            NewThread.intentAction -> NewThread
            OpenFleet.intentAction -> OpenFleet
            CallPuck.intentAction -> CallPuck
            ACTION_OPEN_THREAD -> intent.getStringExtra(EXTRA_THREAD)?.let(ThreadId::parse)?.let(::OpenThread)
            else -> null
        }
    }
}
