package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.FakeThread
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.PluginCommand
import com.agentifiers.amp.fake.PluginDialogs
import com.agentifiers.amp.fake.PluginScript
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.ThreadScript
import com.agentifiers.amp.fake.gateStatus
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.listingFor
import kotlinx.serialization.json.JsonPrimitive

/**
 * The S15b thread with scripted plugin commands beside the recorded `spike-dialog`. Each command's notice repeats
 * what the server received, so a flow can read the wire answer back from the `ui.notify` bar:
 * - `mcp-question`: an MCP elicitation in the `elicitation/create` shape on the plugin dialog channel [INFERENCE: not observed].
 * - `deploy-key`: a secret `ui.input`; the notice reports only the length, never the secret.
 * - `deploy-wizard`: a `ui.wizard` request no client knows, which must render as the Unsupported gate.
 */
internal object GatePlugins : ScenarioSpec {
    override val name = "gate-plugins"

    override fun build(): Scenario {
        val script = ThreadScript.from(Capture.load("s15b-plugin-dialog"))
        val commands = PluginDialogs.spikeDialog().commands + mapOf(
            "mcp-question" to PluginCommand(listOf(elicitation())) { answers -> "mcp-question answered ${answers.single()}" },
            "deploy-key" to PluginCommand(listOf(secretInput())) { answers ->
                "deploy-key received ${(answers.single() as? JsonPrimitive)?.content?.length ?: 0} characters"
            },
            "deploy-wizard" to PluginCommand(listOf(wizard())) { answers -> "deploy-wizard answered ${answers.single()}" },
        )
        return Scenario(listOf(FakeThread(GATE_THREAD, listingFor(GATE_THREAD, null, script.version), gateStatus(), script, PluginScript(commands))))
    }

    private fun elicitation() = json(
        "method" to "elicitation/create",
        "params" to json(
            "serverName" to "linear",
            "message" to "File a Linear issue for this crash?",
            "requestedSchema" to json(
                "type" to "object",
                "properties" to json(
                    "title" to json("type" to "string", "title" to "Title"),
                    "points" to json("type" to "integer", "title" to "Points"),
                    "urgent" to json("type" to "boolean", "title" to "Urgent"),
                    "team" to json("type" to "string", "title" to "Team", "enum" to listOf("web", "app")),
                ),
                "required" to listOf("title"),
            ),
        ),
        "target" to json("kind" to "interactive"),
        "requester" to "mcp",
    )

    private fun secretInput() = json(
        "method" to "ui.input",
        "params" to json("options" to json("title" to "Deploy key", "helpText" to "Paste the deploy key", "secret" to true)),
        "target" to json("kind" to "interactive"),
        "requester" to "executor-plugin",
    )

    private fun wizard() = json(
        "method" to "ui.wizard",
        "params" to json("options" to json("title" to "Pick a deploy target")),
        "target" to json("kind" to "interactive"),
        "requester" to "executor-plugin",
    )
}
