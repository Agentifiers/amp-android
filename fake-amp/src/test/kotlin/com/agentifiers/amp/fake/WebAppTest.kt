package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class WebAppTest {
    private val http = OkHttpClient.Builder().followRedirects(false).build()
    private val layout = "/__data.json?x-sveltekit-trailing-slash=1&x-sveltekit-invalidated=110"

    private fun get(server: FakeAmpServer, path: String, cookie: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(server.ampBaseUrl + path).apply { cookie?.let { header("Cookie", it) } }.build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun signIn(server: FakeAmpServer): String {
        val request = Request.Builder().url(server.ampBaseUrl + "/auth/sign-in?returnTo=%2Fsettings%2Forbs").post(FormBody.Builder().build()).build()
        return http.newCall(request).execute().use { response ->
            assertEquals(303 to "/settings/orbs", response.code to response.header("Location"))
            response.header("Set-Cookie")!!.substringBefore(';')
        }
    }

    private fun nodeValue(body: String, node: Int, key: String): String {
        val slots = Wire.parse(body)["nodes"]!!.jsonArray[node].jsonObject["data"]!!.jsonArray
        fun at(index: Int): JsonElement = when (val slot = slots[index]) {
            is JsonObject -> JsonObject(slot.mapValues { (_, child) -> at(child.jsonPrimitive.int) })
            is JsonArray -> JsonArray(slot.map { at(it.jsonPrimitive.int) })
            else -> slot
        }
        return at(0).jsonObject[key].toString()
    }

    @Test
    fun `page data without the web session cookie answers 401`() = FakeAmp.start("newthread-self-hosted-web").use { server ->
        assertEquals(401 to """{"message":"Authentication required"}""", get(server, layout))
    }

    @Test
    fun `the root layout names the user and the app layout lists providers to the signed-in cookie`() = FakeAmp.start("newthread-self-hosted-web").use { server ->
        val (status, body) = get(server, layout, signIn(server))

        assertEquals(200, status)
        assertEquals("""{"type":"skip"}""", Wire.parse(body)["nodes"]!!.jsonArray[2].toString())
        assertEquals("""{"id":"user_01EXAMPLE00000000000000000","username":"fake-1"}""", nodeValue(body, 0, "user"))
        assertEquals(
            """[{"configID":"configID-00000001","name":"example-orbs-1","isDefault":true,"owner":"workspace","activeThreadCount":0},""" +
                """{"configID":"configID-00000002","name":"example-orbs-2","isDefault":false,"owner":"personal","activeThreadCount":0}]""",
            nodeValue(body, 1, "orbProviderOptions"),
        )
    }

    @Test
    fun `after its first read the gone scenario answers the layout with 404`() = FakeAmp.start("newthread-self-hosted-web-gone").use { server ->
        val cookie = signIn(server)

        assertEquals(200, get(server, layout, cookie).first)
        assertEquals(404 to """{"message":"Not Found"}""", get(server, layout, cookie))
    }

    @Test
    fun `the Orbs page sends a signed-out web view to the sign-in page`() = FakeAmp.start("newthread-self-hosted-web").use { server ->
        val answer = http.newCall(Request.Builder().url(server.ampBaseUrl + "/settings/orbs").build()).execute().use { it.code to it.header("Location") }

        assertEquals(303 to "/auth/sign-in?returnTo=%2Fsettings%2Forbs", answer)
    }
}
