package com.agentifiers.amp.fake

import com.agentifiers.amp.fake.scenarios.SigningInAuth
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class AuthScenarioTest {
    private val http = OkHttpClient.Builder().followRedirects(false).build()

    private fun call(server: FakeAmpServer, path: String, body: RequestBody? = null, bearer: String? = null): Pair<Int, JsonObject> {
        val request = Request.Builder().url(server.ampBaseUrl + path)
            .apply { if (body != null) post(body) }
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .build()
        return http.newCall(request).execute().use { it.code to (FakeJson.parseToJsonElement(it.body.string()) as JsonObject) }
    }

    private fun form(vararg fields: Pair<String, String>) = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()

    private fun jsonBody(body: JsonObject) = body.toString().toRequestBody("application/json".toMediaType())

    private fun emailOf(server: FakeAmpServer, token: String): Pair<Int, String?> {
        val (status, body) = call(server, "/api/internal?getUserInfo", jsonBody(json("method" to "getUserInfo", "params" to json())), token)
        return status to body.obj("result")?.string("email")
    }

    @Test
    fun `the device flow answers pending once, then approves a new user whose refresh token rotates`() = FakeAmp.start("auth").use { server ->
        val (_, config) = call(server, "/api/auth/oauth-config")
        assertEquals(server.ampBaseUrl, config.string("apiURL"))
        val clientId = config.string("clientID")!!
        assertEquals("client_01JNSEYM3V0J5AXK4YXNXRXTGP", clientId)

        val (_, device) = call(server, "/user_management/authorize/device", form("client_id" to clientId))
        assertEquals("GMKF-JRTX", device.string("user_code"))
        assertEquals(300L, device.long("expires_in"))
        assertEquals(5L, device.long("interval"))
        val poll = form("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to device.string("device_code")!!, "client_id" to clientId)

        val (pendingStatus, pending) = call(server, "/user_management/authenticate", poll)
        assertEquals(400 to "authorization_pending", pendingStatus to pending.string("error"))

        val (approvedStatus, tokens) = call(server, "/user_management/authenticate", poll)
        assertEquals(200, approvedStatus)
        assertEquals("fake-1@example.com", tokens.obj("user")!!.string("email"))
        assertEquals(3600L, tokens.long("expires_in"))
        val access = tokens.string("access_token")!!
        assertTrue("tokens carry the canary", "AMPCANARY" in access)
        assertEquals(200 to "fake-1@example.com", emailOf(server, access))
        val (_, grant) = call(server, "/api/user-actor-credentials", jsonBody(json("capability" to "read")), access)
        assertEquals("user_fake_1", grant.string("userId"))

        val oldRefresh = tokens.string("refresh_token")!!
        val (rotatedStatus, rotated) = call(server, "/user_management/authenticate", form("grant_type" to "refresh_token", "refresh_token" to oldRefresh, "client_id" to clientId))
        assertEquals(200, rotatedStatus)
        assertEquals(200 to "fake-1@example.com", emailOf(server, rotated.string("access_token")!!))
        val (reusedStatus, reused) = call(server, "/user_management/authenticate", form("grant_type" to "refresh_token", "refresh_token" to oldRefresh, "client_id" to clientId))
        assertEquals(400 to "invalid_grant", reusedStatus to reused.string("error"))
    }

    @Test
    fun `the browser flow redirects to the loopback callback and redeems the code with the PKCE verifier`() = FakeAmp.start("auth").use { server ->
        val verifier = "verifier-literal"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
        val authorize = Request.Builder().url("${server.ampBaseUrl}/auth/cli/authorize?challenge=$challenge&port=4567&state=s1&redeem=amp").build()
        val location = http.newCall(authorize).execute().use { it.code to it.header("Location") }
        assertEquals(302 to "http://127.0.0.1:4567/callback?code=amp-auth.1&state=s1", location)

        val redeem = json("code" to "amp-auth.1", "verifier" to verifier, "redirect_uri" to "http://127.0.0.1:4567/callback")
        val (status, tokens) = call(server, "/api/auth/cli-redeem", jsonBody(redeem))
        assertEquals(200 to "fake-1@example.com", status to tokens.obj("user")!!.string("email"))
        val (again, _) = call(server, "/api/auth/cli-redeem", jsonBody(redeem))
        assertEquals(400, again)
    }

    @Test
    fun `pasted fake-user tokens are their own user, and oauth-logout and the expiry hook turn tokens away`() = FakeAmp.start("auth").use { server ->
        assertEquals(200 to "fake-3@example.com", emailOf(server, "fake-user-3"))
        assertEquals(200 to "fake-amp@example.com", emailOf(server, "any-other-token"))

        val verifier = "v"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
        http.newCall(Request.Builder().url("${server.ampBaseUrl}/auth/cli/authorize?challenge=$challenge&port=1&state=s").build()).execute().close()
        val (_, first) = call(server, "/api/auth/cli-redeem", jsonBody(json("code" to "amp-auth.1", "verifier" to verifier, "redirect_uri" to "x")))
        val access = first.string("access_token")!!

        assertEquals(200, call(server, SigningInAuth.EXPIRE_ACCESS, form()).first)
        assertEquals(401 to null, emailOf(server, access))
        val (_, refreshed) = call(server, "/user_management/authenticate", form("grant_type" to "refresh_token", "refresh_token" to first.string("refresh_token")!!))
        val fresh = refreshed.string("access_token")!!
        assertEquals(200 to "fake-1@example.com", emailOf(server, fresh))

        assertEquals(200, call(server, "/api/auth/oauth-logout", jsonBody(json()), fresh).first)
        assertEquals(401 to null, emailOf(server, fresh))
        val (afterLogout, refusal) = call(server, "/user_management/authenticate", form("grant_type" to "refresh_token", "refresh_token" to refreshed.string("refresh_token")!!))
        assertEquals(400 to "invalid_grant", afterLogout to refusal.string("error"))
    }
}
