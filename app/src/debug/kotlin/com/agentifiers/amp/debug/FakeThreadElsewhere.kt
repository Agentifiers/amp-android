package com.agentifiers.amp.debug

import com.agentifiers.amp.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** `fake-thread-elsewhere {threadId, title}`: the running fake gains a thread as if the CLI or the web had started it. */
object FakeThreadElsewhere : AppEventHandler {
    override val name = "fake-thread-elsewhere"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val thread = payload.requireString("threadId").let { ThreadId.parse(it) ?: throw BadPayload("\"$it\" is not a thread id") }
        val title = payload.requireString("title")
        val fake = UseServer.running ?: throw BadPayload("no fake server is running; send use-server {\"scenario\"} first")
        withContext(Dispatchers.IO) { fake.startThreadElsewhere(thread.raw, title) }
        return "started ${thread.raw.take(10)} elsewhere"
    }
}
