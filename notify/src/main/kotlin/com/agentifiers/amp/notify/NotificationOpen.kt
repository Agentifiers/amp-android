package com.agentifiers.amp.notify

import android.content.Intent
import android.net.Uri
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.ThreadId

/**
 * A notification tap: `amp://open?account=&thread=&focus=gate|latest` on the app's launch intent. The window
 * switches to [account] and opens [thread] (screens.md, "Notifications carry their account"). A pending gate shows in
 * the thread's gate dock either way, so `focus` needs no handling beyond opening the thread.
 */
data class NotificationOpen(val account: AccountId, val thread: ThreadId) {
    internal fun uri(focusGate: Boolean): Uri = Uri.Builder().scheme(SCHEME).authority(HOST)
        .appendQueryParameter("account", account.raw)
        .appendQueryParameter("thread", thread.raw)
        .appendQueryParameter("focus", if (focusGate) "gate" else "latest")
        .build()

    companion object {
        private const val SCHEME = "amp"
        private const val HOST = "open"

        /** Null for any intent a notification of this app did not build. */
        fun parse(intent: Intent?): NotificationOpen? {
            val data = intent?.data ?: return null
            if (data.scheme != SCHEME || data.host != HOST) return null
            val account = data.getQueryParameter("account")?.takeIf { it.isNotEmpty() }?.let(::AccountId) ?: return null
            val thread = data.getQueryParameter("thread")?.let(ThreadId::parse) ?: return null
            return NotificationOpen(account, thread)
        }
    }
}
