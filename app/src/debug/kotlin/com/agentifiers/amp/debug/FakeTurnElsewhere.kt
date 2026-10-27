package com.agentifiers.amp.debug

import com.agentifiers.amp.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * `fake-turn-elsewhere {threadId, text, openElsewhere?, state?, afterMs?}`: waits up to 30 s for the app to subscribe
 * to the thread and for its subscribing socket to close, then the running fake ends a turn on it with `text` as the
 * assistant's last message, as Amp would, and pushes to every subscription unless a client holds the thread open.
 * `openElsewhere` true first opens the thread in another client, as an ampcode.com tab would, so no push comes.
 * `state` "working" starts the turn instead of ending it. `afterMs` waits that long before ending it, so a flow can
 * send the app to the background first.
 */
object FakeTurnElsewhere : AppEventHandler {
    override val name = "fake-turn-elsewhere"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val thread = payload.requireString("threadId").let { ThreadId.parse(it) ?: throw BadPayload("\"$it\" is not a thread id") }
        val fake = UseServer.running ?: throw BadPayload("no fake server is running; send use-server {\"scenario\"} first")
        return withContext(Dispatchers.IO) {
            try {
                if (payload.optBoolean("openElsewhere")) fake.holdOpenElsewhere(thread.raw, open = true)
                if (payload.optString("state") == "working") {
                    fake.startTurnElsewhere(thread.raw)
                    return@withContext "turn started"
                }
                val text = payload.requireString("text")
                val subscribed = withTimeoutOrNull(30_000) {
                    while (fake.subscriptionCount(thread.raw) == 0 || fake.socketCount(thread.raw) > 0) delay(250)
                } != null
                delay(payload.optLong("afterMs"))
                val sent = fake.finishTurnElsewhere(thread.raw, text)
                if (subscribed) "$sent push(es) sent" else "no subscription after 30 s, $sent push(es) sent"
            } catch (_: IllegalArgumentException) {
                throw BadPayload("the fake has no thread ${thread.raw.take(10)}")
            }
        }
    }
}
