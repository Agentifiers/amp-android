package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScenarioTest {

    @Test
    fun `settings lists the seeded runner share and env vars and a write shows on the next read`() = FakeAmp.start("settings").use { server ->
        val wire = Wire(server)
        val shares = wire.internal("listRunnerShares").obj("result")!!.array("shares")!!.map { (it as JsonObject).string("runnerId") }
        assertEquals(listOf("android-spike"), shares)

        val personal = json("target" to json("type" to "user"))
        assertEquals(listOf("GITHUB_TOKEN" to "secret", "NODE_ENV" to "env"), envVars(wire, personal))

        val set = wire.internal("setEnvVarSecret", personal + mapOf("name" to JsonPrimitive("SENTRY_DSN"), "value" to JsonPrimitive("dsn"), "kind" to JsonPrimitive("env")))
        assertEquals(true, set.obj("result")!!.bool("created"))
        assertEquals(listOf("GITHUB_TOKEN" to "secret", "NODE_ENV" to "env", "SENTRY_DSN" to "env"), envVars(wire, personal))
    }

    @Test
    fun `settings answers getUserInfo with the workspace the OAuth client methods need`() = FakeAmp.start("settings").use { server ->
        val wire = Wire(server)
        val team = wire.internal("getUserInfo").obj("result")!!.obj("team")!!
        assertEquals("example-team", team.string("name"))

        val clients = wire.internal("listExternalAPIOAuthClients", json("workspaceName" to "example-team")).obj("result")!!.array("clients")!!
        assertEquals(listOf("Release dashboard"), clients.map { (it as JsonObject).string("name") })
        assertEquals("not-found", wire.internal("listExternalAPIOAuthClients", json("workspaceName" to "other")).obj("error")!!.string("code"))
    }

    private fun envVars(wire: Wire, params: JsonObject) =
        wire.internal("listEnvVarSecrets", params).obj("result")!!.array("items")!!.map { (it as JsonObject).let { e -> e.string("name") to e.string("kind") } }
}
