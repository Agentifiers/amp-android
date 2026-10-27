package com.agentifiers.amp.sync.internal.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One push registration's receiving keys (RFC 8291): the uncompressed P-256 public key Amp encrypts to, the
 * 16-byte auth secret, and ECDH with the private key, which may live in the Android Keystore and never leave it.
 */
internal class PushKeys(val publicKey: ByteArray, val auth: ByteArray, val agree: (ECPublicKey) -> ByteArray)

/** RFC 8291 `aes128gcm` decryption of one Web Push message body, as a user agent does it. */
internal object WebPushDecryptor {
    private const val HEADER = 16 + 4 + 1
    private const val TAG_BYTES = 16

    /** Null when the body is not one well-formed record encrypted to [keys]. Never throws. */
    fun decrypt(body: ByteArray, keys: PushKeys): ByteArray? = try {
        open(body, keys)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun open(body: ByteArray, keys: PushKeys): ByteArray? {
        if (body.size < HEADER) return null
        val header = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also(header::get)
        val recordSize = header.int
        val idLength = header.get().toInt() and 0xff
        if (idLength != 65 || body.size < HEADER + idLength + TAG_BYTES + 1) return null
        val senderPublic = ByteArray(idLength).also(header::get)
        val ciphertext = body.copyOfRange(HEADER + idLength, body.size)
        if (ciphertext.size > recordSize) return null

        val ecdh = keys.agree(p256Point(senderPublic) ?: return null)
        val prkKey = hmac(keys.auth, ecdh)
        val ikm = hmac(prkKey, "WebPush: info".encodeToByteArray() + 0 + keys.publicKey + senderPublic + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".encodeToByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".encodeToByteArray() + 0 + 1).copyOf(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        val padded = cipher.doFinal(ciphertext)
        val delimiter = padded.indexOfLast { it != 0.toByte() }
        if (delimiter < 0 || padded[delimiter] != 2.toByte()) return null
        return padded.copyOf(delimiter)
    }

    /** RFC 8291 section 7: an uncompressed point that is on P-256, so a bad key cannot leak the private key. */
    fun p256Point(uncompressed: ByteArray): ECPublicKey? {
        if (uncompressed.size != 65 || uncompressed[0] != 4.toByte()) return null
        val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
        if (x >= P || y >= P) return null
        if (y.modPow(TWO, P) != x.modPow(THREE, P).subtract(x.multiply(THREE)).add(B).mod(P)) return null
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
    }

    /** The uncompressed form of [key]: 0x04, then x and y as 32 bytes each. */
    fun uncompressed(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private operator fun ByteArray.plus(byte: Int): ByteArray = this + byteArrayOf(byte.toByte())

    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    val P256: ECParameterSpec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
}
