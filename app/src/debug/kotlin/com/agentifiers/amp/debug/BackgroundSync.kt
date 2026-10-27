package com.agentifiers.amp.debug

import com.agentifiers.amp.notify.PushInbox
import com.agentifiers.amp.sync.api.Loadable
import org.json.JSONObject

/** `background-sync {}`: a relay wake message for the last-used account, which queues its background sync. */
object BackgroundSync : AppEventHandler {
    override val name = "background-sync"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val roster = (app.engines.roster.value as? Loadable.Ready)?.value ?: throw BadPayload("the account roster has not loaded")
        val account = roster.lastUsed ?: throw BadPayload("no account is signed in")
        return PushInbox.deliver(account, null)
    }
}
