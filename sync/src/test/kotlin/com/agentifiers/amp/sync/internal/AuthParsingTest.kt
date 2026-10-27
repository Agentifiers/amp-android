package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.UserId
import com.agentifiers.amp.sync.internal.auth.AuthReply
import com.agentifiers.amp.sync.internal.auth.DevicePoll
import com.agentifiers.amp.sync.internal.auth.OAuthConfig
import com.agentifiers.amp.sync.internal.auth.TokenGrant
import com.agentifiers.amp.sync.internal.auth.accountIdOf
import com.agentifiers.amp.sync.internal.wire.AmpJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import kotlin.time.Instant

class AuthParsingTest {
    private fun json(text: String) = AmpJson.parseToJsonElement(text) as JsonObject

    private fun config(text: String, allowLocalHttp: Boolean) = OAuthConfig.parse(json(text), allowLocalHttp)?.let {
        listOf(it.clientId, it.apiUrl.toString(), it.deviceLoginPath, it.cliCodeRedemption)
    }

    @Test
    fun capturedOAuthConfigIsAnAmpServer() {
        val captured = """{"clientID":"client_01JNSEYM3V0J5AXK4YXNXRXTGP","apiURL":"https://authapi.ampcode.com","defaultLoginMethod":"oauth","deviceLoginPath":"/auth/cli/device","cliCodeRedemption":true}"""
        assertEquals(listOf("client_01JNSEYM3V0J5AXK4YXNXRXTGP", "https://authapi.ampcode.com/", "/auth/cli/device", true), config(captured, allowLocalHttp = false))
    }

    @Test
    fun oauthConfigWithoutClientIdOrWithPlainHttpOffLocalhostIsNotAnAmpServer() {
        assertEquals(null, config("""{"apiURL":"https://authapi.ampcode.com"}""", allowLocalHttp = true))
        assertEquals(null, config("""{"clientID":"c","apiURL":"http://auth.example.com"}""", allowLocalHttp = true))
        assertEquals(null, config("""{"clientID":"c","apiURL":"http://10.0.2.2:8080"}""", allowLocalHttp = false))
        assertEquals(listOf("c", "http://10.0.2.2:8080/", null, false), config("""{"clientID":"c","apiURL":"http://10.0.2.2:8080"}""", allowLocalHttp = true))
    }

    @Test
    fun accountIdIsTheServerAndUserDigest() {
        assertEquals(AccountId("A-f964343d99d5ee6e"), accountIdOf(ServerEnvironment.Production, UserId("user_01EXAMPLE00000000000000000")))
    }

    @Test
    fun expiryComesFromExpiresInThenTheJwtExpClaim() {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1800000000,"sub":"u"}""".encodeToByteArray())
        val jwt = "eyJhbGciOiJIUzI1NiJ9.$claims.c2ln"
        assertEquals(Instant.fromEpochSeconds(1_700_003_600), TokenGrant.expiryOf(jwt, 3600, now))
        assertEquals(Instant.fromEpochSeconds(1_800_000_000), TokenGrant.expiryOf(jwt, null, now))
        assertEquals(null, TokenGrant.expiryOf("sgamp_user_opaque", null, now))
    }

    @Test
    fun devicePollAnswersAreReadFromStatusAndError() {
        val now = Instant.fromEpochSeconds(0)
        assertEquals(DevicePoll.Pending, DevicePoll.parse(AuthReply(400, json("""{"error":"authorization_pending"}"""), null), now))
        assertEquals(DevicePoll.SlowDown, DevicePoll.parse(AuthReply(400, json("""{"error":"slow_down"}"""), null), now))
        assertEquals(DevicePoll.Denied, DevicePoll.parse(AuthReply(400, json("""{"error":"access_denied"}"""), null), now))
        assertEquals(DevicePoll.Expired, DevicePoll.parse(AuthReply(400, json("""{"error":"expired_token"}"""), null), now))
        assertEquals(DevicePoll.RetryAfter(7), DevicePoll.parse(AuthReply(503, null, 7), now))
        assertEquals(DevicePoll.Unknown, DevicePoll.parse(AuthReply(400, json("""{"error":"invalid_client"}"""), null), now))
        val tokens = DevicePoll.parse(AuthReply(200, json("""{"access_token":"a","refresh_token":"r","expires_in":60,"user":{"email":"e@x.com"}}"""), null), now)
        val grant = (tokens as DevicePoll.Tokens).grant
        assertEquals(listOf("a", "r", Instant.fromEpochSeconds(60), "e@x.com"), listOf(grant.accessToken, grant.refreshToken, grant.expiresAt, grant.email))
    }
}
