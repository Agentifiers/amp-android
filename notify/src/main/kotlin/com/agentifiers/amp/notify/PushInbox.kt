package com.agentifiers.amp.notify

import android.util.Log
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.sync.api.PushEvent
import dagger.hilt.android.EntryPointAccessors

/**
 * Where every push lands, whichever channel carried it: the relay's FCM message, a UnifiedPush message, or the
 * debug `inject-push` event. The body is still encrypted; :sync decrypts and parses it.
 */
object PushInbox {
    internal const val TAG = "AmpNotify"

    /** [body] null is a relay wake message: the account's background sync runs instead. Returns the line it logged. */
    suspend fun deliver(account: AccountId, body: ByteArray?): String {
        if (body == null) {
            PollWorker.runNow(NotifyRuntime.app, account)
            return "push wake: background sync queued".also { Log.i(TAG, it) }
        }
        val event = NotifyRuntime.background(account).decodePush(body)
            ?: return "push: dropped, the body did not decrypt with this account's keys".also { Log.w(TAG, it) }
        return "push ${kind(event)} ${event.thread.raw.take(10)}: ${route(account, event)}".also { Log.i(TAG, it) }
    }

    /**
     * Local state decides what shows: nothing for a thread a window is showing, nothing for a snoozed thread unless it
     * needs approval, and the tag dedupes repeats. [local] is a finish the app detected itself rather than a push; both
     * come through here, so they follow the same rules. A finish that shows nothing is still marked in the ledger, so
     * the other path does not post it once the window closes or the snooze ends.
     */
    internal suspend fun route(account: AccountId, event: PushEvent, local: Boolean = false): String {
        val background = NotifyRuntime.background(account)
        val notifier = NotifyRuntime.notifier
        val hidden = when {
            event.thread in background.leasedThreads.value -> "suppressed, a window shows this thread"
            event !is PushEvent.ApprovalRequired && snoozed(account, event) -> "suppressed, snoozed"
            else -> null
        }
        if (hidden != null) {
            if (event is PushEvent.ThreadIdle) notifier.markSeen(account, event)
            return hidden
        }
        return when (event) {
            is PushEvent.ThreadIdle -> describe(notifier.postReady(account, event, local))
            is PushEvent.ApprovalRequired -> {
                val posting = notifier.postGatePending(account, event)
                if (posting == Posting.AlreadyPosted) return describe(posting)
                GateFetchWorker.enqueue(NotifyRuntime.app, NoticeKey(account, event.thread, event.tag, event.title))
                "${describe(posting)}, reading the gate"
            }
            is PushEvent.Other -> describe(notifier.postOther(account, event))
        }
    }

    private fun describe(posting: Posting): String = when (posting) {
        is Posting.Posted -> "posted on ${posting.channel.id}"
        Posting.AlreadyPosted -> "suppressed, another path already posted this tag"
        Posting.Filled -> "filled the reply into the notification the local check posted"
        Posting.NotificationsOff -> "suppressed, Android notifications are off for Amp"
        is Posting.ChannelOff -> "suppressed, channel ${posting.channel.id} is off"
    }

    private fun kind(event: PushEvent): String = when (event) {
        is PushEvent.ThreadIdle -> "thread_idle"
        is PushEvent.ApprovalRequired -> "approval_required"
        is PushEvent.Other -> event.kind
    }

    private suspend fun snoozed(account: AccountId, event: PushEvent): Boolean {
        val databases = EntryPointAccessors.fromApplication(NotifyRuntime.app, NotifyEntryPoint::class.java).databases()
        val snooze = databases.forAccount(account).snoozes.find(event.thread.raw) ?: return false
        val untilMs = snooze.untilMs
        return untilMs == null || untilMs > System.currentTimeMillis()
    }
}
