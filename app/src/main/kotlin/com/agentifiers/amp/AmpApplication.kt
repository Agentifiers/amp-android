package com.agentifiers.amp

import android.app.Application
import android.util.Log
import com.agentifiers.amp.store.AccountDatabases
import com.agentifiers.amp.store.AppPreferences
import com.agentifiers.amp.sync.api.AmpEngines
import com.agentifiers.amp.sync.api.EngineConfig
import com.agentifiers.amp.sync.api.EngineParts
import com.agentifiers.amp.sync.api.LeaseIssuer
import com.agentifiers.amp.sync.api.SignInResult
import com.agentifiers.amp.surfaces.FleetSurfaces
import com.agentifiers.amp.web.WebProfiles
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Builds the engine once and splits it: EngineModule hands [EngineParts.engines] and [EngineParts.background]
 * to Hilt, and [leases] stays here, `internal`, so only :app's VisibleThreadLeases can issue a viewer token.
 */
@HiltAndroidApp
class AmpApplication : Application() {
    @Inject lateinit var databases: AccountDatabases
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var server: ServerChoice
    @Inject lateinit var lock: AppLockController

    internal lateinit var parts: EngineParts
        private set
    internal val leases: LeaseIssuer get() = parts.leases
    internal lateinit var surfaces: FleetSurfaces
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        WebProfiles.start(this)
        registerActivityLifecycleCallbacks(lock)
        parts = AmpEngines.create(
            this,
            databases,
            prefs,
            EngineConfig(
                defaultServer = ServerChoice.Production,
                rivetPublishableToken = BuildConfig.RIVET_PUBLISHABLE_TOKEN,
                clientVersion = BuildConfig.VERSION_NAME,
                pushRelayUrl = BuildConfig.PUSH_RELAY_URL,
                networkLog = BuildConfig.DEBUG,
            ),
        )
        surfaces = FleetSurfaces(this, parts.engines).also { it.start() }
    }

    /**
     * Debug builds sign in from `files/import-token` so automation never puts a token on a command line or in
     * logcat. The file is deleted as soon as it is read, before the token is checked.
     */
    fun importDebugToken() {
        if (!BuildConfig.DEBUG) return
        val file = File(filesDir, "import-token")
        if (!file.exists()) return
        scope.launch {
            val token = file.readText()
            file.delete()
            val outcome = when (val result = parts.engines.signIn.withToken(server.current.value, token)) {
                is SignInResult.Accepted -> "accepted"
                is SignInResult.Rejected -> "rejected: ${result.reason}"
            }
            Log.i("AmpImport", "import-token $outcome")
        }
    }
}
