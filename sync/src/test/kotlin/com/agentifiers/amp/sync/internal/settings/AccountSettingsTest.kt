package com.agentifiers.amp.sync.internal.settings

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.model.AccountCommand
import com.agentifiers.amp.model.AccountResource
import com.agentifiers.amp.model.AutomationInfo
import com.agentifiers.amp.model.AutomationStatus
import com.agentifiers.amp.model.ExtensionsGlance
import com.agentifiers.amp.model.IntentId
import com.agentifiers.amp.model.McpApprovalInfo
import com.agentifiers.amp.model.McpApprovalOwner
import com.agentifiers.amp.model.OAuthClientInfo
import com.agentifiers.amp.model.OAuthClientType
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.RunnerShare
import com.agentifiers.amp.model.SecretEntry
import com.agentifiers.amp.model.SecretTarget
import com.agentifiers.amp.model.ShipBehavior
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.AccountCommandResult
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.net.AmpHttp
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.net.FixedBearer
import com.sun.net.httpserver.HttpServer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetSocketAddress
import com.agentifiers.amp.sync.internal.refusingClient
import java.util.Collections
import kotlin.time.Instant

class AccountSettingsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<AutoCloseable>()

    @After
    fun close() {
        scope.cancel()
        closers.forEach(AutoCloseable::close)
    }

    @Test
    fun `every resource reads the settings scenario's seed`() = runBlocking {
        val settings = fake()

        assertEquals(listOf(RunnerShare(RunnerId("android-spike"), "5a1e9d3c-0f2b-4c67-8e41-b7d2a9c6f013")), ready(settings, AccountResource.RunnerShares))
        assertEquals(listOf(SecretEntry("GITHUB_TOKEN", true), SecretEntry("NODE_ENV", false)), ready(settings, AccountResource.Secrets(SecretTarget.Personal)))
        assertEquals(emptyList<SecretEntry>(), ready(settings, AccountResource.Secrets(SecretTarget.Workspace)))
        assertEquals(
            listOf(
                AutomationInfo(
                    "auto_01JX3N7Q8K2M4P6R8T0V2W4Y6A", thread("T-01a16702-3202-73fb-8f52-e4498ccd3721"), "Nightly dependency check", "Every day at 9:00",
                    AutomationStatus.Active, Instant.parse("2026-10-24T09:00:00Z"), Instant.parse("2026-10-22T09:00:00Z"), null,
                ),
                AutomationInfo(
                    "auto_01JX3N9B1D3F5H7J9L1N3Q5S7U", thread("T-01a16738-2ec2-7354-98e9-688bc413f079"), "Weekly changelog", "Fridays at 17:00",
                    AutomationStatus.Paused, null, Instant.parse("2026-10-22T09:00:00Z"), null,
                ),
            ),
            ready(settings, AccountResource.Automations),
        )
        assertEquals(listOf("Rivet", "Ghostty", "Kotlin Multiplatform"), ready(settings, AccountResource.DictationVocabulary))
        assertEquals(
            listOf(McpApprovalInfo("linear", "3f9a1c7e5b2d8f4a6c0e9b1d3f5a7c9e2b4d6f8a0c1e3b5d7f9a2c4e6b8d0f1a", McpApprovalOwner.User)),
            ready(settings, AccountResource.McpApprovals),
        )
        assertEquals(
            listOf(OAuthClientInfo("client_01JX3P2R4T6V8X0Z2B4D6F8H0K", "Release dashboard", persistentListOf("threads:read"))),
            ready(settings, AccountResource.OAuthClients),
        )
        assertEquals(
            ExtensionsGlance(
                mcpServers = persistentListOf("linear", "sentry"),
                pluginRepos = persistentListOf("https://ampcode.com/git/@devuser/-/plugins"),
                skillRepos = persistentListOf("https://ampcode.com/git/@devuser/-/skills", "https://ampcode.com/git/@example-team/-/skills"),
            ),
            ready(settings, AccountResource.Extensions),
        )
        assertEquals(listOf("preview.example.dev"), ready(settings, AccountResource.CustomDomains))
    }

    @Test
    fun `the same key returns the same flow`() {
        val settings = fake()
        assertSame(settings.read(AccountResource.Automations), settings.read(AccountResource.Automations))
        assertSame(settings.read(AccountResource.Secrets(SecretTarget.Personal)), settings.read(AccountResource.Secrets(SecretTarget.Personal)))
    }

    @Test
    fun `each command settles once and the key it changes already shows the change`() = runBlocking {
        val settings = fake()
        val results = collect(settings)
        val shares = settings.read(AccountResource.RunnerShares)
        val secrets = settings.read(AccountResource.Secrets(SecretTarget.Personal))
        val automations = settings.read(AccountResource.Automations)
        val vocabulary = settings.read(AccountResource.DictationVocabulary)
        val clients = settings.read(AccountResource.OAuthClients)
        listOf(shares, secrets, automations, vocabulary, clients).forEach { settle(it) }

        val intents = mutableListOf<IntentId>()
        suspend fun run(command: AccountCommand): AccountCommandResult {
            val intent = settings.dispatch(command).also(intents::add)
            return withTimeout(10_000) { results.awaitResult(intent) }
        }

        assertEquals(Outcome.Ok(Unit), run(AccountCommand.ShareRunner(RunnerId("android-spike"), share = false)).outcome)
        assertEquals(emptyList<RunnerShare>(), shares.readyValue())
        assertEquals(Outcome.Ok(Unit), run(AccountCommand.ShareRunner(RunnerId("android-spike"), share = true)).outcome)
        assertEquals(listOf(RunnerId("android-spike")), shares.readyValue().map { it.runner })

        val secret = run(AccountCommand.SetSecret(SecretTarget.Personal, "SENTRY_DSN", "dsn-AMPCANARY-value", secret = true))
        assertEquals(Outcome.Ok(Unit), secret.outcome)
        assertFalse("the result never prints the secret value", secret.toString().contains("AMPCANARY"))
        assertEquals(listOf(SecretEntry("GITHUB_TOKEN", true), SecretEntry("NODE_ENV", false), SecretEntry("SENTRY_DSN", true)), secrets.readyValue())

        assertEquals(Outcome.Ok(Unit), run(AccountCommand.SetAutomationEnabled("auto_01JX3N9B1D3F5H7J9L1N3Q5S7U", enabled = true)).outcome)
        assertEquals(listOf(true, true), automations.readyValue().map { it.enabled })
        assertEquals(Outcome.Ok(Unit), run(AccountCommand.DeleteAutomation("auto_01JX3N7Q8K2M4P6R8T0V2W4Y6A")).outcome)
        assertEquals(listOf("Weekly changelog"), automations.readyValue().map { it.title })
        assertEquals(
            Outcome.Failed(EngineError.Server("not-found", "Automation not found")),
            run(AccountCommand.DeleteAutomation("auto_missing")).outcome,
        )

        assertEquals(Outcome.Ok(Unit), run(AccountCommand.EditVocabulary(add = persistentListOf("Jetpack Compose"), remove = persistentListOf("Ghostty"))).outcome)
        assertEquals(listOf("Rivet", "Kotlin Multiplatform", "Jetpack Compose"), vocabulary.readyValue())

        val create = AccountCommand.CreateOAuthClient("Phone companion", OAuthClientType.ClientSide, persistentListOf("threads:read"), persistentListOf("amp://oauth/callback"))
        assertEquals(Outcome.Ok(Unit), run(create).outcome)
        assertEquals(listOf("Release dashboard", "Phone companion"), clients.readyValue().map { it.name })
        assertEquals(
            Outcome.Refused("Create server-side clients on the web, where the secret is shown once."),
            run(create.copy(name = "Backend", type = OAuthClientType.ServerSide)).outcome,
        )
        assertEquals(listOf("Release dashboard", "Phone companion"), clients.readyValue().map { it.name })

        assertEquals(Outcome.Ok(Unit), run(AccountCommand.SetShipBehavior(ProjectId("9d2c4e6a-8b1f-4a3c-b5d7-e9f1a3c5b7d9"), ShipBehavior.Custom("Open a PR and request review."))).outcome)
        assertEquals(
            Outcome.Failed(EngineError.Server("not-found", "Project not found")),
            run(AccountCommand.SetShipBehavior(ProjectId("00000000-0000-4000-8000-000000000000"), ShipBehavior.PushBranch)).outcome,
        )
        assertEquals(Outcome.Ok(Unit), run(AccountCommand.ClearOrbSnapshots(ProjectId("9d2c4e6a-8b1f-4a3c-b5d7-e9f1a3c5b7d9"))).outcome)

        assertEquals(intents, results.all().map { it.intent })
    }

    @Test
    fun `an answer in an unknown shape is drift`() = runBlocking {
        val settings = scripted { method -> if (method == "listRunnerShares") 200 to """{"ok":true,"result":{"shares":[{"shareID":7}]}}""" else null }
        assertEquals(Loadable.Failed(EngineError.Drift("listRunnerShares")), settle(settings.read(AccountResource.RunnerShares)))
    }

    @Test
    fun `401 is unauthorized and a refused connection is offline`() = runBlocking {
        val rejected = scripted { 401 to """{"error":"unauthorized"}""" }
        assertEquals(Loadable.Failed(EngineError.Unauthorized), settle(rejected.read(AccountResource.Automations)))

        val offline = AccountSettings(ampHttp("http://127.0.0.1:9", refusingClient), scope)
        assertEquals(Loadable.Failed(EngineError.Offline), settle(offline.read(AccountResource.Automations)))
    }

    @Test
    fun `a read of a failed key fetches again`() = runBlocking {
        var calls = 0
        val settings = scripted { method ->
            if (method != "getDictationVocabulary") return@scripted null
            calls++
            if (calls == 1) 500 to "<html>bad gateway</html>" else 200 to """{"ok":true,"result":{"vocabulary":"Rivet"}}"""
        }
        assertEquals(Loadable.Failed(EngineError.Server("HTTP 500", "HTTP 500")), settle(settings.read(AccountResource.DictationVocabulary)))
        assertEquals(listOf("Rivet"), settle(settings.read(AccountResource.DictationVocabulary)).let { (it as Loadable.Ready).value })
    }

    @Test
    fun `a failed refetch keeps the ready value and a lost write is unknown`() = runBlocking {
        var listed = 0
        val settings = scripted { method ->
            when (method) {
                "listAutomations" -> if (++listed == 1) 200 to """{"ok":true,"result":{"automations":[]}}""" else 503 to "unavailable"
                "deleteAutomation" -> -1 to ""
                else -> null
            }
        }
        val results = collect(settings)
        val automations = settings.read(AccountResource.Automations)
        val first = settle(automations)
        val intent = settings.dispatch(AccountCommand.DeleteAutomation("auto_01JX3N7Q8K2M4P6R8T0V2W4Y6A"))

        assertEquals(Outcome.Unknown, withTimeout(10_000) { results.awaitResult(intent) }.outcome)
        assertEquals(2, listed)
        assertSame(first, automations.value)
    }

    @Test
    fun `an automation's disabledReason names why it stopped and a refresh reads the next run again`() = runBlocking {
        var listed = 0
        val settings = scripted { method ->
            if (method != "listAutomations") return@scripted null
            listed++
            val next = if (listed == 1) "2026-10-27T12:05:59.000Z" else "2026-10-27T12:20:59.000Z"
            200 to """{"ok":true,"result":{"automations":[
                ${automationJson("a1", "T-01a16702-3202-73fb-8f52-e4498ccd3721", """{"provider":"schedule","schedule":"*/15 * * * *","scheduleLabel":"Every 15 minutes"}""", true, null, next, null)},
                ${automationJson("a2", "T-01a16738-2ec2-7354-98e9-688bc413f079", """{"provider":"schedule","schedule":"0 9 * * *"}""", false, "last-run-failed", null, "2026-10-27T09:00:00.000Z")},
                ${automationJson("a3", "T-01a16738-2ec2-7354-98e9-688bc413f079", """{"provider":"slack","event":"message","slackChannelID":"C00000001"}""", false, "schedule-exhausted", null, null)},
                ${automationJson("a4", "T-01a16738-2ec2-7354-98e9-688bc413f079", """{"provider":"schedule","schedule":"0 9 * * *"}""", false, "user-paused", null, null)}
            ]}}"""
        }
        val automations = settings.read(AccountResource.Automations)
        assertEquals(
            listOf(
                listOf("a1", "Every 15 minutes", AutomationStatus.Active, Instant.parse("2026-10-27T12:05:59Z"), null),
                listOf("a2", "0 9 * * *", AutomationStatus.Failed, null, Instant.parse("2026-10-27T09:00:00Z")),
                listOf("a3", "New Slack messages", AutomationStatus.Completed, null, null),
                listOf("a4", "0 9 * * *", AutomationStatus.Paused, null, null),
            ),
            (settle(automations) as Loadable.Ready).value.map { listOf(it.id, it.trigger, it.status, it.nextRun, it.lastRun) },
        )
        settings.refresh(AccountResource.Automations)
        val refreshed = withTimeout(10_000) { automations.first { (it as? Loadable.Ready)?.value?.first()?.nextRun == Instant.parse("2026-10-27T12:20:59Z") } }
        assertEquals(2, listed)
        assertEquals("a1", (refreshed as Loadable.Ready).value.first().id)
    }

    private fun automationJson(id: String, thread: String, trigger: String, enabled: Boolean, reason: String?, next: String?, last: String?) =
        """{"id":"$id","title":"Rollout check","targetThreadID":"$thread","trigger":$trigger,"runMode":"existing","agentMode":null,""" +
            """"enabled":$enabled,"disabledReason":${reason?.let { "\"$it\"" }},"nextRunAt":${next?.let { "\"$it\"" }},""" +
            """"lastRunAt":${last?.let { "\"$it\"" }},"lastRunError":null,"createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-01T00:00:00.000Z","targetThreadTitle":null}"""

    /** Served at the recording moment, so the seed's times read back as recorded. */
    private fun fake(): AccountSettings {
        val server = FakeAmp.start("settings", servedAt = FakeAmp.RECORDED_AT).also(closers::add)
        return AccountSettings(ampHttp(server.ampBaseUrl), scope)
    }

    /** A server whose `/api/internal` answers come from [answer]: (status, body), status -1 drops the connection, null is not-found. */
    private fun scripted(answer: (String) -> Pair<Int, String>?): AccountSettings {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/internal") { exchange ->
            val (status, body) = answer(exchange.requestURI.rawQuery) ?: (200 to """{"ok":false,"error":{"code":"not-found","message":"No method"}}""")
            if (status == -1) {
                exchange.close()
            } else {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        closers += AutoCloseable { server.stop(0) }
        return AccountSettings(ampHttp("http://127.0.0.1:${server.address.port}"), scope)
    }

    private fun collect(settings: AccountSettings): Results {
        val results = Results()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { settings.commandResults.collect(results::add) }
        return results
    }

    private class Results {
        private val seen = Collections.synchronizedList(mutableListOf<AccountCommandResult>())
        private val updates = kotlinx.coroutines.flow.MutableStateFlow(0)

        fun add(result: AccountCommandResult) {
            seen += result
            updates.value = seen.size
        }

        fun all(): List<AccountCommandResult> = seen.toList()

        suspend fun awaitResult(intent: IntentId): AccountCommandResult {
            updates.first { seen.any { it.intent == intent } }
            return seen.single { it.intent == intent }
        }
    }

    private suspend fun <T> settle(flow: StateFlow<Loadable<T>>): Loadable<T> = withTimeout(10_000) { flow.first { it !is Loadable.Loading } }

    private suspend fun <T> ready(settings: AccountSettings, resource: AccountResource<T>): T =
        when (val state = settle(settings.read(resource))) {
            is Loadable.Ready -> state.value
            else -> throw AssertionError("$resource settled as $state")
        }

    private fun <T> StateFlow<Loadable<T>>.readyValue(): T = (value as Loadable.Ready).value

    private fun thread(raw: String) = ThreadId.parse(raw)!!
}

/** The one place tests build AmpHttp, so a change to its constructor is a one-line fix here. */
internal fun ampHttp(baseUrl: String, client: OkHttpClient = OkHttpClient()): AmpHttp =
    AmpHttp(client, Endpoint(baseUrl.toHttpUrl(), "$baseUrl/actors".toHttpUrl(), "pk-test"), FixedBearer("fake-access-AMPCANARY"))
