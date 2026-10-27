package com.agentifiers.amp.debug

import com.agentifiers.amp.sync.api.SignInResult
import org.json.JSONObject

/** `sign-in-token {token}`: signs in on the chosen server without the sign-in screen. The token is never logged. */
object SignInToken : AppEventHandler {
    override val name = "sign-in-token"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val token = payload.requireString("token")
        val server = app.server.current.value
        return when (val result = app.engines.signIn.withToken(server, token)) {
            is SignInResult.Accepted -> "signed in to ${server.ampBaseUrl} as ${result.account.raw}"
            is SignInResult.Rejected -> "rejected: ${result.reason}"
        }
    }
}
