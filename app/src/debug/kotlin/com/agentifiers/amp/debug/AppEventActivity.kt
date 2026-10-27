package com.agentifiers.amp.debug

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import com.agentifiers.amp.AppLockController
import com.agentifiers.amp.LayoutOverrides
import com.agentifiers.amp.MainActivity
import com.agentifiers.amp.ServerChoice
import com.agentifiers.amp.SignOutCoordinator
import com.agentifiers.amp.WindowNavigator
import com.agentifiers.amp.sync.api.AmpEngines
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives `amp-debug://event/<name>?json=…`, hands it to [AppEvents] on a process-wide scope, and closes on top
 * of the app's window. Bringing MainActivity forward matters when an event lands while the app is relaunching:
 * otherwise finishing this activity can drop the device to the launcher.
 */
@AndroidEntryPoint
class AppEventActivity : ComponentActivity() {
    @Inject lateinit var engines: AmpEngines
    @Inject lateinit var server: ServerChoice
    @Inject lateinit var navigator: WindowNavigator
    @Inject lateinit var overrides: LayoutOverrides
    @Inject lateinit var lock: AppLockController
    @Inject lateinit var signOuts: SignOutCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val name = uri?.pathSegments?.firstOrNull()
        if (name != null) {
            val json = uri.getQueryParameter("json")
            val context = AppEventContext(engines, server, navigator, overrides, lock, signOuts, applicationContext)
            events.launch { AppEvents.dispatch(name, json, context) }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    private companion object {
        val events = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
