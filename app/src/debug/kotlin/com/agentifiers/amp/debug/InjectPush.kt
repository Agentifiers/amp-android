package com.agentifiers.amp.debug

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.notify.PushInbox
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.api.encryptPushForTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `inject-push {kind, threadId, title, body, tag, ...}`: the plaintext Web Push payload (S11 shape). It is encrypted to
 * the signed-in account's push keys and handed to the same inbox a relay or UnifiedPush message reaches, so
 * decryption, parsing, suppression and display all run as for a real push.
 */
object InjectPush : AppEventHandler {
    override val name = "inject-push"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        payload.requireString("kind")
        payload.requireString("threadId").let { ThreadId.parse(it) ?: throw BadPayload("\"$it\" is not a thread id") }
        payload.requireString("tag")
        val roster = (app.engines.roster.value as? Loadable.Ready)?.value ?: throw BadPayload("the account roster has not loaded")
        val account = roster.lastUsed ?: throw BadPayload("no account is signed in")
        return withContext(Dispatchers.IO) {
            val body = app.engines.encryptPushForTest(account, payload.toString().encodeToByteArray()) ?: throw BadPayload("no account is signed in")
            PushInbox.deliver(account, body)
        }
    }
}
