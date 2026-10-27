package com.agentifiers.amp.sync.internal

import com.agentifiers.amp.fake.FakeAmp
import com.agentifiers.amp.fake.FakeAmpServer
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.CredentialKind
import com.agentifiers.amp.model.FleetView
import com.agentifiers.amp.sync.api.SignInFailure
import com.agentifiers.amp.sync.api.SignInResult
import com.agentifiers.amp.sync.api.SignInStep
import com.agentifiers.amp.sync.internal.push.PushRegistration
import com.agentifiers.amp.sync.internal.vault.StoredCredential
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Instant

class SignInTest {
    private val http = OkHttpClient.Builder().followRedirects(false).build()

    private val fleetBasicThreads = listOf("T-01a16702-3202-73fb-8f52-e4498ccd3721", "T-01a16738-2ec2-7354-98e9-688bc413f079", "T-01a166e9-3fa0-77ca-b925-383e88ba52ac")

    /** A request straight to the fake, as another device or the browser would make it. */
    private fun call(server: FakeAmpServer, path: String, body: RequestBody, bearer: String? = null): Pair<Int, JsonObject?> {
        val request = Request.Builder().url(server.ampBaseUrl + path).post(body).apply { bearer?.let { header("Authorization", "Bearer $it") } }.build()
        return http.newCall(request).execute().use { it.code to (AmpJson.parseToJsonElement(it.body.string()) as? JsonObject) }
    }

    private fun form(vararg fields: Pair<String, String>) = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()

    private fun empty() = "{}".toRequestBody("application/json".toMediaType())

    private fun emailBehind(server: FakeAmpServer, token: String): String? {
        val body = """{"method":"getUserInfo","params":{}}""".toRequestBody("application/json".toMediaType())
        return call(server, "/api/internal?getUserInfo", body, token).second?.obj("result")?.string("email")
    }

    private fun refreshAtFake(server: FakeAmpServer, refreshToken: String) =
        call(server, "/user_management/authenticate", form("grant_type" to "refresh_token", "refresh_token" to refreshToken))

    private fun expireAccessTokens(server: FakeAmpServer) = call(server, "/auth/fake/expire-access-tokens", form())

    private suspend fun EngineHarness.deviceSignIn(): AccountId = (signIn(engines.start(environment)).last() as SignInStep.SignedIn).account.id

    private suspend fun EngineHarness.oauth(id: AccountId) = vault.read(id) as StoredCredential.OAuth

    @Test
    fun `device flow shows the captured code, polls, confirms the approving user, and the account survives a restart`() = FakeAmp.start("auth").use { server ->
        runTest {
            val app = EngineHarness(server)
            app.ready()

            val steps = app.signIn(app.engines.start(app.environment))

            assertEquals(SignInStep.ShowCode("GMKF-JRTX", "${server.ampBaseUrl}/auth/cli/device?user_code=GMKF-JRTX", 300), steps[0])
            assertEquals(SignInStep.Polling, steps[1])
            val candidate = (steps[2] as SignInStep.Confirm).candidate
            assertEquals("fake-1@example.com" to CredentialKind.OAuth, candidate.email to candidate.credential)
            val account = (steps[3] as SignInStep.SignedIn).account
            assertEquals(4, steps.size)
            assertEquals("fake-1@example.com", emailBehind(server, app.oauth(account.id).accessToken))

            app.restart()
            val roster = app.ready()
            assertEquals(listOf("fake-1@example.com" to AuthHealth.Ok), roster.accounts.map { it.email to it.auth })
            assertEquals(account.id, roster.lastUsed)
        }
    }

    @Test
    fun `browser sign-in redirects to the loopback listener, which answers the browser and redeems the code`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            val browser = OkHttpClient()
            var page: Pair<Int, String>? = null
            val visits = mutableListOf<Job>()

            val steps = app.signIn(app.engines.browser(app.environment)) { step ->
                if (step is SignInStep.OpenBrowser) {
                    visits += launch(Dispatchers.IO) { page = browser.newCall(Request.Builder().url(step.url).build()).execute().use { it.code to it.body.string() } }
                }
            }
            visits.joinAll()

            val authorize = (steps[0] as SignInStep.OpenBrowser).url
            assertTrue("authorize URL $authorize", authorize.startsWith("${server.ampBaseUrl}/auth/cli/authorize?challenge=") && authorize.endsWith("&redeem=amp"))
            assertEquals("fake-1@example.com", (steps[1] as SignInStep.Confirm).candidate.email)
            assertEquals("fake-1@example.com", (steps[2] as SignInStep.SignedIn).account.email)
            assertEquals(200, page?.first)
            assertTrue("callback page", page!!.second.contains("You can close this tab and return to Amp."))
        }
    }

    @Test
    fun `a pasted token confirms its user once, refuses a second copy, and a cancel stores nothing`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()

            val first = app.signIn(app.engines.paste(app.environment, " fake-user-3 "))
            val candidate = (first[0] as SignInStep.Confirm).candidate
            assertEquals("fake-3@example.com" to CredentialKind.AccessToken, candidate.email to candidate.credential)
            assertEquals("fake-3@example.com", (first[1] as SignInStep.SignedIn).account.email)

            val again = app.signIn(app.engines.paste(app.environment, "fake-user-3"))
            assertEquals(SignInStep.Failed(SignInFailure.AlreadySignedIn), again[1])

            val cancelled = app.signIn(app.engines.paste(app.environment, "fake-user-4"), add = false)
            assertEquals(1, cancelled.size)
            assertEquals(listOf("fake-3@example.com"), app.ready().accounts.map { it.email })
            assertEquals(listOf(SignInStep.Failed(SignInFailure.TokenRejected)), app.signIn(app.engines.paste(app.environment, "two words")))
        }
    }

    @Test
    fun `sign-out revokes the session and wipes the account, so its old token is rejected`() = FakeAmp.start("auth").use { server ->
        runTest {
            val app = EngineHarness(server)
            app.ready()
            val id = app.deviceSignIn()
            val stored = app.oauth(id)
            val images = File(app.imageCache, id.raw).apply { mkdirs() }
            File(images, "cached.png").writeText("png")
            app.pushSecrets.getValue(id).saveRegistration(PushRegistration("https://fcm.googleapis.com/fcm/send/device-1", null))

            app.engines.signOut(id)

            assertEquals(emptyList<String>(), app.ready().accounts.map { it.email })
            assertEquals(emptyList<String>(), app.prefs.roster.data.first().accounts.map { it.id })
            assertEquals(null, app.vault.read(id))
            assertEquals(listOf(id), app.databases.deleted)
            assertFalse("image cache deleted", images.exists())
            assertEquals(null, app.pushSecrets.getValue(id).registration())
            assertEquals(emptyMap<AccountId, Any>(), app.vault.pendingRevocations())
            assertThrows(IllegalArgumentException::class.java) { app.engines.scope(id) }
            assertEquals(400 to "invalid_grant", refreshAtFake(server, stored.refreshToken).let { (status, body) -> status to body?.string("error") })
            assertEquals(listOf(SignInStep.Failed(SignInFailure.TokenRejected)), app.signIn(app.engines.paste(app.environment, stored.accessToken)))
        }
    }

    @Test
    fun `sign-out offline with an expired access token keeps the refresh token to revoke at the next start`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            val id = app.deviceSignIn()
            val stored = app.oauth(id)
            app.vault.write(id, StoredCredential.OAuth(stored.accessToken, stored.refreshToken, Instant.fromEpochSeconds(0), stored.apiUrl, stored.clientId))
            app.restart()
            app.ready()
            server.close()

            app.engines.signOut(id)

            assertEquals(mapOf(id to stored.refreshToken), app.vault.pendingRevocations().mapValues { it.value.refreshToken })
            assertEquals(null, app.vault.read(id))
            assertEquals(emptyList<String>(), app.ready().accounts.map { it.email })
        }
    }

    @Test
    fun `a 401 refreshes the OAuth token, rotates the refresh token and retries the request once`() = FakeAmp.start("auth").use { server ->
        runTest {
            val app = EngineHarness(server)
            app.ready()
            val id = app.deviceSignIn()
            val before = app.oauth(id)
            assertEquals(200, expireAccessTokens(server).first)

            val fleet = app.engines.scope(id).fleet
            fleet.refresh()
            val view = fleet.threads.first { it !is FleetView.Loading }

            assertEquals(fleetBasicThreads, (view as FleetView.Ready).threads.map { it.id.raw })
            val after = app.oauth(id)
            assertEquals("fake-1@example.com", emailBehind(server, after.accessToken))
            assertEquals(null, emailBehind(server, before.accessToken))
            assertEquals(400 to "invalid_grant", refreshAtFake(server, before.refreshToken).let { (status, body) -> status to body?.string("error") })
            assertEquals(AuthHealth.Ok, app.ready().accounts.single().auth)
        }
    }

    @Test
    fun `invalid_grant on refresh marks the account NeedsSignIn and keeps it and its credential`() = FakeAmp.start("auth").use { server ->
        runTest {
            val app = EngineHarness(server)
            app.ready()
            val id = app.deviceSignIn()
            val stored = app.oauth(id)
            assertEquals(200, refreshAtFake(server, stored.refreshToken).first)
            expireAccessTokens(server)

            app.engines.scope(id).fleet.refresh()
            val roster = app.rosterWhere { it.accounts.single().auth == AuthHealth.NeedsSignIn }

            assertEquals(listOf("fake-1@example.com"), roster.accounts.map { it.email })
            assertEquals(AuthHealth.NeedsSignIn, app.engines.scope(id).account.value.auth)
            assertEquals(stored.refreshToken, app.oauth(id).refreshToken)
            assertEquals(listOf(true), app.prefs.roster.data.first().accounts.map { it.needsSignIn })
        }
    }

    @Test
    fun `a pasted token Amp stops accepting needs sign-in, and signing in again as the same user restores the account`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            val verifier = "verifier-literal"
            val authorize = "${server.ampBaseUrl}/auth/cli/authorize?challenge=Yer6zEQXEDrxKa3fBatowDrCs-hL7zmrY8d7zfUPWoo&port=9&state=s&redeem=amp"
            http.newCall(Request.Builder().url(authorize).build()).execute().close()
            val redeem = """{"code":"amp-auth.1","verifier":"$verifier","redirect_uri":"x"}""".toRequestBody("application/json".toMediaType())
            val minted = call(server, "/api/auth/cli-redeem", redeem).second!!.string("access_token")!!

            val id = (app.signIn(app.engines.paste(app.environment, minted)).last() as SignInStep.SignedIn).account.id
            assertEquals(200, call(server, "/api/auth/oauth-logout", empty(), minted).first)
            val scope = app.engines.scope(id)
            scope.fleet.refresh()
            app.rosterWhere { it.accounts.single().auth == AuthHealth.NeedsSignIn }

            val again = app.signIn(app.engines.paste(app.environment, "fake-user-1"))
            assertEquals(id, (again.last() as SignInStep.SignedIn).account.id)
            assertEquals(listOf(id to AuthHealth.Ok), app.ready().accounts.map { it.id to it.auth })
            val view = scope.fleet.threads.first { it is FleetView.Ready }
            assertEquals(fleetBasicThreads, (view as FleetView.Ready).threads.map { it.id.raw })
        }
    }

    @Test
    fun `five accounts is the limit for every method, and withToken accepts the same account again`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            val ids = (1..5).map { n -> (app.engines.withToken(app.environment, "fake-user-$n") as SignInResult.Accepted).account }

            assertEquals(SignInResult.Accepted(ids[1]), app.engines.withToken(app.environment, "fake-user-2"))
            assertEquals(SignInResult.Rejected("You can keep up to 5 accounts signed in."), app.engines.withToken(app.environment, "fake-user-6"))
            assertEquals(listOf(SignInStep.Failed(SignInFailure.TooManyAccounts)), app.signIn(app.engines.start(app.environment)))
            assertEquals(listOf(SignInStep.Failed(SignInFailure.TooManyAccounts)), app.signIn(app.engines.browser(app.environment)))
            assertEquals(listOf(SignInStep.Failed(SignInFailure.TooManyAccounts)), app.signIn(app.engines.paste(app.environment, "fake-user-6")))
            val roster = app.ready()
            assertEquals((1..5).map { "fake-$it@example.com" }, roster.accounts.map { it.email })
            assertEquals(ids[4], roster.lastUsed)
        }
    }

    @Test
    fun `at five accounts one that needs sign-in signs in again, and a sixth is still refused`() = FakeAmp.start("auth").use { server ->
        runBlocking {
            val app = EngineHarness(server)
            app.ready()
            val authorize = "${server.ampBaseUrl}/auth/cli/authorize?challenge=Yer6zEQXEDrxKa3fBatowDrCs-hL7zmrY8d7zfUPWoo&port=9&state=s&redeem=amp"
            http.newCall(Request.Builder().url(authorize).build()).execute().close()
            val redeem = """{"code":"amp-auth.1","verifier":"verifier-literal","redirect_uri":"x"}""".toRequestBody("application/json".toMediaType())
            val minted = call(server, "/api/auth/cli-redeem", redeem).second!!.string("access_token")!!
            val first = (app.engines.withToken(app.environment, minted) as SignInResult.Accepted).account
            (2..5).forEach { n -> app.engines.withToken(app.environment, "fake-user-$n") }
            call(server, "/api/auth/oauth-logout", empty(), minted)
            app.engines.scope(first).fleet.refresh()
            app.rosterWhere { roster -> roster.accounts.any { it.auth == AuthHealth.NeedsSignIn } }

            val sixth = app.signIn(app.engines.paste(app.environment, "fake-user-6"))
            val again = app.signIn(app.engines.paste(app.environment, "fake-user-1"))

            assertEquals(listOf("Confirm", "Failed"), sixth.map { it::class.simpleName })
            assertEquals(SignInStep.Failed(SignInFailure.TooManyAccounts), sixth.last())
            assertEquals(first, (again.last() as SignInStep.SignedIn).account.id)
            assertEquals((1..5).map { "fake-$it@example.com" to AuthHealth.Ok }, app.ready().accounts.map { it.email to it.auth })
        }
    }
}
