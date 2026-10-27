package com.agentifiers.amp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.model.AppLock
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.notify.NotificationOpen
import com.agentifiers.amp.layout.DisplayRotation
import com.agentifiers.amp.layout.displayRotation
import com.agentifiers.amp.layout.reportedVerticalFoldsDp
import com.agentifiers.amp.store.AppPreferences
import com.agentifiers.amp.sync.api.AmpEngines
import com.agentifiers.amp.ui.AmpRoot
import com.agentifiers.amp.ui.WindowServices
import com.agentifiers.amp.window.WindowModel
import com.agentifiers.amp.window.WindowState
import com.agentifiers.amp.window.shortcutGroups
import com.agentifiers.amp.window.windowOpening
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * One app window. The manifest lets the system open several (multi-instance), and each instance keys its leases
 * and saved workspace by its task id.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var engines: AmpEngines
    @Inject lateinit var server: ServerChoice
    @Inject lateinit var navigator: WindowNavigator
    @Inject lateinit var overrides: LayoutOverrides
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var lock: AppLockController
    @Inject lateinit var signOuts: SignOutCoordinator
    @Inject lateinit var notificationPrompt: NotificationPrompt

    private val model: WindowModel by viewModels()
    private lateinit var window: WindowState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val canvas = AmpTheme.colors.surfaces.canvas.toArgb()
        enableEdgeToEdge(SystemBarStyle.dark(canvas), SystemBarStyle.dark(canvas))
        val windowKey = "task-$taskId"
        window = model.state ?: WindowState(windowKey, intent.windowOpening()).also { model.state = it }
        val leases = VisibleThreadLeases(windowKey, lifecycle, (application as AmpApplication).leases)
        val services = WindowServices(engines, server, navigator, overrides, leases, lock, signOuts, prefs, window, WindowNotifications(this, notificationPrompt))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch { lock.setting.collect { setRecentsScreenshotEnabled(it == AppLock.Off) } }
        }
        val folds = reportedVerticalFoldsDp(this)
        val rotations = displayRotation(this)
        setContent {
            AmpRoot(
                services = services,
                deviceModel = Build.MODEL,
                rotation = rotations.collectAsStateWithLifecycle(DisplayRotation.Natural).value,
                reportedFoldsDp = folds.collectAsStateWithLifecycle(emptyList()).value,
            )
        }
        if (savedInstanceState == null) openFromNotification(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    private fun openFromNotification(intent: Intent) {
        val open = NotificationOpen.parse(intent) ?: return
        navigator.open(PaneDestination.Thread(open.thread), open.account)
    }

    override fun onStart() {
        super.onStart()
        (application as AmpApplication).importDebugToken()
    }

    /** Window shortcuts run before any view sees the key, so a focused text field cannot swallow them. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = window.keys?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(shortcutGroups())
    }
}
