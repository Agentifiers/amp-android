package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.net.URLEncoder
import java.util.UUID

/**
 * The few ampcode.com web app routes the app's WebView profile reaches: the sign-in page (`/auth/sign-in?returnTo=`),
 * which sets a session cookie; the Orbs settings page it returns to; and `/__data.json` for `/`, served to that cookie
 * only and 401 without it. Node order follows the web route `/(app)`: root layout, app layout, page (web entry
 * `3dae27c6.js`). The root layout carries `user` and the app layout `orbProviderOptions` among other keys; a node the
 * mask leaves out is a `skip`.
 */
internal class WebApp(private val web: FakeWeb, private val providers: List<FakeOrbProvider>) {
    private val sessions = HashSet<String>()
    private var layoutReads = 0

    fun route(request: RecordedRequest): MockResponse? {
        val path = request.url.encodedPath
        val signedIn = sessionOf(request) in sessions
        return when {
            path == SIGN_IN && request.method == "POST" -> signIn(request.url.queryParameter("returnTo"))
            path == SIGN_IN -> html("Sign in", signInForm(request.url.queryParameter("returnTo") ?: "/"))
            path == ORBS && !signedIn -> redirect("$SIGN_IN?returnTo=${URLEncoder.encode(ORBS, "UTF-8")}")
            path == ORBS -> html("Orbs", providers.filter { it.listed }.joinToString("") { "<li>${it.name}</li>" }.let { "<h1>Orbs</h1><ul>$it</ul>" })
            path == DATA -> layoutData(request.url.queryParameter("x-sveltekit-invalidated"), signedIn)
            else -> null
        }
    }

    private fun signIn(returnTo: String?): MockResponse {
        val session = UUID.randomUUID().toString()
        sessions += session
        val target = returnTo?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
        return MockResponse.Builder().code(303).setHeader("Location", target)
            .setHeader("Set-Cookie", "$COOKIE=$session; Path=/; HttpOnly; SameSite=Lax").build()
    }

    private fun layoutData(mask: String?, signedIn: Boolean): MockResponse {
        if (!signedIn) {
            return MockResponse.Builder().code(401).setHeader("Content-Type", "application/json").body("""{"message":"Authentication required"}""").build()
        }
        layoutReads++
        if (layoutReads > web.layoutReads) {
            return MockResponse.Builder().code(404).setHeader("Content-Type", "application/json").body("""{"message":"Not Found"}""").build()
        }
        val root = json("user" to json("id" to USER_ID, "username" to "fake-1"), "theme" to "dark")
        val app = json(
            "deviceAccounts" to listOf(json("email" to "fake-1@example.com")),
            "orbProviderOptions" to providers.filter { it.listed }.map(::option),
        )
        val nodes = listOf(root, app, null).mapIndexed { index, data ->
            if (data != null && mask?.getOrNull(index) == '1') json("type" to "data", "data" to flatten(data), "uses" to json()) else json("type" to "skip")
        }
        val body = FakeJson.encodeToString(JsonElement.serializer(), json("type" to "data", "nodes" to nodes)) + "\n"
        return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
    }

    private fun option(provider: FakeOrbProvider) = json(
        "configID" to provider.configID, "name" to provider.name, "isDefault" to provider.isDefault, "owner" to provider.owner, "activeThreadCount" to 0,
    )

    private fun sessionOf(request: RecordedRequest): String? = request.headers.values("Cookie").flatMap { it.split(';') }
        .map { it.trim() }.firstOrNull { it.startsWith("$COOKIE=") }?.removePrefix("$COOKIE=")

    private fun redirect(location: String) = MockResponse.Builder().code(303).setHeader("Location", location).build()

    private fun html(title: String, body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "text/html; charset=utf-8")
        .body("<!doctype html><html><head><title>$title</title></head><body>$body</body></html>").build()

    private fun signInForm(returnTo: String) =
        "<h1>Sign in to Amp</h1><form method=\"post\" action=\"$SIGN_IN?returnTo=${URLEncoder.encode(returnTo, "UTF-8")}\">" +
            "<button id=\"$SIGN_IN_BUTTON\" type=\"submit\">Sign In</button></form>"

    internal companion object {
        const val SIGN_IN = "/auth/sign-in"
        const val ORBS = "/settings/orbs"
        const val DATA = "/__data.json"
        const val COOKIE = "fake-amp-web-session"
        const val SIGN_IN_BUTTON = "fake-web-sign-in"

        /** devalue's `stringify` without its deduplication of equal values: slot 0 is the root, containers hold slot indices. */
        fun flatten(root: JsonElement): JsonArray {
            val slots = ArrayList<JsonElement>()
            fun put(value: JsonElement): Int {
                val index = slots.size
                slots += JsonNull
                slots[index] = when (value) {
                    is JsonObject -> JsonObject(value.mapValues { (_, child) -> JsonPrimitive(put(child)) })
                    is JsonArray -> JsonArray(value.map { JsonPrimitive(put(it)) })
                    else -> value
                }
                return index
            }
            put(root)
            return JsonArray(slots)
        }
    }
}
