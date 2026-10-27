package com.agentifiers.amp.puck.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the process in the foreground for [PuckCalls.current]: a `phoneCall|microphone` foreground service with the
 * CallStyle notification Telecom requires within 5 s of adding the call.
 */
internal class PuckCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hosting: PuckCall? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            PuckCalls.hangUp()
            if (hosting == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val call = PuckCalls.current
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: IllegalStateException) {
            return refuse(call)
        } catch (_: SecurityException) {
            return refuse(call)
        }
        if (call == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (hosting != null) return START_NOT_STICKY
        hosting = call
        scope.launch {
            try {
                call.run { PuckCalls.publish(call, it) }
            } finally {
                PuckCalls.finished(call)
                hosting = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hosting?.hangUp()
        scope.cancel()
    }

    private fun refuse(call: PuckCall?): Int {
        call?.let { PuckCalls.abandon(it, PuckCalls.BACKGROUND_REFUSED) }
        stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Puck calls", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) },
        )
        val hangUp = PendingIntent.getService(this, 0, Intent(this, PuckCallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val puck = Person.Builder().setName("Puck").setImportant(true).build()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(puck, hangUp))
            .build()
    }

    private companion object {
        const val ACTION_HANG_UP = "com.agentifiers.amp.puck.voice.HANG_UP"
        const val CHANNEL = "puck-call"
        const val NOTIFICATION_ID = 0x9c4c
    }
}
