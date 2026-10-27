package com.agentifiers.amp

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.notify.AccountTeardown
import com.agentifiers.amp.sync.api.AmpEngines
import com.agentifiers.amp.store.AppPreferences
import com.agentifiers.amp.store.forgetWorkspaces
import com.agentifiers.amp.web.WebProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place sign-out runs (auth-and-security.md, "Sign-out ends the session"). Every step tolerates
 * state that is already gone, so a sign-out cut short by process death finishes when the user tries again.
 */
@Singleton
class SignOutCoordinator @Inject constructor(engines: AmpEngines, notifications: AccountTeardown, prefs: AppPreferences) {
    private val steps = listOf<suspend (AccountId) -> Unit>(
        { notifications.forget(it) },
        engines::signOut,
        WebProfiles::delete,
        { prefs.forgetWorkspaces(it.raw) },
    )

    /** The window that asks closes when its account leaves the roster, so the steps cannot run in its scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun signOut(account: AccountId) {
        steps.forEach { it(account) }
    }

    fun launch(account: AccountId): Job = scope.launch { signOut(account) }
}
