package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.sync.api.PushEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.ECPrivateKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/** RFC 8291 section 5: the published receiver keys and message. */
class WebPushDecryptorTest {
    private val body = b64(
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
            "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
            "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
    )
    private val receiver = softwareKeys(
        privateKey = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94",
        publicKey = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
        auth = "BTBZMqHH6r4Tts7J_aSIgg",
    )

    @Test
    fun theRfcExampleDecryptsToItsPlaintext() {
        val plaintext = WebPushDecryptor.decrypt(body, receiver)
        assertEquals("When I grow up, I want to be a watermelon", plaintext?.decodeToString())
    }

    @Test
    fun anotherReceiversKeyFailsWithoutThrowing() {
        val stranger = softwareKeys(
            privateKey = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
            publicKey = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
            auth = "BTBZMqHH6r4Tts7J_aSIgg",
        )
        assertEquals("When I grow up, I want to be a watermelon", WebPushDecryptor.decrypt(body, receiver)?.decodeToString())
        assertNull(WebPushDecryptor.decrypt(body, stranger))
    }

    @Test
    fun aWrongAuthSecretOrATamperedBodyFails() {
        val wrongAuth = PushKeys(receiver.publicKey, ByteArray(16), receiver.agree)
        assertNull(WebPushDecryptor.decrypt(body, wrongAuth))
        val tampered = body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(WebPushDecryptor.decrypt(tampered, receiver))
        assertNull(WebPushDecryptor.decrypt(body.copyOf(20), receiver))
    }

    @Test
    fun theS11PayloadParsesToAThreadIdleEvent() {
        val payload = """{"kind":"thread_idle","threadId":"T-01a16702-3202-73fb-8f52-e4498ccd3721",""" +
            """"threadURL":"https://ampcode.com/threads/T-01a16702-3202-73fb-8f52-e4498ccd3721","title":"Rename add to add_numbers",""" +
            """"body":"done","tag":"amp-thread-T-01a16702-3202-73fb-8f52-e4498ccd3721-idle-M-1","requireInteraction":false}"""
        val event = PushPayload.parse(payload.encodeToByteArray()) as PushEvent.ThreadIdle
        assertEquals("T-01a16702-3202-73fb-8f52-e4498ccd3721", event.thread.raw)
        assertEquals("Rename add to add_numbers", event.title)
        assertEquals("done", event.body)
        assertEquals("amp-thread-T-01a16702-3202-73fb-8f52-e4498ccd3721-idle-M-1", event.tag)
        val unknown = PushPayload.parse("""{"kind":"mention","threadId":"T-01a16702-3202-73fb-8f52-e4498ccd3721","title":"Hi"}""".encodeToByteArray())
        assertEquals("mention", (unknown as PushEvent.Other).kind)
        assertNull(PushPayload.parse("""{"kind":"thread_idle","threadId":"not-a-thread"}""".encodeToByteArray()))
    }

    private fun softwareKeys(privateKey: String, publicKey: String, auth: String): PushKeys {
        val private = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, b64(privateKey)), WebPushDecryptor.P256))
        return PushKeys(b64(publicKey), b64(auth)) { peer ->
            KeyAgreement.getInstance("ECDH").run {
                init(private)
                doPhase(peer, true)
                generateSecret()
            }
        }
    }

    private fun b64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
