package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.FakeIdentity
import com.agentifiers.amp.fake.GATE_THREAD
import com.agentifiers.amp.fake.InternalMethods
import com.agentifiers.amp.fake.RUNNER_THREAD
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.World
import com.agentifiers.amp.fake.array
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.obj
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.string
import com.agentifiers.amp.fake.USER_ID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * FleetBasic's threads plus an account with settings: one runner share, two personal env vars (one secret), two
 * automations, a dictation vocabulary, one MCP approval, one OAuth client, extensions, and one custom domain. Writes
 * change what the next read returns. Shapes follow the CLI's zod schemas; every
 * value is scripted, because no capture holds these methods.
 */
internal object Settings : ScenarioSpec {
    override val name = "settings"

    const val WORKSPACE_ID = "0b6f3c52-7d1e-4a8b-9c3f-2e5d8a1b4c70"
    const val WORKSPACE_NAME = "example-team"
    const val RUNNER = "android-spike"
    const val SHARE_ID = "5a1e9d3c-0f2b-4c67-8e41-b7d2a9c6f013"
    const val NIGHTLY = "auto_01JX3N7Q8K2M4P6R8T0V2W4Y6A"
    const val CHANGELOG = "auto_01JX3N9B1D3F5H7J9L1N3Q5S7U"
    const val PROJECT_ID = "9d2c4e6a-8b1f-4a3c-b5d7-e9f1a3c5b7d9"
    const val SPEC_HASH = "3f9a1c7e5b2d8f4a6c0e9b1d3f5a7c9e2b4d6f8a0c1e3b5d7f9a2c4e6b8d0f1a"

    override fun build(): Scenario = Scenario(FleetBasic.build().threads, methods = SettingsMethods())
}

private class SettingsMethods : InternalMethods {
    private val team = json("id" to Settings.WORKSPACE_ID, "name" to Settings.WORKSPACE_NAME, "displayName" to "Example Team")

    private val shares = mutableListOf(share(Settings.SHARE_ID, Settings.RUNNER))

    /** Env var names and kinds per target key; the fake never keeps a value. */
    private val envVars = mutableMapOf("user" to linkedMapOf("GITHUB_TOKEN" to "secret", "NODE_ENV" to "env"))

    private val automations = AutomationBook(listOf(
        AutomationBook.automation(Settings.NIGHTLY, "Nightly dependency check", RUNNER_THREAD, "Every day at 9:00", true, "2026-10-24T09:00:00.000Z", "2026-10-22T09:00:00.000Z"),
        AutomationBook.automation(Settings.CHANGELOG, "Weekly changelog", GATE_THREAD, "Fridays at 17:00", false, null, "2026-10-22T09:00:00.000Z"),
    ))

    private var vocabulary = "Rivet, Ghostty, Kotlin Multiplatform"

    private val clients = mutableListOf(
        client("c2e4a6b8-1d3f-4a5c-8e7b-9d1f3a5c7e9b", "client_01JX3P2R4T6V8X0Z2B4D6F8H0K", "Release dashboard", listOf("threads:read"), listOf("https://dash.example.dev/callback")),
    )

    private val projects = mutableMapOf(Settings.PROJECT_ID to project())
    private var snapshots = 2

    override fun answer(method: String, params: JsonObject, identity: FakeIdentity): JsonObject? = automations.answer(method, params) ?: when (method) {
        "getUserInfo" -> ok(World.userInfo(identity) + mapOf("team" to team))
        "listRunnerShares" -> ok(json("shares" to shares.toList()))
        "shareRunner" -> shareRunner(params)
        "revokeRunnerShare" -> revokeRunnerShare(params)
        "listEnvVarSecrets" -> ok(json("items" to envVars[targetKey(params)].orEmpty().map { (name, kind) -> envVar(name, kind) }))
        "setEnvVarSecret" -> setEnvVarSecret(params)
        "getDictationVocabulary" -> ok(json("vocabulary" to vocabulary))
        "setDictationVocabulary" -> setDictationVocabulary(params)
        "listMCPServerApprovals" -> ok(json(
            "approvals" to listOf(json("serverName" to "linear", "specHash" to Settings.SPEC_HASH, "scope" to "user")),
            "canApproveForWorkspace" to false,
        ))
        "listExternalAPIOAuthClients" -> if (params.string("workspaceName") != Settings.WORKSPACE_NAME) {
            World.notFound("Workspace not found")
        } else {
            ok(json("clients" to clients.toList(), "manageURL" to "https://ampcode.com/w/${Settings.WORKSPACE_NAME}/settings/oauth-clients"))
        }
        "createExternalAPIOAuthClient" -> createClient(params)
        "mcpListAccountServers" -> ok(json("servers" to listOf(
            json("id" to "mcp_01JX3Q4S6U8W0Y2A4C6E8G0J2L", "name" to "linear", "url" to "https://mcp.linear.app/sse", "connected" to true, "owner" to json("type" to "personal")),
            json("id" to "mcp_01JX3Q5T7V9X1Z3B5D7F9H1K3M", "name" to "sentry", "url" to "https://mcp.sentry.dev/mcp", "connected" to false,
                "owner" to json("type" to "workspace", "workspaceName" to Settings.WORKSPACE_NAME)),
        )))
        "listGlobalPluginRepositories" -> ok(json("repositories" to listOf(
            repository("user", true, "https://ampcode.com/git/@devuser/-/plugins"),
            repository("workspace", false, "https://ampcode.com/git/@${Settings.WORKSPACE_NAME}/-/plugins"),
        )))
        "listGlobalSkillRepositories" -> ok(json("repositories" to listOf(
            repository("user", true, "https://ampcode.com/git/@devuser/-/skills"),
            repository("workspace", true, "https://ampcode.com/git/@${Settings.WORKSPACE_NAME}/-/skills"),
        )))
        "listCustomDomains" -> ok(json("domains" to if (params.string("target") == "user") listOf(domain("preview.example.dev")) else emptyList()))
        "updateProject" -> updateProject(params)
        "deleteProjectSandboxSnapshots" -> if (params.string("projectID") !in projects) {
            World.notFound("Project not found")
        } else {
            ok(json("deleted" to (snapshots > 0))).also { snapshots = 0 }
        }
        else -> null
    }

    private fun shareRunner(params: JsonObject): JsonObject {
        val runner = params.string("runnerId") ?: return invalid("runnerId is required")
        if (params.string("workspaceID") != Settings.WORKSPACE_ID) return World.notFound("Workspace not found")
        val existing = shares.firstOrNull { it.string("runnerId") == runner }
        val shareId = existing?.string("shareID") ?: UUID.randomUUID().toString().also { shares += share(it, runner) }
        return ok(json("shareID" to shareId))
    }

    private fun revokeRunnerShare(params: JsonObject): JsonObject {
        val removed = shares.removeAll { it.string("shareID") == params.string("shareID") }
        return if (removed) ok(json()) else World.notFound("Share not found")
    }

    private fun setEnvVarSecret(params: JsonObject): JsonObject {
        val name = params.string("name")?.takeIf { it.isNotEmpty() } ?: return invalid("name is required")
        if (params.string("value").isNullOrEmpty()) return invalid("value is required")
        val names = envVars.getOrPut(targetKey(params)) { linkedMapOf() }
        val created = name !in names
        val kind = params.string("kind") ?: names[name] ?: "secret"
        names[name] = kind
        return ok(json("name" to name, "kind" to kind, "created" to created))
    }

    private fun setDictationVocabulary(params: JsonObject): JsonObject {
        val terms = params.string("vocabulary") ?: return invalid("vocabulary is required")
        vocabulary = when (params.string("operation")) {
            "set" -> terms
            "add" -> listOf(vocabulary, terms).filter { it.isNotBlank() }.joinToString(", ")
            else -> return invalid("operation must be add or set")
        }
        return ok(json("vocabulary" to vocabulary))
    }

    private fun createClient(params: JsonObject): JsonObject {
        if (params.string("workspaceName") != Settings.WORKSPACE_NAME) return World.notFound("Workspace not found")
        val type = params.string("type")?.takeIf { it == "server-side" || it == "client-side" } ?: return invalid("type must be server-side or client-side")
        val name = params.string("name")?.takeIf { it.isNotEmpty() } ?: return invalid("name is required")
        val created = client(
            UUID.randomUUID().toString(),
            "client_" + UUID.randomUUID().toString().replace("-", "").take(26).uppercase(),
            name,
            params.array("scopes")?.map { (it as JsonPrimitive).content }.orEmpty(),
            params.array("redirectURIs")?.map { (it as JsonPrimitive).content }.orEmpty(),
            type,
        )
        clients += created
        val serverSide = type == "server-side"
        return ok(json(
            "client" to created,
            "clientSecret" to if (serverSide) "fake-oauth-secret-AMPCANARY-${UUID.randomUUID()}" else null,
            "clientSecretStatus" to if (serverSide) "created" else "not-applicable",
            "manageURL" to "https://ampcode.com/w/${Settings.WORKSPACE_NAME}/settings/oauth-clients",
        ))
    }

    private fun updateProject(params: JsonObject): JsonObject {
        val id = params.string("projectID")
        val current = projects[id] ?: return World.notFound("Project not found")
        val updated = current + listOfNotNull(
            params.string("changesWorkflow")?.let { "changesWorkflow" to element(it) },
            params.string("customShipPrompt")?.let { "customShipPrompt" to element(it) },
        ).toMap()
        projects[id!!] = updated
        return ok(updated)
    }

    private fun targetKey(params: JsonObject): String {
        val target = params.obj("target")
        return listOfNotNull(target?.string("type"), target?.string("projectID")).joinToString(":")
    }

    private fun ok(result: JsonObject) = json("ok" to true, "result" to result)

    private fun invalid(message: String) = json("ok" to false, "error" to json("code" to "invalid-params", "message" to message))

    private fun share(shareId: String, runner: String) = json("shareID" to shareId, "workspaceID" to Settings.WORKSPACE_ID, "runnerId" to runner)

    private fun envVar(name: String, kind: String) = json(
        "name" to name, "kind" to kind, "createdBy" to "devuser", "createdByUserID" to USER_ID,
        "updatedBy" to "devuser", "updatedByUserID" to USER_ID, "updatedAt" to "2026-10-20T15:04:00.000Z",
    )

    private fun client(id: String, clientId: String, name: String, scopes: List<String>, redirects: List<String>, type: String = "client-side") = json(
        "id" to id, "type" to type, "name" to name, "description" to null, "scopes" to scopes, "redirectURIs" to redirects,
        "clientID" to clientId, "createdAt" to "2026-10-01T08:30:00.000Z",
    )

    private fun repository(scope: String, exists: Boolean, cloneUrl: String) = json("scope" to scope, "exists" to exists, "cloneURL" to cloneUrl, "viewerCanWrite" to true)

    private fun domain(hostname: String) = json(
        "id" to "7e9b1d3f-5a7c-4e9b-8d1f-3a5c7e9b1d3f", "hostname" to hostname, "status" to "active",
        "verifiedAt" to "2026-10-02T11:00:00.000Z", "lastError" to null, "checkGeneration" to 3, "completedCheckGeneration" to 3,
        "lastCheckedAt" to "2026-10-22T11:00:00.000Z", "createdAt" to "2026-10-02T10:40:00.000Z", "updatedAt" to "2026-10-22T11:00:00.000Z",
        "caaPolicyError" to null, "includeHostnameRouting" to true,
        "dnsRecords" to listOf(json("type" to "CNAME", "name" to hostname, "value" to "sites.ampcode.com", "purpose" to "routing")),
    )

    private fun project() = json(
        "id" to Settings.PROJECT_ID, "owner" to json("type" to "workspace", "workspaceID" to Settings.WORKSPACE_ID),
        "name" to "amp-android", "namespace" to Settings.WORKSPACE_NAME, "description" to "Amp for Android", "iconURL" to null,
        "listedOnAmpStore" to false, "galleryMedia" to emptyList<Any>(), "repositoryURL" to "https://github.com/example/example-project",
        "repositoryMode" to "github", "remoteURLs" to emptyList<Any>(), "changesWorkflow" to "merge-to-main", "customShipPrompt" to "",
        "baseBranch" to "", "environmentResource" to "a1.medium",
        "url" to "https://ampcode.com/projects/${Settings.WORKSPACE_NAME}/amp-android",
    )
}
