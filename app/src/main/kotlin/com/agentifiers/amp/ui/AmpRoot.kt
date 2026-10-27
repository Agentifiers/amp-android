package com.agentifiers.amp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.AppLockController
import com.agentifiers.amp.BuildConfig
import com.agentifiers.amp.LayoutOverrides
import com.agentifiers.amp.ServerChoice
import com.agentifiers.amp.SignOutCoordinator
import com.agentifiers.amp.VisibleThreadLeases
import com.agentifiers.amp.WindowNavigator
import com.agentifiers.amp.WindowNotifications
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.LocalPaneCreases
import com.agentifiers.amp.design.LocalWindowCreases
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.layout.CreaseSource
import com.agentifiers.amp.layout.Creases
import com.agentifiers.amp.layout.DisplayRotation
import com.agentifiers.amp.layout.WindowGeometry
import com.agentifiers.amp.layout.resolveCreases
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.NotificationAccess
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.settings.SignInScreen
import com.agentifiers.amp.sync.api.AmpEngines
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.store.AppPreferences
import com.agentifiers.amp.window.StandInDevices
import com.agentifiers.amp.window.WindowShell
import com.agentifiers.amp.window.WindowState

/**
 * What one window needs from the process: the engine, the sign-in server, outside navigation, its leases, app lock,
 * sign-out, its layout state, and its notification permission.
 */
class WindowServices(
    val engines: AmpEngines,
    val server: ServerChoice,
    val navigator: WindowNavigator,
    val overrides: LayoutOverrides,
    val leases: VisibleThreadLeases,
    val lock: AppLockController,
    val signOuts: SignOutCoordinator,
    val prefs: AppPreferences,
    val window: WindowState,
    val notifications: WindowNotifications,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AmpRoot(services: WindowServices, deviceModel: String, rotation: DisplayRotation, reportedFoldsDp: List<Float>) {
    val roster by services.engines.roster.collectAsStateWithLifecycle()
    val server by services.server.current.collectAsStateWithLifecycle()
    val override by services.overrides.geometry.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val calibrator = remember { if (BuildConfig.DEBUG) CreaseCalibrator(context, deviceModel) else null }
    val density = LocalDensity.current.density
    AmpTheme {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag(ShellTags.root), color = AmpTheme.colors.surfaces.canvas) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // The outer density, not maxWidth: a density-only change keeps the pixel constraints, so the
                // subcomposition would otherwise keep measuring with the old density.
                val width = override?.widthDp ?: (constraints.maxWidth / density)
                val height = constraints.maxHeight / density
                val creases = override?.let { Creases(it.creasesDp, CreaseSource.Override, reportedFoldsDp) }
                    ?: resolveCreases(deviceModel, width, rotation, reportedFoldsDp, calibrator?.calibration, StandInDevices)
                val geometry = WindowGeometry(width, height, creases)
                val editor = calibrator?.let {
                    CreaseEditor(
                        place = { index, dp -> it.place(index, dp, creases.positionsDp, width, height, density, rotation) },
                        reset = it::reset,
                    )
                }
                // Sign-in and the lock screen span the window, so its creases are theirs; WindowShell narrows them per column.
                val windowCreases = creases.positionsDp.map { it.dp }
                CompositionLocalProvider(LocalPaneCreases provides windowCreases, LocalWindowCreases provides windowCreases) {
                    AppLockGate(services.lock) {
                        val window = rememberSaveable(saver = WindowAccount.Saver) { WindowAccount(null, WindowOverlay.None) }
                        val workspaces = rememberSaveableStateHolder()
                        val signIn: @Composable (ServerEnvironment, String?, (() -> Unit)?) -> Unit = { target, notice, onCancel ->
                            SignInScreen(services.engines.signIn, WindowSignInHost(context, target, notice, onCancel, window))
                        }
                        when (val state = roster) {
                            Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            is Loadable.Failed -> signIn(server, null, null)
                            is Loadable.Ready -> {
                                val signedIn = state.value.accounts.map { it.id.raw }.toSet()
                                LaunchedEffect(signedIn) { services.window.retain(signedIn) }
                                val navigation by services.navigator.pending.collectAsStateWithLifecycle()
                                LaunchedEffect(navigation, signedIn) {
                                    val request = navigation ?: return@LaunchedEffect
                                    val wanted = request.account ?: return@LaunchedEffect
                                    if (wanted.raw in signedIn) window.switchTo(wanted) else services.navigator.consumed(request)
                                }
                                val account = state.value.accountFor(window.chosen)
                                val other = state.value.accounts.firstOrNull { it.id != account?.id }
                                when {
                                    account == null -> signIn(server, null, null)
                                    window.overlay == WindowOverlay.AddAccount -> signIn(server, null, window::dismiss)
                                    account.auth == AuthHealth.NeedsSignIn -> signIn(
                                        account.server,
                                        "Sign in again to ${account.email} on ${account.server.host}.",
                                        other?.let { next -> { window.switchTo(next.id) } },
                                    )
                                    else -> {
                                        workspaces.SaveableStateProvider(account.id.raw) {
                                            WindowShell(services.engines.scope(account.id), window, services, services.window, geometry, editor)
                                        }
                                        NotificationAsk(services.notifications)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asks once, whichever way the account signed in and on any launch with a signed-in account, until the user answers.
 * Turn On shows Android's prompt; Not Now leaves Turn On in Settings and the Sidebar.
 */
@Composable
private fun NotificationAsk(notifications: WindowNotifications) {
    val access by notifications.access.collectAsStateWithLifecycle()
    if (access != NotificationAccess.FirstAsk) return
    AlertDialog(
        onDismissRequest = notifications::notNow,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(ShellTags.notificationsAsk),
        title = { Text("Turn on notifications?") },
        text = { Text("Get notified when a thread needs you or finishes.") },
        confirmButton = { AmpButton("Turn On", notifications::turnOn, ShellTags.notificationsAskTurnOn, rank = AmpButtonRank.Primary) },
        dismissButton = { AmpButton("Not Now", notifications::notNow, ShellTags.notificationsAskNotNow, rank = AmpButtonRank.Tertiary) },
    )
}
