package com.agentifiers.amp.sync.internal.push

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement

/** What this device registered for one account: where Amp pushes, and the relay row behind it when there is one. */
internal data class PushRegistration(val endpoint: String, val relay: RelayRegistration?)

/** Where one account's push keys and registration live. [PushVault] on a device. */
internal interface PushSecrets {
    fun keys(): PushKeys
    fun registration(): PushRegistration?
    fun saveRegistration(registration: PushRegistration?)
    fun wipe()
}

/**
 * One account's push secrets. The P-256 private key is an Android Keystore key that only agrees (ECDH) and never
 * leaves the Keystore. The auth secret and the relay registration are AES-GCM encrypted under a Keystore-wrapped
 * Tink keyset in `noBackupFilesDir`. Callers run these on an I/O thread.
 */
internal class PushVault(context: Context, private val account: AccountId) : PushSecrets {
    private val file = File(context.noBackupFilesDir, "vault/push-${account.raw}.bin")
    private val alias = "amp_push_${account.raw}"

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    @Synchronized
    override fun keys(): PushKeys {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = read()
        val auth = entry?.string("auth")?.let(::unbase64)
        if (auth == null || !store.containsAlias(alias)) {
            generate()
            write(Entry(base64(ByteArray(16).also(SecureRandom()::nextBytes)), registration = null))
            return keys()
        }
        val publicKey = store.getCertificate(alias).publicKey as ECPublicKey
        val privateKey = store.getKey(alias, null) as PrivateKey
        return PushKeys(WebPushDecryptor.uncompressed(publicKey), auth) { peer ->
            KeyAgreement.getInstance("ECDH", KEYSTORE).run {
                init(privateKey)
                doPhase(peer, true)
                generateSecret()
            }
        }
    }

    @Synchronized
    override fun registration(): PushRegistration? {
        val stored = read()?.obj("registration") ?: return null
        val endpoint = stored.string("endpoint") ?: return null
        val relay = stored.obj("relay")?.let { relay ->
            val rid = relay.string("rid")
            val pushUrl = relay.string("pushUrl")
            val secret = relay.string("secret")
            if (rid == null || pushUrl == null || secret == null) null else RelayRegistration(rid, pushUrl, secret)
        }
        return PushRegistration(endpoint, relay)
    }

    @Synchronized
    override fun saveRegistration(registration: PushRegistration?) {
        keys()
        val auth = read()?.string("auth") ?: return
        write(Entry(auth, registration))
    }

    @Synchronized
    override fun wipe() {
        file.delete()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
    }

    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .build(),
            )
        }.generateKeyPair()
    }

    private class Entry(val auth: String, val registration: PushRegistration?)

    private fun read(): JsonObject? {
        if (!file.exists()) return null
        return try {
            AmpJson.parseToJsonElement(aead.decrypt(file.readBytes(), ASSOCIATED_DATA).decodeToString()) as? JsonObject
        } catch (_: GeneralSecurityException) {
            file.delete()
            null
        }
    }

    private fun write(entry: Entry) {
        val json = buildJsonObject {
            put("auth", entry.auth)
            entry.registration?.let { registration ->
                putJsonObject("registration") {
                    put("endpoint", registration.endpoint)
                    registration.relay?.let { relay ->
                        putJsonObject("relay") {
                            put("rid", relay.rid)
                            put("pushUrl", relay.pushUrl)
                            put("secret", relay.secret)
                        }
                    }
                }
            }
        }
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, "${file.name}.tmp")
        staged.writeBytes(aead.encrypt(json.toString().encodeToByteArray(), ASSOCIATED_DATA))
        staged.renameTo(file)
    }

    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unbase64(text: String) = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEYSET_NAME = "amp_push_keyset"
        const val KEYSET_PREFS = "amp_push_vault"
        const val MASTER_KEY_URI = "android-keystore://amp_push_master"
        val ASSOCIATED_DATA = "amp-push-keys".encodeToByteArray()
    }
}
