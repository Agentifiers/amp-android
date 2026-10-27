package com.agentifiers.amp.settings

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.model.NotificationAccess
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.SettingsPage
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Loadable
import kotlinx.coroutines.flow.StateFlow

/** What the sign-in screen needs from :app. */
interface SignInHost {
    val defaultServer: ServerEnvironment

    /** Debug builds: accept http for localhost, 127.0.0.1 and 10.0.2.2. */
    val allowLocalHttp: Boolean

    /** Shown above the screen, such as "Sign in again to a@b.com on ampcode.com." */
    val notice: String?

    /** Non-null when the user can back out, as from Add Account. */
    val onCancel: (() -> Unit)?

    /** A Custom Tab; [ephemeral] asks for an Ephemeral Custom Tab that keeps no browser session. */
    fun openBrowser(url: String, ephemeral: Boolean)

    /** Brings the app's window back over the Custom Tab once the sign-in is approved. */
    fun bringToFront()

    /** The window switches to [account]. */
    fun onSignedIn(account: AccountId)
}

/** What the account switcher needs from :app. */
interface AccountsHost {
    val roster: StateFlow<Loadable<AccountRoster>>

    /** This window's account. */
    val current: AccountId
    fun switchTo(account: AccountId)
    fun addAccount()

    /** Called after the switcher's own confirmation dialog. */
    fun signOut(account: AccountId)
    fun openSettings(page: SettingsPage)
    fun openWeb(page: AccountWebPage)

    /** [account]'s engine scope, for the Needs you count and credits on its row. */
    fun scopeOf(account: AccountId): AccountScope

    /** Closes the sheet or dialog that holds the switcher. */
    fun dismiss()
}

/** What native Settings needs from :app on top of [AccountsHost]. */
interface SettingsHost : AccountsHost {
    val appLock: StateFlow<AppLock>
    fun setAppLock(lock: AppLock)

    /** A Custom Tab for pages outside the account, such as Service Status. */
    fun openUrl(url: String)

    /** Android's notification settings for this app. */
    fun openNotificationSettings()

    /** Whether Android lets Amp post notifications, read again whenever the window resumes. */
    val notifications: StateFlow<NotificationAccess>

    /** Android's prompt while it would still show, otherwise Android's notification settings for this app. */
    fun turnOnNotifications()

    /** "Notify even when a thread is open elsewhere": the phone checks for finished turns Amp sent no push for. */
    val notifyWhenOpenElsewhere: StateFlow<Boolean>
    fun setNotifyWhenOpenElsewhere(on: Boolean)

    /** Leaves Settings and gives the window back what it showed before. */
    fun close()
    val versionName: String
}
