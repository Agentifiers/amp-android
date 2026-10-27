package com.agentifiers.amp.thread.composer.attachments

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import androidx.core.content.IntentCompat
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Records the whole device to `cacheDir/attachments/recording-<time>.mp4` while a notification with Stop shows.
 * The finished file goes to [finished] under the owner (an [AttachmentTray.owner]) that started it.
 */
class ScreenRecordingService : Service() {
    private var session: Session? = null

    private class Session(
        val owner: String,
        val file: File,
        val recorder: MediaRecorder,
        val projection: MediaProjection,
        val display: VirtualDisplay,
        val callback: MediaProjection.Callback,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (session == null) begin(intent)
            ACTION_STOP -> if (session == null) stopSelf() else finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    private fun begin(intent: Intent) {
        val owner = intent.getStringExtra(EXTRA_OWNER)
        val consent = IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT, Intent::class.java)
        if (owner == null || consent == null) {
            stopSelf()
            return
        }
        val withMic = intent.getBooleanExtra(EXTRA_MIC, false)
        // Android 14 requires the mediaProjection foreground service to run before getMediaProjection.
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            (if (withMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTIFICATION_ID, notification(), type)

        val mode = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        val scale = minOf(1f, MAX_EDGE.toFloat() / max(mode.physicalWidth, mode.physicalHeight))
        val width = alignTo16(mode.physicalWidth * scale)
        val height = alignTo16(mode.physicalHeight * scale)
        val dpi = resources.displayMetrics.densityDpi
        val file = newAttachmentFile(this, "recording", "mp4")

        val recorder = MediaRecorder(this)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() = finish()
        }
        var projection: MediaProjection? = null
        var display: VirtualDisplay? = null
        try {
            recorder.apply {
                if (withMic) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8_000_000)
                if (withMic) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44_100)
                    setAudioEncodingBitRate(128_000)
                }
                prepare()
            }
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val granted = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, consent)
                ?: error("screen capture consent was not granted")
            projection = granted
            granted.registerCallback(callback, Handler(Looper.getMainLooper()))
            val mirror = granted.createVirtualDisplay(
                "amp-screen-recording", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null,
            ) ?: error("the system refused a virtual display for recording")
            display = mirror
            recorder.start()
            session = Session(owner, file, recorder, granted, mirror, callback)
            active.value = true
        } catch (e: Exception) {
            display?.release()
            projection?.unregisterCallback(callback)
            projection?.stop()
            recorder.release()
            file.delete()
            stopSelf()
        }
    }

    /** Safe to call more than once: Stop, the system's own stop-sharing chip, and onDestroy all land here. */
    private fun finish() {
        val ending = session ?: return
        session = null
        val recorded = try {
            ending.recorder.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        ending.recorder.release()
        ending.display.release()
        ending.projection.unregisterCallback(ending.callback)
        ending.projection.stop()
        if (recorded) {
            val uri = attachmentUri(this, ending.file)
            pending.update { it + (ending.owner to it[ending.owner].orEmpty() + uri) }
        } else {
            ending.file.delete()
        }
        active.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Recording screen")
            .setContentText("Stop to attach the video to your message.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build())
            .build()
    }

    private fun alignTo16(value: Float): Int = max(16, (value.toInt() / 16) * 16)

    companion object {
        private const val ACTION_START = "com.agentifiers.amp.attachments.RECORD_START"
        private const val ACTION_STOP = "com.agentifiers.amp.attachments.RECORD_STOP"
        private const val EXTRA_OWNER = "owner"
        private const val EXTRA_CONSENT = "consent"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_MIC = "mic"
        private const val CHANNEL = "screen-recording"
        private const val NOTIFICATION_ID = 0x5C4EE7
        private const val MAX_EDGE = 1920

        private val pending = MutableStateFlow<Map<String, List<Uri>>>(emptyMap())
        private val active = MutableStateFlow(false)

        /** Finished recordings not yet taken by their tray, keyed by owner. */
        internal val finished: StateFlow<Map<String, List<Uri>>> = pending.asStateFlow()
        internal val recording: StateFlow<Boolean> = active.asStateFlow()

        internal fun claim(owner: String): List<Uri> = pending.getAndUpdate { it - owner }[owner].orEmpty()

        internal fun start(context: Context, resultCode: Int, consent: Intent, withMic: Boolean, owner: String) {
            val intent = Intent(context, ScreenRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_CONSENT, consent)
                .putExtra(EXTRA_MIC, withMic)
                .putExtra(EXTRA_OWNER, owner)
            context.startForegroundService(intent)
        }

        internal fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
