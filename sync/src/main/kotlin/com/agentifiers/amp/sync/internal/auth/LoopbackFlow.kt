package com.agentifiers.amp.sync.internal.auth

import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.sync.api.SignInFailure
import com.agentifiers.amp.sync.api.SignInStep
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The CLI's browser flow (auth-and-security.md, "Loopback PKCE is the fallback"): a listener on 127.0.0.1 only,
 * the authorize page in the browser, one `/callback` request, then the code exchange with the PKCE verifier.
 */
internal class LoopbackFlow(private val calls: AuthCalls) {
    suspend fun run(server: ServerEnvironment, config: OAuthConfig, emit: suspend (SignInStep) -> Unit): Proof {
        val base = SignInFlows.baseOf(server)
        val listener = ServerSocket(0, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val code = closing(listener) {
            val state = randomUrlSafe()
            val verifier = randomUrlSafe()
            val challenge = challengeOf(verifier)
            val authorize = base.newBuilder().addPathSegments("auth/cli/authorize")
                .addQueryParameter("challenge", challenge)
                .addQueryParameter("port", listener.localPort.toString())
                .addQueryParameter("state", state)
                .apply { if (config.cliCodeRedemption) addQueryParameter("redeem", "amp") }
                .build()
            emit(SignInStep.OpenBrowser(authorize.toString()))
            Callback(awaitCode(listener, state), verifier, challenge, "http://127.0.0.1:${listener.localPort}/callback")
        }
        return exchange(server, config, code)
    }

    private class Callback(val code: String, val verifier: String, val challenge: String, val redirectUri: String)

    private suspend fun exchange(server: ServerEnvironment, config: OAuthConfig, callback: Callback): Proof {
        val base = SignInFlows.baseOf(server)
        if (callback.code.startsWith(AMP_CODE_PREFIX)) {
            val body = buildJsonObject {
                put("code", callback.code)
                put("verifier", callback.verifier)
                put("redirect_uri", callback.redirectUri)
            }
            val reply = calls.json(base.under("api/auth/cli-redeem"), body)
            val grant = TokenGrant.parse(reply.body?.takeIf { reply.status in 200..299 }, calls.now()) ?: throw SignInStopped(SignInFailure.ServerRejected)
            return Proof.OAuth(grant, config)
        }
        val fields = mapOf(
            "grant_type" to "authorization_code",
            "code" to callback.code,
            "code_verifier" to callback.verifier,
            "client_id" to config.clientId,
        )
        val reply = calls.form(config.apiUrl.under("user_management/authenticate"), fields)
        val grant = TokenGrant.parse(reply.body?.takeIf { reply.status in 200..299 }, calls.now()) ?: throw SignInStopped(SignInFailure.ServerRejected)
        try {
            calls.json(base.under("api/auth/cli-complete"), buildJsonObject { put("challenge", callback.challenge) }, grant.accessToken)
        } catch (_: IOException) {
            // Best effort, as in the CLI: the tokens are already issued.
        }
        return Proof.OAuth(grant, config)
    }

    /**
     * Answers one connection at a time until one reaches `/callback`, or the 10 minutes the CLI waits run out.
     * Each connection gets at most [CONNECTION_TIMEOUT_MS] of that time, so a client that never finishes its
     * request cannot hold the listener.
     */
    private suspend fun awaitCode(listener: ServerSocket, state: String): String {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (true) {
            val remaining = millisUntil(deadline)
            if (remaining <= 0) throw SignInStopped(SignInFailure.Expired)
            listener.soTimeout = remaining.toInt()
            val socket = try {
                runInterruptible(Dispatchers.IO) { listener.accept() }
            } catch (_: SocketTimeoutException) {
                throw SignInStopped(SignInFailure.Expired)
            }
            val connectionDeadline = minOf(deadline, System.nanoTime() + CONNECTION_TIMEOUT_MS * 1_000_000)
            val code = closing(socket) { runInterruptible(Dispatchers.IO) { answer(socket, state, connectionDeadline) } }
            if (code != null) return code
        }
    }

    /** The code from a valid `/callback`; null for any other request. A bad callback ends the attempt. */
    private fun answer(socket: Socket, state: String, deadline: Long): String? {
        val target = when (val head = readHead(socket, deadline)) {
            Head.TooLarge -> {
                reply(socket, 431, "Request too large.")
                return null
            }
            Head.Unfinished -> return null
            is Head.Read -> head.requestLine.split(' ').getOrNull(1)
        }
        val url = target?.let { "http://127.0.0.1$it".toHttpUrlOrNull() }
        if (url?.encodedPath != "/callback") {
            reply(socket, 404, "Not found.")
            return null
        }
        val code = url.queryParameter("code")
        if (url.queryParameter("error") != null || url.queryParameter("state") != state || code.isNullOrEmpty()) {
            reply(socket, 400, "Sign-in failed. Return to Amp and try again.")
            throw SignInStopped(SignInFailure.ServerRejected)
        }
        reply(socket, 200, "You can close this tab and return to Amp.")
        return code
    }

    private sealed interface Head {
        class Read(val requestLine: String) : Head
        data object TooLarge : Head
        data object Unfinished : Head
    }

    /**
     * Reads the request line and headers, up to the blank line, so the reply never lands on unread bytes and the
     * close stays clean. Gives up at [deadline] or after [MAX_REQUEST_BYTES].
     */
    private fun readHead(socket: Socket, deadline: Long): Head {
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val remaining = millisUntil(deadline)
                if (remaining <= 0) return Head.Unfinished
                socket.soTimeout = remaining.toInt()
                val read = input.read(buffer)
                if (read < 0) return Head.Unfinished
                head.write(buffer, 0, read)
                val text = head.toString(Charsets.US_ASCII)
                val end = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: text.indexOf("\n\n").takeIf { it >= 0 }
                if (end != null) return Head.Read(text.substring(0, end).lineSequence().first().trimEnd('\r'))
                if (head.size() > MAX_REQUEST_BYTES) return Head.TooLarge
            }
        } catch (_: IOException) {
            return Head.Unfinished
        }
    }

    private fun reply(socket: Socket, status: Int, message: String) {
        val body = "<!doctype html><meta charset=\"utf-8\"><title>Amp</title><p>$message</p>".encodeToByteArray()
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            431 -> "Request Header Fields Too Large"
            else -> "Not Found"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        try {
            socket.getOutputStream().apply {
                write(head.encodeToByteArray())
                write(body)
                flush()
            }
        } catch (_: IOException) {
            // The browser went away; the code it delivered is still good.
        }
    }

    private companion object {
        const val AMP_CODE_PREFIX = "amp-auth."
        const val TIMEOUT_MS = 10 * 60 * 1000L
        const val CONNECTION_TIMEOUT_MS = 10_000L
        const val MAX_REQUEST_BYTES = 16 * 1024
        const val BACKLOG = 4
        val random = SecureRandom()

        fun millisUntil(deadline: Long): Long = (deadline - System.nanoTime()) / 1_000_000

        /**
         * Runs [block] and closes [resource] when it ends or is cancelled. Closing is what unblocks a thread in
         * `accept` or `read`; an interrupt does not.
         */
        suspend fun <T> closing(resource: Closeable, block: suspend () -> T): T = coroutineScope {
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    resource.close()
                }
            }
            try {
                block()
            } finally {
                closer.cancel()
            }
        }

        fun randomUrlSafe(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

        fun challengeOf(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
    }
}
