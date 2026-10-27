package com.agentifiers.amp.debug

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.notify.PushInbox
import com.agentifiers.amp.sync.api.Loadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `use-server {url}` or `{scenario}`: the server the next sign-in goes to. A scenario starts :fake-amp in this
 * process (closing the previous one). A signed-in account on another server is signed out, so the window
 * never shows one server's data while sign-in targets another. The fake's Web Push to `<origin>/push/<account>`
 * goes straight to [PushInbox], the way a relay or UnifiedPush message reaches it.
 */
object UseServer : AppEventHandler {
    override val name = "use-server"
    private var fake: FakeAmpServer? = null
    private val pushes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The scenario server this process runs, for events that inspect its state. */
    val running: FakeAmpServer? get() = fake

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val server = when {
            payload.has("scenario") -> startFake(payload.requireString("scenario"))
            payload.has("url") -> payload.requireString("url").trimEnd('/').let { ServerEnvironment(it, "$it/actors") }
            else -> throw BadPayload("expected {\"url\"} or {\"scenario\"}")
        }
        app.server.use(server)
        val roster = app.engines.roster.value as? Loadable.Ready
        roster?.value?.accounts?.filter { it.server != server }?.forEach { app.signOuts.signOut(it.id) }
        return "next sign-in goes to ${server.ampBaseUrl}"
    }

    private suspend fun startFake(scenario: String): ServerEnvironment = withContext(Dispatchers.IO) {
        if (scenario !in FakeAmp.scenarios) throw BadPayload("unknown scenario \"$scenario\"; known: ${FakeAmp.scenarios.joinToString()}")
        fake?.close()
        val started = FakeAmp.start(scenario)
        started.onPush = { delivery -> pushes.launch { PushInbox.deliver(AccountId(delivery.endpoint.substringAfterLast('/')), delivery.body) } }
        fake = started
        ServerEnvironment(started.ampBaseUrl, started.actorsBaseUrl)
    }
}
