package com.agentifiers.amp

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.agentifiers.amp.model.NotificationAccess
import com.agentifiers.amp.model.PromptHistory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the app remembers about asking for notifications, shared by every window. */
@Singleton
class NotificationPrompt @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("notification-prompt", Context.MODE_PRIVATE)
    private val mutableHistory = MutableStateFlow(prefs.getString(HISTORY, null)?.let(PromptHistory::valueOf) ?: PromptHistory.Never)
    private val mutableNoticeDismissed = MutableStateFlow(prefs.getBoolean(NOTICE_DISMISSED, false))
    val history: StateFlow<PromptHistory> = mutableHistory.asStateFlow()
    val noticeDismissed: StateFlow<Boolean> = mutableNoticeDismissed.asStateFlow()

    fun record(next: PromptHistory) {
        if (next <= mutableHistory.value) return
        prefs.edit().putString(HISTORY, next.name).apply()
        mutableHistory.value = next
    }

    fun dismissNotice() {
        prefs.edit().putBoolean(NOTICE_DISMISSED, true).apply()
        mutableNoticeDismissed.value = true
    }

    private companion object {
        const val HISTORY = "history"
        const val NOTICE_DISMISSED = "notice-dismissed"
    }
}

/**
 * One window's [NotificationAccess], read again on every resume and whenever another window answers, and the actions
 * that change it. Built in `onCreate`, because the permission request must be registered before the window starts.
 */
class WindowNotifications(private val activity: ComponentActivity, private val prompt: NotificationPrompt) {
    private val manager = activity.getSystemService(NotificationManager::class.java)
    private val mutableAccess = MutableStateFlow(read())
    val access: StateFlow<NotificationAccess> = mutableAccess.asStateFlow()
    val noticeDismissed: StateFlow<Boolean> get() = prompt.noticeDismissed
    private val request = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = refresh()
        })
        activity.lifecycleScope.launch { prompt.history.collect { refresh() } }
    }

    /** Android's prompt while it would still show, otherwise Amp's page in Android's notification settings. */
    fun turnOn() = when (access.value) {
        NotificationAccess.FirstAsk, NotificationAccess.AskAgain -> {
            prompt.record(PromptHistory.Requested)
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        is NotificationAccess.Allowed, NotificationAccess.SettingsOnly ->
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }

    fun notNow() = prompt.record(PromptHistory.Declined)

    fun dismissNotice() = prompt.dismissNotice()

    private fun refresh() {
        mutableAccess.value = read()
    }

    private fun read(): NotificationAccess {
        val runtime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        return NotificationAccess.of(
            runtimePermission = runtime,
            granted = runtime && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            rationale = runtime && activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
            appEnabled = manager.areNotificationsEnabled(),
            history = prompt.history.value,
            channelsOff = manager.notificationChannels.filter { it.importance == NotificationManager.IMPORTANCE_NONE }.map { it.name.toString() },
        )
    }
}
