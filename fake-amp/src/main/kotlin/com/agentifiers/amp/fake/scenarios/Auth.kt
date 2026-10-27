package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.AuthReply
import com.agentifiers.amp.fake.AuthRequest
import com.agentifiers.amp.fake.CapturedAuth
import com.agentifiers.amp.fake.FakeAuth
import com.agentifiers.amp.fake.FakeIdentity
import com.agentifiers.amp.fake.FakeJson
import com.agentifiers.amp.fake.Scenario
import com.agentifiers.amp.fake.ScenarioSpec
import com.agentifiers.amp.fake.capturedAuthBody
import com.agentifiers.amp.fake.element
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.plus
import com.agentifiers.amp.fake.string
import kotlinx.serialization.json.JsonObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * FleetBasic's threads behind a server that signs people in: the device flow answers pending once and then
 * approves, the browser flow redirects straight back with a code, and every approval is a new user
 * (`user_fake_<n>`, `fake-<n>@example.com`). Refresh tokens rotate, and a reused one is `invalid_grant`.
 */
internal object Auth : ScenarioSpec {
    override val name = "auth"

    override fun build(): Scenario = Scenario(FleetBasic.build().threads, auth = SigningInAuth())
}

/** Runs on the World loop, so its maps need no locking. */
internal class SigningInAuth : FakeAuth {
    private var approvals = 0
    private val devicePolls = HashMap<String, Int>()
    private val browserCodes = HashMap<String, Pair<String, Int>>()
    private val access = HashMap<String, Int>()
    private val refresh = HashMap<String, Int>()
    private val pairOf = HashMap<String, String>()
    private val rejected = HashSet<String>()

    override fun identityOf(token: String): FakeIdentity? {
        if (token.isBlank() || token in rejected || token in refresh) return null
        access[token]?.let { return identity(it) }
        PASTED.matchEntire(token)?.let { return identity(it.groupValues[1].toInt()) }
        return CapturedAuth.identity
    }

    override fun route(request: AuthRequest, origin: String): AuthReply? = when (request.path) {
        "/api/auth/oauth-config" -> AuthReply(200, capturedAuthBody("oauth-config", origin) + mapOf("apiURL" to element(origin)))
        "/user_management/authorize/device" -> {
            val deviceCode = "fake-device-AMPCANARY-${UUID.randomUUID()}"
            devicePolls[deviceCode] = 0
            AuthReply(200, capturedAuthBody("device-authorize", origin) + mapOf("device_code" to element(deviceCode)))
        }
        "/user_management/authenticate" -> authenticate(form(request.body), origin)
        "/auth/cli/authorize" -> {
            val code = "amp-auth.${++approvals}"
            browserCodes[code] = request.query("challenge").orEmpty() to approvals
            val callback = "http://127.0.0.1:${request.query("port")}/callback?code=$code&state=${request.query("state")}"
            AuthReply(302, json(), mapOf("Location" to callback))
        }
        "/api/auth/cli-redeem" -> {
            val body = runCatching { FakeJson.parseToJsonElement(request.body) as JsonObject }.getOrNull()
            val (challenge, n) = browserCodes.remove(body?.string("code")) ?: return invalidGrant()
            if (challengeOf(body?.string("verifier").orEmpty()) != challenge) return invalidGrant()
            AuthReply(200, grant(n))
        }
        "/api/auth/cli-complete" -> AuthReply(200, json())
        "/api/auth/oauth-logout" -> {
            val token = request.bearer
            if (token == null || identityOf(token) == null) return AuthReply(401, json("error" to "Unauthorized"))
            rejected += token
            pairOf[token]?.let { refresh.remove(it) }
            AuthReply(200, json())
        }
        EXPIRE_ACCESS -> {
            rejected += access.keys
            AuthReply(200, json("expired" to access.size))
        }
        else -> null
    }

    private fun authenticate(fields: Map<String, String>, origin: String): AuthReply = when (fields["grant_type"]) {
        "urn:ietf:params:oauth:grant-type:device_code" -> {
            val code = fields["device_code"]
            val polls = devicePolls[code]
            when {
                polls == null -> invalidGrant()
                polls == 0 -> {
                    devicePolls[code!!] = 1
                    AuthReply(400, capturedAuthBody("device-poll-once", origin))
                }
                else -> {
                    devicePolls.remove(code)
                    AuthReply(200, grant(++approvals))
                }
            }
        }
        "refresh_token" -> refresh.remove(fields["refresh_token"])?.let { AuthReply(200, grant(it)) } ?: invalidGrant()
        else -> AuthReply(400, json("error" to "unsupported_grant_type"))
    }

    private fun grant(n: Int): JsonObject {
        val accessToken = "fake-access-AMPCANARY-${UUID.randomUUID()}"
        val refreshToken = "fake-refresh-AMPCANARY-${UUID.randomUUID()}"
        access[accessToken] = n
        refresh[refreshToken] = n
        pairOf[accessToken] = refreshToken
        return json(
            "access_token" to accessToken,
            "refresh_token" to refreshToken,
            "expires_in" to 3600,
            "user" to json("email" to identity(n).email),
        )
    }

    private fun invalidGrant() = AuthReply(400, json("error" to "invalid_grant", "error_description" to "The grant is invalid or was already used."))

    private fun form(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))

    companion object {
        /** Test control: every access token minted so far answers 401, while refresh tokens keep working. */
        const val EXPIRE_ACCESS = "/auth/fake/expire-access-tokens"

        private val PASTED = Regex("^fake-user-(\\d+)$")

        fun identity(n: Int) = FakeIdentity("user_fake_$n", "fake-$n@example.com", "fake-$n", "Fake $n")
    }
}
