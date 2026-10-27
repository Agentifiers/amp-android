package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One Web Push the fake sent: where it went, the encrypted body Amp would POST, and the payload inside it. */
public class PushDelivery internal constructor(
    public val endpoint: String,
    public val body: ByteArray,
    public val payload: String,
)

private class Subscription(val endpoint: String, val p256dh: ByteArray, val auth: ByteArray)

/**
 * Stores `client_upsert_notification_subscription` per thread and, when a turn ends, sends the S11 `thread_idle`
 * payload as RFC 8291 `aes128gcm` Web Push. Every delivery is recorded in [deliveries]. One to an endpoint under this
 * fake's own `/push/` goes to [onPush]; another is POSTed when the endpoint is on this machine, so a fake never
 * pushes through a deployed relay.
 */
internal class PushHub(private val world: World) {
    private val subscriptions = HashMap<String, MutableList<Subscription>>()
    val deliveries = CopyOnWriteArrayList<PushDelivery>()
    private val client by lazy { OkHttpClient() }

    @Volatile
    var onPush: ((PushDelivery) -> Unit)? = null

    fun subscriptionCount(threadId: String): Int = subscriptions[threadId]?.size ?: 0

    fun subscribe(thread: ThreadActor, params: JsonObject) {
        val subscription = params.obj("subscription") ?: return
        val endpoint = subscription.string("endpoint") ?: return
        val keys = subscription.obj("keys") ?: return
        val decoded = runCatching {
            Subscription(endpoint, base64Url(keys.string("p256dh")!!), base64Url(keys.string("auth")!!))
        }.getOrNull() ?: return
        val list = subscriptions.getOrPut(thread.id) { ArrayList() }
        list.removeAll { it.endpoint == endpoint }
        list += decoded
    }

    /** Returns how many pushes it sent: none while any client holds the thread open, as Amp does (S23). */
    fun turnEnded(thread: ThreadActor, lastAssistantId: String?, text: String?): Int {
        val targets = subscriptions[thread.id].orEmpty()
        if (targets.isEmpty() || thread.heldOpen) return 0
        val threadUrl = "${world.origin}/threads/${thread.id}"
        val payload = json(
            "kind" to "thread_idle",
            "threadId" to thread.id,
            "threadURL" to threadUrl,
            "title" to thread.title,
            "body" to (text ?: ""),
            "tag" to "amp-thread-${thread.id}-idle-$lastAssistantId",
            "requireInteraction" to false,
            "icon" to "${world.origin}/app-icon.png",
            "badge" to "${world.origin}/app-icon-monochrome-512.png",
        ).toString()
        for (target in targets) {
            val body = WebPushCrypto.encrypt(payload.encodeToByteArray(), target.p256dh, target.auth)
            val delivery = PushDelivery(target.endpoint, body, payload)
            deliveries += delivery
            when {
                target.endpoint.startsWith("${world.origin}/push/") -> onPush?.invoke(delivery)
                isLocal(target.endpoint) -> post(target.endpoint, body)
            }
        }
        return targets.size
    }

    private fun isLocal(endpoint: String): Boolean {
        val host = endpoint.toHttpUrlOrNull()?.host ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
    }

    private fun post(endpoint: String, body: ByteArray) {
        val request = Request.Builder().url(endpoint)
            .header("Content-Encoding", "aes128gcm")
            .header("TTL", "86400")
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    private fun base64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value.trimEnd('='))
}

/** RFC 8291 message encryption with one `aes128gcm` record (RFC 8188), as a Web Push sender does it. */
internal object WebPushCrypto {
    private val random = SecureRandom()
    private const val RECORD_SIZE = 4096

    fun encrypt(plaintext: ByteArray, uaPublic: ByteArray, authSecret: ByteArray): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val ephemeral = generator.generateKeyPair()
        val asPublic = uncompressed(ephemeral.public as ECPublicKey)
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(ephemeral.private)
            doPhase(publicKey(uaPublic), true)
        }
        val ecdhSecret = agreement.generateSecret()
        val prkKey = hmac(authSecret, ecdhSecret)
        val ikm = hmac(prkKey, "WebPush: info".encodeToByteArray() + 0 + uaPublic + asPublic + 1)
        val salt = ByteArray(16).also(random::nextBytes)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".encodeToByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".encodeToByteArray() + 0 + 1).copyOf(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        }
        val ciphertext = cipher.doFinal(plaintext + 2)
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + ciphertext.size)
            .put(salt).putInt(RECORD_SIZE).put(asPublic.size.toByte()).put(asPublic).put(ciphertext)
            .array()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private operator fun ByteArray.plus(byte: Int): ByteArray = this + byteArrayOf(byte.toByte())

    private fun uncompressed(key: ECPublicKey): ByteArray =
        byteArrayOf(4) + unsigned32(key.w.affineX) + unsigned32(key.w.affineY)

    private fun unsigned32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }

    private fun publicKey(uncompressed: ByteArray): ECPublicKey {
        require(uncompressed.size == 65 && uncompressed[0] == 4.toByte()) { "p256dh is not an uncompressed P-256 point" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, uncompressed.copyOfRange(1, 33)), BigInteger(1, uncompressed.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }
}
