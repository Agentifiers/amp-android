package com.agentifiers.amp.sync.internal.net

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug-only logs. `AgentNet` writes one line per finished HTTP exchange in the shape agent-device's
 * `network dump` parses (RESULTS.md S20). Only the content type is logged, so the Authorization header and
 * `x-rivet-conn-params` never reach logcat, and the Rivet token is cut from every URL.
 */
internal class NetLog(private val enabled: Boolean) {
    fun interceptor() = Interceptor { chain ->
        val request = chain.request()
        val started = System.nanoTime()
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            exchange(request.method, request.url, status = 0, contentType = null, started)
            throw e
        }
        exchange(request.method, request.url, response.code, response.header("content-type"), started)
        response
    }

    /** One socket frame, by type only. Plain text without braces, so the network parser skips it. */
    fun frame(direction: Char, type: String, detail: String = "") {
        if (enabled) Log.i(WS_TAG, if (detail.isEmpty()) "WS$direction $type" else "WS$direction $type $detail")
    }

    /** One relay frame by header only: [kind] is the frame kind or the JSON relay method. Never the payload. */
    fun relay(direction: Char, kind: String, flags: Int, channelId: String, length: Int) {
        if (enabled) Log.i(WS_TAG, "WS$direction relay kind=$kind flags=$flags ch=${"%08x".format(channelId.hashCode())} len=$length")
    }

    private fun exchange(method: String, url: HttpUrl, status: Int, contentType: String?, started: Long) {
        if (!enabled) return
        val line = buildJsonObject {
            put("method", method)
            put("url", redact(url))
            put("status", status)
            putJsonObject("headers") { contentType?.let { put("content-type", it) } }
        }
        Log.i(NET_TAG, "$line duration=${(System.nanoTime() - started) / 1_000_000}")
    }

    private fun redact(url: HttpUrl): String {
        if (url.querySize == 0) return url.toString()
        val builder = url.newBuilder().query(null)
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            builder.addQueryParameter(name, if (name in SECRET_PARAMS) "redacted" else url.queryParameterValue(i))
        }
        return builder.build().toString()
    }

    private companion object {
        const val NET_TAG = "AgentNet"
        const val WS_TAG = "AmpWs"
        val SECRET_PARAMS = setOf("rvt-token")
    }
}
