package com.agentifiers.amp.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agentifiers.amp.store.LedgerState
import kotlinx.coroutines.launch

/**
 * Notification buttons. Each tap first replaces the notification with what is being sent, which also ends the
 * RemoteInput spinner and removes the buttons so nothing is answered twice, then hands the work to [ActionWorker].
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = NoticeKey.from(intent) ?: return
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationActions.REPLY_TEXT)?.toString()?.trim()
        val (work, channel, pending) = when (intent.action) {
            NotificationActions.REPLY -> {
                if (typed.isNullOrEmpty()) return
                Triple(arrayOf(ActionWorker.KIND to ActionWorker.REPLY, ActionWorker.TEXT to typed), AmpChannel.Finished, "Sending: $typed")
            }
            NotificationActions.ANSWER -> {
                val token = intent.getStringExtra(NotificationActions.TOKEN) ?: return
                val resolved = intent.getStringExtra(NotificationActions.RESOLVED).orEmpty()
                val summary = intent.getStringExtra(NotificationActions.SUMMARY).orEmpty()
                Triple(
                    arrayOf(ActionWorker.KIND to ActionWorker.ANSWER, ActionWorker.TOKEN to token, ActionWorker.RESOLVED to resolved, ActionWorker.SUMMARY to summary),
                    AmpChannel.NeedsYou,
                    "Sending ${resolved.lowercase()}…",
                )
            }
            NotificationActions.ANSWER_TEXT -> {
                val gate = intent.getStringExtra(NotificationActions.GATE) ?: return
                if (typed.isNullOrEmpty()) return
                Triple(
                    arrayOf(ActionWorker.KIND to ActionWorker.ANSWER_TEXT, ActionWorker.GATE to gate, ActionWorker.TEXT to typed, ActionWorker.RESOLVED to "Answered"),
                    AmpChannel.NeedsYou,
                    "Sending: $typed",
                )
            }
            else -> return
        }
        val done = goAsync()
        NotifyRuntime.scope.launch {
            try {
                NotifyRuntime.notifier.resolve(key, channel, pending, LedgerState.Posted)
                ActionWorker.enqueue(context.applicationContext, key, *work)
            } finally {
                done.finish()
            }
        }
    }
}
