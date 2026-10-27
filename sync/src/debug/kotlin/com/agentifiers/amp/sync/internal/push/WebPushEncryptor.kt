package com.agentifiers.amp.sync.internal.push

import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The sender side of RFC 8291, as Amp's server does it, so a debug build can push to itself. Debug builds only. */
internal object WebPushEncryptor {
    private const val RECORD_SIZE = 4096

    fun encrypt(plaintext: ByteArray, receiverPublic: ByteArray, auth: ByteArray): ByteArray {
        val receiver = requireNotNull(WebPushDecryptor.p256Point(receiverPublic)) { "receiver key is not on P-256" }
        val ephemeral = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val senderPublic = WebPushDecryptor.uncompressed(ephemeral.public as ECPublicKey)
        val ecdh = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private)
            doPhase(receiver, true)
            generateSecret()
        }
        val ikm = hmac(hmac(auth, ecdh), "WebPush: info".encodeToByteArray() + byteArrayOf(0) + receiverPublic + senderPublic + byteArrayOf(1))
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".encodeToByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".encodeToByteArray() + byteArrayOf(0, 1)).copyOf(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
        val ciphertext = cipher.doFinal(plaintext + byteArrayOf(2))
        return ByteBuffer.allocate(16 + 4 + 1 + senderPublic.size + ciphertext.size)
            .put(salt).putInt(RECORD_SIZE).put(senderPublic.size.toByte()).put(senderPublic).put(ciphertext)
            .array()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
