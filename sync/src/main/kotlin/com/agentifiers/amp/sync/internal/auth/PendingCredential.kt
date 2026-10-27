package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.UserId
import kotlin.time.Instant

/** A credential a sign-in method proved and the identity behind it, held in memory until the user confirms. */
internal sealed interface PendingCredential {
    val server: ServerEnvironment
    val userId: UserId
    val email: String

    class OAuth(
        override val server: ServerEnvironment,
        override val userId: UserId,
        override val email: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Instant?,
        val apiUrl: String,
        val clientId: String,
    ) : PendingCredential

    class AccessToken(
        override val server: ServerEnvironment,
        override val userId: UserId,
        override val email: String,
        val token: String,
    ) : PendingCredential
}
