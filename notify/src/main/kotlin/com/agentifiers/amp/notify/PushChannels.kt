package com.agentifiers.amp.notify

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.sync.api.BackgroundAccessProvider
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.PushChannel
import com.agentifiers.amp.sync.api.RelayRoute
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import kotlin.coroutines.resume

/** How this device gets Web Push, picked once per process (`notifications.md`, "Amp sends Web Push"). */
internal sealed interface DeviceChannel {
    /**
     * A relay in the build (`amp.pushRelayUrl`), Play services, and a Firebase config from `app/google-services.json`:
     * the relay forwards over FCM.
     */
    data object Relay : DeviceChannel

    /** No relay path, but a UnifiedPush distributor such as ntfy is installed. */
    data object UnifiedPush : DeviceChannel

    /**
     * Neither: the poll worker and Watch are the only paths until the user installs a distributor. An account on a
     * server that takes its push itself (`:fake-amp` in a debug build) still registers.
     */
    data object None : DeviceChannel

    companion object {
        fun pick(relay: Boolean, firebase: Boolean, playServices: Boolean, distributor: Boolean): DeviceChannel = when {
            relay && firebase && playServices -> Relay
            distributor -> UnifiedPush
            else -> None
        }
    }
}

internal object PushRegistrar {
    private fun pick(context: Context, provider: BackgroundAccessProvider): DeviceChannel = DeviceChannel.pick(
        relay = provider.hasPushRelay,
        firebase = firebaseOptions() != null,
        playServices = playServicesEnabled(context),
        distributor = UnifiedPush.getDistributors(context).isNotEmpty(),
    )

    /** Registers every signed-in account on the device's channel, now and whenever one signs in. */
    fun start(context: Context, provider: BackgroundAccessProvider, scope: CoroutineScope) {
        val channel = pick(context, provider)
        Log.i(PushInbox.TAG, "push channel: ${channel::class.simpleName}")
        scope.launch {
            val registered = HashSet<AccountId>()
            provider.accounts.collect { accounts ->
                for (account in accounts - registered) {
                    registered += account
                    when (channel) {
                        DeviceChannel.Relay -> launch { registerRelay(context, provider, account) }
                        DeviceChannel.UnifiedPush -> UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { ok ->
                            if (ok) UnifiedPush.register(context, account.raw)
                        }
                        DeviceChannel.None -> launch { register(provider, account, PushChannel.None) }
                    }
                }
            }
        }
    }

    /**
     * A relay registration that fails for a reason that can pass (no network yet, a VPN reconnecting, the relay
     * unreachable) is tried again after [RETRY_DELAYS_MS]; a refusal is final.
     */
    private suspend fun registerRelay(context: Context, provider: BackgroundAccessProvider, account: AccountId) {
        for (wait in RETRY_DELAYS_MS) {
            val token = fcmToken(context)
            val outcome = token?.let { register(provider, account, PushChannel.Relay(it)) }
            if (outcome is Outcome.Ok || outcome is Outcome.Refused) return
            Log.i(PushInbox.TAG, "push: retrying registration in ${wait / 1000}s")
            delay(wait)
        }
    }

    suspend fun register(provider: BackgroundAccessProvider, account: AccountId, channel: PushChannel): Outcome<String> {
        val background = provider.forAccount(account)
        val registered = background.ensurePushRegistration(channel)
        when (registered) {
            is Outcome.Ok -> when (val added = background.reconcilePushSubscriptions()) {
                is Outcome.Ok -> Log.i(PushInbox.TAG, "push: registered, ${added.value} subscription(s) added")
                else -> Log.w(PushInbox.TAG, "push: registered, subscribing failed: $added")
            }
            else -> Log.w(PushInbox.TAG, "push: registration failed: $registered")
        }
        return registered
    }

    private val RETRY_DELAYS_MS = longArrayOf(10_000, 30_000, 120_000, 600_000)

    private fun playServicesEnabled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Firebase from the build's `google-services.json` values, or null when the build has none. */
    private fun firebaseOptions(): FirebaseOptions? {
        if (BuildConfig.FIREBASE_APP_ID.isEmpty()) return null
        return FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
    }

    private suspend fun fcmToken(context: Context): String? {
        val options = firebaseOptions() ?: return null
        ensureFirebase(context, options)
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    continuation.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }
    }

    private val firebaseInit = Any()

    /**
     * Registrations for several accounts ask for the token at once, so the check and the init must not race: a second
     * `initializeApp` for the default app throws "FirebaseApp name [DEFAULT] already exists!".
     */
    private fun ensureFirebase(context: Context, options: FirebaseOptions) = synchronized(firebaseInit) {
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, options)
    }
}

/** The relay's FCM data messages `{v, rid, body | wake}`, and FCM token rotation. */
class RelayMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(PushInbox.TAG, "relay: ${if (message.data["wake"] == "1") "wake" else "body"} received")
        when (val route = NotifyRuntime.provider.routeRelayMessage(message.data)) {
            is RelayRoute.Deliver -> runBlocking { withTimeoutOrNull(9_000) { PushInbox.deliver(route.account, route.body) } }
            is RelayRoute.Dropped -> Log.i(PushInbox.TAG, "relay: dropped, ${route.why}")
        }
    }

    override fun onNewToken(token: String) {
        NotifyRuntime.scope.launch { NotifyRuntime.provider.onFcmToken(token) }
    }
}

/**
 * UnifiedPush delivery, one instance per account (instance = account id). Amp encrypts to :sync's keys, not the
 * connector's, so the connector cannot decrypt and hands over the raw body, which goes to :sync like a relay body.
 */
class AmpUnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        NotifyRuntime.scope.launch { PushRegistrar.register(NotifyRuntime.provider, AccountId(instance), PushChannel.UnifiedPush(endpoint.url)) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (message.decrypted) return
        NotifyRuntime.scope.launch { PushInbox.deliver(AccountId(instance), message.content) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(PushInbox.TAG, "unifiedpush: registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        Log.i(PushInbox.TAG, "unifiedpush: unregistered")
    }
}
