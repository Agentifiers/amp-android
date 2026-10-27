package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.sync.api.SignInFailure
import com.agentifiers.amp.sync.api.SignInStep
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The OAuth device grant (auth-and-security.md, "Device flow is the default"): authorize, show the code, then poll
 * every `interval` seconds until the user approves, refuses, or the code expires. A poll that cannot reach the
 * server is retried at the next interval, because the phone often changes networks while the user is in the browser.
 */
internal class DeviceFlow(private val calls: AuthCalls) {
    suspend fun run(server: ServerEnvironment, config: OAuthConfig, emit: suspend (SignInStep) -> Unit): Proof {
        val started = calls.form(config.apiUrl.under("user_management/authorize/device"), mapOf("client_id" to config.clientId))
        val body = started.body?.takeIf { started.status in 200..299 } ?: throw SignInStopped(SignInFailure.ServerRejected)
        val deviceCode = body.string("device_code") ?: throw SignInStopped(SignInFailure.ServerRejected)
        val userCode = body.string("user_code") ?: throw SignInStopped(SignInFailure.ServerRejected)
        val expiresIn = body.long("expires_in") ?: DEFAULT_EXPIRES_IN
        val page = config.deviceLoginPath?.let { path ->
            SignInFlows.baseOf(server).newBuilder().encodedPath(path).addQueryParameter("user_code", userCode).build().toString()
        } ?: body.string("verification_uri_complete")?.takeIf { it.toHttpUrlOrNull() != null } ?: throw SignInStopped(SignInFailure.ServerRejected)
        emit(SignInStep.ShowCode(userCode, page, expiresIn.toInt()))
        emit(SignInStep.Polling)

        val deadline = TimeSource.Monotonic.markNow() + expiresIn.seconds
        var interval = body.long("interval") ?: DEFAULT_INTERVAL
        var wait = interval
        val poll = mapOf(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to deviceCode,
            "client_id" to config.clientId,
        )
        while (true) {
            delay(wait.seconds)
            if (deadline.hasPassedNow()) throw SignInStopped(SignInFailure.Expired)
            val answer = try {
                DevicePoll.parse(calls.form(config.apiUrl.under("user_management/authenticate"), poll), calls.now())
            } catch (_: IOException) {
                wait = interval
                continue
            }
            wait = when (answer) {
                DevicePoll.Pending -> interval
                DevicePoll.SlowDown -> (interval + SLOW_DOWN_STEP).also { interval = it }
                is DevicePoll.RetryAfter -> maxOf(answer.seconds, interval)
                is DevicePoll.Tokens -> return Proof.OAuth(answer.grant, config)
                DevicePoll.Denied -> throw SignInStopped(SignInFailure.Denied)
                DevicePoll.Expired -> throw SignInStopped(SignInFailure.Expired)
                DevicePoll.Unknown -> throw SignInStopped(SignInFailure.ServerRejected)
            }
        }
    }

    private companion object {
        const val DEFAULT_EXPIRES_IN = 300L
        const val DEFAULT_INTERVAL = 5L
        const val SLOW_DOWN_STEP = 5L
    }
}
