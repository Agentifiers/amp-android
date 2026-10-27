package com.agentifiers.amp.sync.internal.catalog

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.NewThreadSpec
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbProviderConfigId
import com.agentifiers.amp.model.OrbSize
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.Visibility
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.SignInResult
import com.agentifiers.amp.sync.api.StartedThread
import com.agentifiers.amp.sync.internal.EngineHarness
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orbs on a workspace's self-hosted providers, against :fake-amp's `newthread-self-hosted` workspace. */
class SelfHostedOrbTest {
    private val low = CatalogEngine.DIAL.first()

    private fun orbOn(name: String) = ExecutorChoice.Orb(null, OrbSize("a1.small"), OrbProvider.SelfHosted.named(name)!!)

    private fun spec(executor: ExecutorChoice) = NewThreadSpec(Draft.text("Print uname -a"), low, executor, persistentListOf(), Visibility.Private)

    private suspend fun EngineHarness.signedIn(): AccountId {
        ready()
        return (engines.withToken(environment, "fake-amp-accepts-any-bearer") as SignInResult.Accepted).account
    }

    private fun withWorkspace(block: suspend (FakeAmpServer, EngineHarness) -> Unit) = FakeAmp.start("newthread-self-hosted").use { server ->
        runBlocking { block(server, EngineHarness(server)) }
    }

    @Test
    fun `a self-hosted orb starts with createRemoteExecutorThread naming the provider, as amp --orb-provider does`() = withWorkspace { server, app ->
        val started = app.engines.scope(app.signedIn()).startThread(spec(orbOn("example-orbs-2")))

        assertTrue((started as Outcome.Ok).value is StartedThread.Running)
        val params = server.remoteExecutorThreads.single()
        assertTrue(params.string("messageId")!!.matches(Regex("^M-[A-Za-z0-9]+$")))
        assertEquals(
            Json.parseToJsonElement(
                """{"content":[{"type":"text","text":"Print uname -a"}],"agentMode":"low","environmentResource":"a1.small","orbProvider":{"name":"example-orbs-2"}}""",
            ),
            JsonObject(params - "messageId" - "userState"),
        )
    }

    @Test
    fun `a provider the web session listed starts by its configID, as the web composer sends it`() = withWorkspace { server, app ->
        val listed = OrbProvider.SelfHosted.Listed(OrbProviderConfigId("configID-00000002"), "example-orbs-2")
        val started = app.engines.scope(app.signedIn()).startThread(spec(ExecutorChoice.Orb(null, OrbSize("a1.small"), listed)))

        assertTrue((started as Outcome.Ok).value is StartedThread.Running)
        assertEquals(Json.parseToJsonElement("""{"configID":"configID-00000002"}"""), server.remoteExecutorThreads.single()["orbProvider"])
    }

    @Test
    fun `a provider the workspace lacks is refused with Amp's reason and no thread`() = withWorkspace { server, app ->
        val started = app.engines.scope(app.signedIn()).startThread(spec(orbOn("example-orbs-9")))

        assertEquals(Outcome.Failed(EngineError.Server("200", "not-found: No orb provider example-orbs-9 in this workspace.")), started)
        assertEquals(1, server.remoteExecutorThreads.size)
    }

    @Test
    fun `a respawn cannot move a thread onto a self-hosted provider`() = withWorkspace { _, app ->
        val respawned = app.engines.scope(app.signedIn()).respawn(ThreadId.parse("T-01a16702-3202-73fb-8f52-e4498ccd3721")!!, orbOn("example-orbs-1"))

        assertEquals(Outcome.Refused("A self-hosted orb starts with a new thread. Discard this one and send again."), respawned)
    }

    @Test
    fun `client_spawn_executor carries no provider, because its schema has none`() {
        val params = spawnParams(ExecutorChoice.Orb(ProjectId("p1"), OrbSize("a1.large"), OrbProvider.SelfHosted.named("example-orbs-1")!!))!!

        assertEquals(Json.parseToJsonElement("""{"projectID":"p1","environmentResource":"a1.large"}"""), JsonObject(params - "requestId"))
    }
}
