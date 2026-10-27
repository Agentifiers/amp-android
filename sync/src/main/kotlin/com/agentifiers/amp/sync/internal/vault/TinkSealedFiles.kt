package com.agentifiers.amp.sync.internal.vault

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Files under `noBackupFilesDir/vault`, each encrypted with its own AES-256-GCM keyset that its own Android
 * Keystore key wraps. Destroying a name deletes the file, the keyset's preferences file and the Keystore key,
 * so a signed-out account leaves nothing that could decrypt a copy. Callers run these on an I/O thread.
 */
internal class TinkSealedFiles(private val context: Context) : SealedFiles {
    private val dir = File(context.noBackupFilesDir, "vault")
    private val aeads = ConcurrentHashMap<String, Aead>()
    private val registered by lazy { AeadConfig.register() }

    override fun read(name: String): ByteArray? {
        val file = fileOf(name)
        if (!file.exists()) return null
        return try {
            aead(name).decrypt(file.readBytes(), name.encodeToByteArray())
        } catch (_: GeneralSecurityException) {
            destroy(name)
            null
        }
    }

    override fun write(name: String, plaintext: ByteArray) {
        dir.mkdirs()
        val staged = File(dir, "$name.bin.tmp")
        staged.writeBytes(aead(name).encrypt(plaintext, name.encodeToByteArray()))
        staged.renameTo(fileOf(name))
    }

    override fun destroy(name: String) {
        aeads.remove(name)
        fileOf(name).delete()
        context.deleteSharedPreferences(PREFS_PREFIX + name)
        deleteKey(MASTER_ALIAS_PREFIX + name)
    }

    override fun names(): List<String> =
        dir.listFiles { file -> file.name.endsWith(".bin") }.orEmpty().map { it.name.removeSuffix(".bin") }

    /** Slice 1 kept one token in `vault/account-token.bin` under one shared key. Accounts are per-file now. */
    fun retireSingleTokenVault() {
        File(dir, "account-token.bin").delete()
        context.deleteSharedPreferences("amp_vault")
        deleteKey("amp_vault_master")
    }

    private fun fileOf(name: String) = File(dir, "$name.bin")

    private fun aead(name: String): Aead = aeads.getOrPut(name) {
        registered
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREFS_PREFIX + name)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_ALIAS_PREFIX$name")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun deleteKey(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val KEYSET_NAME = "keyset"
        const val PREFS_PREFIX = "amp_vault_"
        const val MASTER_ALIAS_PREFIX = "amp_vault_master_"
    }
}
