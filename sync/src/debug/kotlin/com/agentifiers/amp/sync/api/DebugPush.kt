package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.sync.internal.DefaultEngines
import com.agentifiers.amp.sync.internal.push.WebPushEncryptor

/**
 * Debug builds only: encrypts [plaintext] to [account]'s push keys exactly as Amp would, so the `inject-push` event
 * runs the real decryption and display path. Null when the account is not signed in.
 */
public fun AmpEngines.encryptPushForTest(account: AccountId, plaintext: ByteArray): ByteArray? {
    val keys = (this as DefaultEngines).background(account)?.keys ?: return null
    return WebPushEncryptor.encrypt(plaintext, keys.publicKey, keys.auth)
}
