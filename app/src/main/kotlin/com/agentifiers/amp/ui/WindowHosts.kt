package com.agentifiers.amp.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.agentifiers.amp.AppLockController
import com.agentifiers.amp.BuildConfig
import com.agentifiers.amp.MainActivity
import com.agentifiers.amp.SignOutCoordinator
import com.agentifiers.amp.WindowNotifications
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.model.NotificationAccess
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.model.SettingsPage
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.notify.NotifyWhenOpenElsewhere
import com.agentifiers.amp.settings.SettingsHost
import com.agentifiers.amp.settings.SignInHost
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.web.openCustomTab
import kotlinx.coroutines.flow.StateFlow

internal class WindowSignInHost(
    private val context: Context,
    override val defaultServer: ServerEnvironment,
    override val notice: String?,
    override val onCancel: (() -> Unit)?,
    private val window: WindowAccount,
) : SignInHost {
    override val allowLocalHttp: Boolean = BuildConfig.DEBUG

    override fun openBrowser(url: String, ephemeral: Boolean) = openCustomTab(context, url, ephemeral)

    /** The Custom Tab runs in this task, so bringing MainActivity back with these flags closes it (A3). */
    override fun bringToFront() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onSignedIn(account: AccountId) = window.switchTo(account)
}

/** One per window and account: the switcher and every Settings page act on this window. */
internal class WindowSettingsHost(
    private val context: Context,
    override val current: AccountId,
    private val window: WindowAccount,
    override val roster: StateFlow<Loadable<AccountRoster>>,
    private val signOuts: SignOutCoordinator,
    private val lock: AppLockController,
    private val navigate: (PaneDestination) -> Unit,
    private val scopes: (AccountId) -> AccountScope,
    private val close: () -> Unit,
    private val windowNotifications: WindowNotifications,
) : SettingsHost {
    override val appLock: StateFlow<AppLock> = lock.setting
    override val versionName: String = BuildConfig.VERSION_NAME

    override fun switchTo(account: AccountId) = window.switchTo(account)

    override fun addAccount() = window.addAccount()

    override fun signOut(account: AccountId) {
        window.dismiss()
        signOuts.launch(account)
    }

    override fun openSettings(page: SettingsPage) {
        window.dismiss()
        navigate(PaneDestination.Settings(page))
    }

    override fun openWeb(page: AccountWebPage) {
        window.dismiss()
        navigate(PaneDestination.Web(WebSurface.AccountPage(page, current, null)))
    }

    override fun dismiss() = window.dismiss()

    override fun scopeOf(account: AccountId): AccountScope = scopes(account)

    override fun close() = close.invoke()

    override fun setAppLock(lock: AppLock) = this.lock.setAppLock(lock)

    override fun openUrl(url: String) = openCustomTab(context, url, ephemeral = false)

    override fun openNotificationSettings() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    override val notifications: StateFlow<NotificationAccess> get() = windowNotifications.access

    override fun turnOnNotifications() = windowNotifications.turnOn()

    override val notifyWhenOpenElsewhere: StateFlow<Boolean> get() = NotifyWhenOpenElsewhere.on

    override fun setNotifyWhenOpenElsewhere(on: Boolean) = NotifyWhenOpenElsewhere.set(on)
}
