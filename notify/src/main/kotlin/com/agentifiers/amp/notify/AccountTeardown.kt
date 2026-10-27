package com.agentifiers.amp.notify

import android.content.Context
import androidx.work.WorkManager
import com.agentifiers.amp.model.AccountId
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sign-out step owned by :notify, called by :app's SignOutCoordinator before `AmpEngines.signOut`, which deletes the
 * relay registration. Idempotent: running it again for a forgotten account changes nothing.
 */
@Singleton
class AccountTeardown @Inject constructor(@ApplicationContext private val context: Context) {
    fun forget(account: AccountId) {
        NotifyRuntime.notifier.cancelAccount(account)
        WorkManager.getInstance(context).cancelAllWorkByTag(accountTag(account))
        LocalTurns.forget(account)
        if (UnifiedPush.getAckDistributor(context) != null) UnifiedPush.unregister(context, account.raw)
    }
}
