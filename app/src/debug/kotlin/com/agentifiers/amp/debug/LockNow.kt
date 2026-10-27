package com.agentifiers.amp.debug

import org.json.JSONObject

/** `lock-now {}`: locks every window at once when app lock is on, so flows test the lock without backgrounding. */
object LockNow : AppEventHandler {
    override val name = "lock-now"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String =
        if (app.lock.lockNow()) "locked" else "refused: app lock is off"
}
