package com.agentifiers.amp.sync.internal.push

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * The relay API from `docs/design/push-relay.md`, served by [LocalRelay]: a local stand-in with the same routes,
 * statuses and hashing rules as `relay/src/index.ts`.
 */
class PushRelayClientTest {
    private val relay = LocalRelay()
    private val server = MockWebServer()
    private lateinit var client: PushRelayClient

    @Before
    fun start() {
        server.dispatcher = relay
        server.start()
        client = PushRelayClient(OkHttpClient(), server.url("/"))
    }

    @After
    fun stop() = server.close()

    @Test
    fun registerReturnsAPushUrlWhoseCapabilityHashesToTheRid() = runTest {
        val registration = (client.register("fcm-token-a") as RelayAnswer.Ok).value
        assertTrue(registration.pushUrl.startsWith(server.url("/v1/push/").toString()))
        assertEquals(registration.rid, PushRelayClient.ridOf(registration.pushUrl))
        assertEquals("fcm-token-a", relay.tokens[registration.rid])
    }

    @Test
    fun updateMovesTheRowToTheNewTokenAndAWrongSecretIsUnauthorized() = runTest {
        val registration = (client.register("fcm-token-a") as RelayAnswer.Ok).value
        assertEquals(RelayAnswer.Ok(Unit), client.update(registration, "fcm-token-b"))
        assertEquals("fcm-token-b", relay.tokens[registration.rid])
        assertEquals(RelayAnswer.Unauthorized, client.update(registration.copy(secret = "not-the-secret"), "fcm-token-c"))
        assertEquals("fcm-token-b", relay.tokens[registration.rid])
    }

    @Test
    fun deleteIsIdempotentAndAnUpdateAfterItIsGone() = runTest {
        val registration = (client.register("fcm-token-a") as RelayAnswer.Ok).value
        assertEquals(RelayAnswer.Ok(Unit), client.delete(registration))
        assertEquals(null, relay.tokens[registration.rid])
        assertEquals(RelayAnswer.Ok(Unit), client.delete(registration))
        assertEquals(RelayAnswer.Gone, client.update(registration, "fcm-token-b"))
    }

    @Test
    fun ensureRegistersAgainWhenTheRelayAnswers410() = runTest {
        val first = (client.ensure(null, "fcm-token-a") as RelayAnswer.Ok).value
        assertEquals(first, (client.ensure(first, "fcm-token-a") as RelayAnswer.Ok).value)
        relay.goneStatus = 410
        relay.forget(first.rid)
        val second = (client.ensure(first, "fcm-token-b") as RelayAnswer.Ok).value
        assertNotEquals(first.pushUrl, second.pushUrl)
        assertEquals("fcm-token-b", relay.tokens[second.rid])
    }

    @Test
    fun aRidThatDoesNotMatchThePushUrlIsRejected() = runTest {
        relay.lieAboutRid = true
        assertEquals(RelayAnswer.Failed("the relay's rid does not match its push URL"), client.register("fcm-token-a"))
    }

    @Test
    fun theRidOfTheRelayReadmeIsUnpaddedBase64UrlSha256OfTheCapability() {
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", PushRelayClient.ridOf("https://relay.example/v1/push/abc"))
    }

    private class LocalRelay : Dispatcher() {
        val tokens = HashMap<String, String>()
        private val secrets = HashMap<String, String>()
        var goneStatus = 404
        var lieAboutRid = false
        private var counter = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val segments = request.url.pathSegments
            val body = request.body?.utf8().orEmpty()
            val token = Regex("\"fcmToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return when {
                request.method == "POST" && segments == listOf("v1", "registrations") -> {
                    token ?: return reply(400)
                    val capability = "capability-${counter++}"
                    val rid = if (lieAboutRid) "not-the-hash" else hash(capability)
                    tokens[rid] = token
                    secrets[rid] = "secret-$capability"
                    reply(201, """{"rid":"$rid","pushUrl":"${request.url.newBuilder().encodedPath("/v1/push/$capability").build()}","secret":"secret-$capability"}""")
                }
                segments.size == 3 && segments[1] == "registrations" -> {
                    val rid = segments[2]
                    val secret = secrets[rid] ?: return reply(goneStatus)
                    if (request.headers["Authorization"] != "Bearer $secret") return reply(401)
                    when (request.method) {
                        "PUT" -> {
                            tokens[rid] = token ?: return reply(400)
                            reply(204)
                        }
                        "DELETE" -> {
                            tokens.remove(rid)
                            secrets.remove(rid)
                            reply(204)
                        }
                        else -> reply(405)
                    }
                }
                else -> reply(404)
            }
        }

        /** What the relay does when FCM reports the token UNREGISTERED: the row is deleted. */
        fun forget(rid: String) {
            tokens.remove(rid)
            secrets.remove(rid)
        }

        private fun reply(code: Int, json: String = "") = MockResponse.Builder().code(code).body(json).build()

        private fun hash(capability: String) =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(capability.encodeToByteArray()))
    }
}
