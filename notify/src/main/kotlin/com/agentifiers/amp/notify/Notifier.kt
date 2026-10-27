package com.agentifiers.amp.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.store.AccountDatabases
import com.agentifiers.amp.store.LedgerRow
import com.agentifiers.amp.store.LedgerState
import com.agentifiers.amp.sync.api.GateTokens
import com.agentifiers.amp.sync.api.PushEvent

/** The four channels from `notifications.md`, one row each. */
internal enum class AmpChannel(val id: String, val title: String, val importance: Int) {
    NeedsYou("needs_you", "Needs you", NotificationManager.IMPORTANCE_HIGH),
    Finished("finished", "Finished", NotificationManager.IMPORTANCE_DEFAULT),
    Watching("watching", "Watching", NotificationManager.IMPORTANCE_LOW),
    Other("other", "Other", NotificationManager.IMPORTANCE_DEFAULT),
}

/** What one notification is about. Every action PendingIntent carries it, so a receiver can update the right one. */
internal data class NoticeKey(val account: AccountId, val thread: ThreadId, val tag: String, val title: String) {
    fun into(intent: Intent): Intent = intent
        .putExtra(EXTRA_ACCOUNT, account.raw)
        .putExtra(EXTRA_THREAD, thread.raw)
        .putExtra(EXTRA_TAG, tag)
        .putExtra(EXTRA_TITLE, title)

    companion object {
        private const val EXTRA_ACCOUNT = "amp.account"
        private const val EXTRA_THREAD = "amp.thread"
        private const val EXTRA_TAG = "amp.tag"
        private const val EXTRA_TITLE = "amp.title"

        fun from(intent: Intent): NoticeKey? {
            val account = intent.getStringExtra(EXTRA_ACCOUNT)?.let(::AccountId) ?: return null
            val thread = intent.getStringExtra(EXTRA_THREAD)?.let(ThreadId::parse) ?: return null
            val tag = intent.getStringExtra(EXTRA_TAG) ?: return null
            return NoticeKey(account, thread, tag, intent.getStringExtra(EXTRA_TITLE).orEmpty())
        }
    }
}

/**
 * The one place notifications are built. Each event claims its tag in the account's ledger first, so whichever
 * delivery path sees it first posts it and the others post nothing. The tag is also the Android notification tag,
 * so a repeated push replaces instead of stacking.
 */
internal class Notifier(private val context: Context, private val databases: AccountDatabases) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannels(AmpChannel.entries.map { NotificationChannel(it.id, it.title, it.importance) })
    }

    /**
     * [local] is a finish the app detected itself, which carries no reply text. When Amp's push for the same turn
     * comes after it, the push fills its reply into that notification without alerting again.
     */
    suspend fun postReady(account: AccountId, event: PushEvent.ThreadIdle, local: Boolean = false): Posting {
        val fill = !claim(account, event.thread, event.tag, "thread_idle")
        if (fill && (local || event.body.isBlank() || !shownLocally(event.tag))) return Posting.AlreadyPosted
        val key = NoticeKey(account, event.thread, event.tag, event.title)
        val reply = Notification.Action.Builder(null, "Reply", actionIntent(key, NotificationActions.REPLY, mutable = true))
            .addRemoteInput(RemoteInput.Builder(NotificationActions.REPLY_TEXT).setLabel("Reply to ${event.title}").build())
            .build()
        val builder = base(key, AmpChannel.Finished, event.body.ifBlank { "Ready for your next message" }).addAction(reply)
            .setOnlyAlertOnce(true)
            .addExtras(Bundle().apply { putBoolean(EXTRA_LOCAL, local) })
        val posting = post(key, AmpChannel.Finished, builder)
        return if (fill && posting is Posting.Posted) Posting.Filled else posting
    }

    /** A finish the user watched in a window, or one on a snoozed thread: decided, so no later delivery posts it. */
    suspend fun markSeen(account: AccountId, event: PushEvent.ThreadIdle) {
        databases.forAccount(account).ledger.claim(LedgerRow(event.tag, event.thread.raw, "thread_idle", System.currentTimeMillis(), LedgerState.Dismissed))
    }

    private fun shownLocally(tag: String): Boolean =
        manager.activeNotifications.any { it.tag == tag && it.notification.extras.getBoolean(EXTRA_LOCAL) }

    /** `approval_required` says nothing about the gate, so this shows at once and [postGate] fills it in. */
    suspend fun postGatePending(account: AccountId, event: PushEvent.ApprovalRequired): Posting {
        if (!claim(account, event.thread, event.tag, "approval_required")) return Posting.AlreadyPosted
        val key = NoticeKey(account, event.thread, event.tag, event.title)
        return post(key, AmpChannel.NeedsYou, base(key, AmpChannel.NeedsYou, event.body.ifBlank { "Waiting for your approval" }))
    }

    fun postGate(key: NoticeKey, gate: HumanGate) {
        val text = gateText(gate)
        val builder = base(key, AmpChannel.NeedsYou, text)
        for (button in gateButtons(gate)) {
            val action = when (button) {
                is GateButton.Answer -> Notification.Action.Builder(
                    null, button.label,
                    actionIntent(key, NotificationActions.ANSWER, mutable = false, request = button.label) {
                        putExtra(NotificationActions.TOKEN, GateTokens.encode(button.response))
                            .putExtra(NotificationActions.RESOLVED, button.resolved)
                            .putExtra(NotificationActions.SUMMARY, text)
                    },
                ).build()
                is GateButton.TextAnswer -> Notification.Action.Builder(
                    null, button.label,
                    actionIntent(key, NotificationActions.ANSWER_TEXT, mutable = true) { putExtra(NotificationActions.GATE, button.gate.id.raw) },
                ).addRemoteInput(RemoteInput.Builder(NotificationActions.REPLY_TEXT).setLabel(button.gate.title).build()).build()
                is GateButton.Open -> Notification.Action.Builder(null, button.label, openIntent(key, focusGate = button.onGate)).build()
                is GateButton.Web -> Notification.Action.Builder(null, button.label, webIntent(key)).build()
            }
            builder.addAction(action)
        }
        post(key, AmpChannel.NeedsYou, builder)
    }

    /** A gate that could not be read: tapping opens the thread on its gate. */
    fun postOpenOnly(key: NoticeKey, text: String) {
        post(key, AmpChannel.NeedsYou, base(key, AmpChannel.NeedsYou, text).addAction(Notification.Action.Builder(null, "Open", openIntent(key, focusGate = true)).build()))
    }

    suspend fun postOther(account: AccountId, event: PushEvent.Other): Posting {
        if (!claim(account, event.thread, event.tag, event.kind)) return Posting.AlreadyPosted
        val key = NoticeKey(account, event.thread, event.tag, event.title)
        return post(key, AmpChannel.Other, base(key, AmpChannel.Other, event.body))
    }

    /** A thread that needs a person, seen by the poll or watch path rather than a push. */
    suspend fun postNeedsYou(key: NoticeKey, text: String): Boolean {
        if (!claim(key.account, key.thread, key.tag, "action_required")) return false
        postOpenOnly(key, text)
        return true
    }

    /** After an answer or a reply: the notification says what happened, with no buttons left to press twice. */
    suspend fun resolve(key: NoticeKey, channel: AmpChannel, text: String, state: LedgerState) {
        databases.forAccount(key.account).ledger.resolve(key.tag, state)
        post(key, channel, base(key, channel, text).setOnlyAlertOnce(true).setTimeoutAfter(RESOLVED_TIMEOUT_MS))
    }

    fun cancelAccount(account: AccountId) {
        manager.activeNotifications.filter { it.notification.group == group(account) }.forEach { manager.cancel(it.tag, it.id) }
    }

    private suspend fun claim(account: AccountId, thread: ThreadId, tag: String, kind: String): Boolean =
        databases.forAccount(account).ledger.claim(LedgerRow(tag, thread.raw, kind, System.currentTimeMillis(), LedgerState.Posted))

    private fun base(key: NoticeKey, channel: AmpChannel, text: String): Notification.Builder =
        Notification.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_amp).setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(key.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setGroup(group(key.account))
            .setAutoCancel(true)
            .setContentIntent(openIntent(key, focusGate = channel == AmpChannel.NeedsYou))

    private fun post(key: NoticeKey, channel: AmpChannel, builder: Notification.Builder): Posting {
        builder.setCategory(if (channel == AmpChannel.NeedsYou) Notification.CATEGORY_REMINDER else Notification.CATEGORY_MESSAGE)
        manager.notify(key.tag, ID, builder.build())
        return when {
            !manager.areNotificationsEnabled() -> Posting.NotificationsOff
            manager.getNotificationChannel(channel.id)?.importance == NotificationManager.IMPORTANCE_NONE -> Posting.ChannelOff(channel)
            else -> Posting.Posted(channel)
        }
    }

    private fun actionIntent(
        key: NoticeKey,
        action: String,
        mutable: Boolean,
        request: String = "",
        extras: Intent.() -> Intent = { this },
    ): PendingIntent {
        val intent = key.into(Intent(context, NotificationActionReceiver::class.java).setAction(action)).extras()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, "${key.tag}/$action/$request".hashCode(), intent, flags)
    }

    private fun openIntent(key: NoticeKey, focusGate: Boolean): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .setData(NotificationOpen(key.account, key.thread).uri(focusGate))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, "${key.tag}/open/$focusGate".hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun webIntent(key: NoticeKey): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://ampcode.com/threads/${key.thread.raw}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, "${key.tag}/web".hashCode(), view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ID = 1
        private const val RESOLVED_TIMEOUT_MS = 15_000L
        private const val EXTRA_LOCAL = "amp.local"

        fun group(account: AccountId) = "amp:${account.raw}"
    }
}

/** What one post call did, as Android will show it. Only [Posted] reaches the shade. */
internal sealed interface Posting {
    data class Posted(val channel: AmpChannel) : Posting

    /** The ledger already held the tag: another delivery path posted this event. */
    data object AlreadyPosted : Posting

    /** Amp's push for a turn the app had already posted from its own check: the reply text replaced the generic line. */
    data object Filled : Posting
    data object NotificationsOff : Posting
    data class ChannelOff(val channel: AmpChannel) : Posting
}

/** Notification action intents: one action string per kind, extras from [NoticeKey] plus these. */
internal object NotificationActions {
    const val REPLY = "com.agentifiers.amp.notify.REPLY"
    const val ANSWER = "com.agentifiers.amp.notify.ANSWER"
    const val ANSWER_TEXT = "com.agentifiers.amp.notify.ANSWER_TEXT"
    const val REPLY_TEXT = "amp.reply"
    const val TOKEN = "amp.token"
    const val RESOLVED = "amp.resolved"
    const val GATE = "amp.gate"
    const val SUMMARY = "amp.summary"
}
