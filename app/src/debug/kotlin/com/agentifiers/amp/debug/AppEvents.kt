package com.agentifiers.amp.debug

import android.content.Context
import android.util.Log
import com.agentifiers.amp.AppLockController
import com.agentifiers.amp.LayoutOverrides
import com.agentifiers.amp.ServerChoice
import com.agentifiers.amp.SignOutCoordinator
import com.agentifiers.amp.WindowNavigator
import com.agentifiers.amp.sync.api.AmpEngines
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Debug-only agent-device hook: `amp-debug://event/<name>?json=<urlencoded payload>`. One handler per file;
 * a new event is a new file plus one line in [AppEvents.handlers]. docs/design/verification.md lists the events.
 */
interface AppEventHandler {
    val name: String

    /** Returns the line logged under [AppEvents.TAG]. Never includes a secret from the payload. */
    suspend fun handle(payload: JSONObject, app: AppEventContext): String
}

/** What a handler may touch: the engine, the window controls, app lock, and sign-out, as :app exposes them to Hilt. */
class AppEventContext(
    val engines: AmpEngines,
    val server: ServerChoice,
    val navigator: WindowNavigator,
    val overrides: LayoutOverrides,
    val lock: AppLockController,
    val signOuts: SignOutCoordinator,
    val context: Context,
)

/** A payload that does not match the event's documented shape. */
class BadPayload(message: String) : Exception(message)

object AppEvents {
    const val TAG = "AmpEvent"

    val handlers: Map<String, AppEventHandler> = listOf(
        UseServer,
        SignInToken,
        OpenThread,
        SetArrangement,
        GeometryOverrideEvent,
        DropConnection,
        FilesStored,
        RunPluginCommand,
        LockNow,
        InjectPush,
        FakeThreadElsewhere,
        FakeTurnElsewhere,
        BackgroundSync,
        SeedOutbox,
        PinWidget,
        TerminalType,
        TerminalPaste,
        OpenCatalog,
    ).associateBy { it.name }

    /** Flows fire events back to back; each one finishes before the next starts, so sign-in sees the server use-server chose. */
    private val order = Mutex()

    suspend fun dispatch(name: String, json: String?, app: AppEventContext) = order.withLock { run(name, json, app) }

    private suspend fun run(name: String, json: String?, app: AppEventContext) {
        val handler = handlers[name]
        if (handler == null) {
            Log.w(TAG, "$name: unknown event; known: ${handlers.keys.sorted().joinToString()}")
            return
        }
        val line = try {
            handler.handle(if (json.isNullOrBlank()) JSONObject() else JSONObject(json), app)
        } catch (e: BadPayload) {
            "rejected: ${e.message}"
        } catch (e: org.json.JSONException) {
            "rejected: the json parameter is not a JSON object"
        }
        Log.i(TAG, "$name: $line")
    }
}

internal fun JSONObject.requireString(key: String): String =
    optString(key).takeIf { has(key) && it.isNotEmpty() } ?: throw BadPayload("missing \"$key\"")
