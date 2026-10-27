package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeOrbProvider
import com.agentifiers.amp.fake.FakeWeb
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.obj

/**
 * No threads yet, and the S3 runner online: `listRunners` answers as `captures/s03-runner.live.jsonl` recorded it,
 * over the user actor (`:8`, with its 34 `agentModes`) and internal RPC (`:10`). `listAgentModes` is not recorded, so
 * the app falls back to the Dial. A created thread plays the S4 turn, and spawns go through as S4 recorded them.
 */
internal object NewThread : ScenarioSpec {
    override val name = "newthread"

    override fun build(): Scenario = runnerOnline()

    fun runnerOnline(spawnRefusal: String? = null, orbProviders: List<FakeOrbProvider> = emptyList(), web: FakeWeb? = null): Scenario {
        val capture = Capture.load("s03-runner.live")
        return Scenario(
            threads = emptyList(),
            internal = mapOf("listRunners" to capture.responseBody("internal.listRunners").obj("result")!!),
            actions = mapOf("listRunners" to capture.responseBody("userActor.action.listRunners")["output"]!!),
            spawnRefusal = spawnRefusal,
            orbProviders = orbProviders,
            web = web,
        )
    }
}

/**
 * [NewThread] in a workspace with two self-hosted orb providers, the first its default, in the shape the web's
 * settings list reads (`configID`, `name`, `isDefault`; web chunk `b8da1567.js`). A self-hosted orb starts through
 * `createRemoteExecutorThread`, which refuses a provider name the workspace lacks.
 */
internal object NewThreadSelfHosted : ScenarioSpec {
    override val name = "newthread-self-hosted"

    val providers = listOf(
        FakeOrbProvider("configID-00000001", "example-orbs-1", isDefault = true),
        FakeOrbProvider("configID-00000002", "example-orbs-2"),
    )

    override fun build(): Scenario = NewThread.runnerOnline(orbProviders = providers)
}

/**
 * [NewThreadSelfHosted] with the web app: its layout lists the workspace default and a personal provider to the web
 * session the sign-in page sets, and `createRemoteExecutorThread` resolves only `{configID}`.
 */
internal object NewThreadSelfHostedWeb : ScenarioSpec {
    override val name = "newthread-self-hosted-web"

    val providers = listOf(
        FakeOrbProvider("configID-00000001", "example-orbs-1", isDefault = true),
        FakeOrbProvider("configID-00000002", "example-orbs-2", owner = "personal"),
    )

    override fun build(): Scenario = NewThread.runnerOnline(orbProviders = providers, web = FakeWeb(providerNames = false))
}

/**
 * [NewThreadSelfHostedWeb] after Amp moves the layout data: the first read lists the two providers and every later
 * one answers 404. `example-orbs-3` is in the workspace but not in that list, so only its typed name starts it.
 */
internal object NewThreadSelfHostedWebGone : ScenarioSpec {
    override val name = "newthread-self-hosted-web-gone"

    override fun build(): Scenario = NewThread.runnerOnline(
        orbProviders = NewThreadSelfHostedWeb.providers + FakeOrbProvider("configID-00000003", "example-orbs-3", listed = false),
        web = FakeWeb(layoutReads = 1),
    )
}

/** [NewThread] with every `client_spawn_executor` refused, for the Retry, Choose Another Executor, and Discard path. */
internal object NewThreadSpawnFails : ScenarioSpec {
    override val name = "newthread-spawn-fails"

    /** Scripted: no spawn refusal was ever captured, so its wording is the app's to show, not Amp's. */
    override fun build(): Scenario = NewThread.runnerOnline(spawnRefusal = "Runner android-spike is not connected.")
}
