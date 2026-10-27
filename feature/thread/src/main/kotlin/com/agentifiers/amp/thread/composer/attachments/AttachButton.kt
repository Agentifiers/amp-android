package com.agentifiers.amp.thread.composer.attachments

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpSheet
import com.agentifiers.amp.design.icons.AmpIcons
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface LatestRecording {
    class Found(val uri: Uri, val name: String, val addedAtMs: Long, val thumbnail: Bitmap?) : LatestRecording
    data object Missing : LatestRecording
}

/** The composer's paperclip. Opens the sheet of the six attachment sources. */
@Composable
fun AttachButton(tray: AttachmentTray, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    var sheetOpen by remember { mutableStateOf(false) }
    var screenshot by remember { mutableStateOf<Bitmap?>(null) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    var recordMic by rememberSaveable { mutableStateOf(false) }
    var latest by remember { mutableStateOf<LatestRecording?>(null) }
    val recording by ScreenRecordingService.recording.collectAsStateWithLifecycle()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFile?.let(::File)
        cameraFile = null
        if (file == null) return@rememberLauncherForActivityResult
        if (saved) tray.add(attachmentUri(context, file)) else file.delete()
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> uris.forEach(tray::add) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(tray::add) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenRecordingService.start(context, result.resultCode, data, recordMic, tray.owner)
        }
    }
    val recordPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == false) recordMic = false
        consent.launch(captureIntent(context))
    }
    val videoAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            coroutines.launch { latest = findLatestRecording(context) }
        } else {
            Toast.makeText(context, "Allow access to videos to attach your latest screen recording.", Toast.LENGTH_LONG).show()
        }
    }

    fun close() {
        sheetOpen = false
        screenshot = null
    }

    AmpIconButton(
        icon = AmpIcons.Paperclip,
        contentDescription = "Attach",
        onClick = {
            val activity = context.findActivity()
            if (activity == null) {
                sheetOpen = true
            } else {
                captureWindow(activity) { shot ->
                    screenshot = shot
                    sheetOpen = true
                }
            }
        },
        testTag = ThreadTags.composerAttach,
        modifier = modifier,
    )

    if (sheetOpen) {
        AmpSheet(onDismissRequest = ::close) {
            Column(Modifier.fillMaxWidth().padding(bottom = AmpTheme.spacing.xl)) {
                SheetOption("Camera", ThreadTags.attachCamera) {
                    close()
                    val file = newAttachmentFile(context, "photo", "jpg")
                    cameraFile = file.absolutePath
                    try {
                        camera.launch(attachmentUri(context, file))
                    } catch (e: ActivityNotFoundException) {
                        cameraFile = null
                        Toast.makeText(context, "No camera app is installed.", Toast.LENGTH_SHORT).show()
                    }
                }
                SheetOption("Photos", ThreadTags.attachPhotos) {
                    close()
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                SheetOption("Files", ThreadTags.attachFiles) {
                    close()
                    files.launch(arrayOf("*/*"))
                }
                val shot = screenshot
                SheetOption("Take Screenshot", ThreadTags.attachScreenshot, enabled = shot != null) {
                    close()
                    if (shot != null) coroutines.launch { saveScreenshot(context, shot)?.let(tray::add) }
                }
                if (recording) {
                    SheetOption("Stop Recording", ThreadTags.attachRecord) {
                        close()
                        ScreenRecordingService.stop(context)
                    }
                } else {
                    SheetOption("Record Screen", ThreadTags.attachRecord) {
                        close()
                        recordPermissions.launch(recordingPermissions(context, recordMic))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 40.dp, end = AmpTheme.spacing.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Record Microphone", style = AmpTheme.type.body, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.weight(1f))
                        Switch(checked = recordMic, onCheckedChange = { recordMic = it })
                    }
                }
                SheetOption("Attach Latest Screen Recording", ThreadTags.attachLatestRecording) {
                    close()
                    videoAccess.launch(videoPermissions())
                }
            }
        }
    }

    when (val found = latest) {
        null -> Unit
        LatestRecording.Missing -> AlertDialog(
            onDismissRequest = { latest = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            confirmButton = { AmpButton("OK", { latest = null }, ThreadTags.attachLatestRecordingOk, rank = AmpButtonRank.Primary) },
            title = { Text("No screen recordings") },
            text = { Text("There is no video in this device's screen recordings folder.") },
        )
        is LatestRecording.Found -> AlertDialog(
            onDismissRequest = { latest = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            confirmButton = {
                AmpButton(
                    "Attach",
                    {
                        latest = null
                        tray.add(found.uri)
                    },
                    ThreadTags.attachLatestRecordingAttach,
                    rank = AmpButtonRank.Primary,
                )
            },
            dismissButton = { AmpButton("Cancel", { latest = null }, ThreadTags.attachLatestRecordingCancel, rank = AmpButtonRank.Secondary) },
            title = { Text("Attach latest screen recording?") },
            text = {
                Column {
                    found.thumbnail?.let {
                        Image(
                            it.asImageBitmap(),
                            contentDescription = found.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(bottom = AmpTheme.spacing.sm),
                        )
                    }
                    Text(found.name, style = AmpTheme.type.body, color = AmpTheme.colors.text.ink)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(found.addedAtMs)), style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted)
                }
            },
        )
    }
}

@Composable
private fun SheetOption(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = AmpTheme.type.body,
        color = if (enabled) AmpTheme.colors.text.ink else AmpTheme.colors.text.inkMuted,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AmpTheme.spacing.xl, vertical = 14.dp)
            .testTag(tag),
    )
}

/** Copies the window before the sheet draws over it; [done] gets null when the window has no surface yet. */
private fun captureWindow(activity: Activity, done: (Bitmap?) -> Unit) {
    val window = activity.window
    val view = window.decorView
    if (view.width == 0 || view.height == 0) return done(null)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(window, bitmap, { result -> done(if (result == PixelCopy.SUCCESS) bitmap else null) }, Handler(Looper.getMainLooper()))
    } catch (e: IllegalArgumentException) {
        done(null)
    }
}

private suspend fun saveScreenshot(context: Context, shot: Bitmap): Uri? = withContext(Dispatchers.IO) {
    val file = newAttachmentFile(context, "screenshot", "png")
    try {
        file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        attachmentUri(context, file)
    } catch (e: IOException) {
        file.delete()
        null
    }
}

private fun captureIntent(context: Context) = context.getSystemService(MediaProjectionManager::class.java).let {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        it.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        it.createScreenCaptureIntent()
    }
}

private fun recordingPermissions(context: Context, withMic: Boolean): Array<String> = buildList {
    if (withMic) add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

private fun videoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * The newest video in a screen recordings folder: `DCIM/Screen recordings` (Samsung and others), `ScreenRecorder`
 * folders, or the `screen-<time>.mp4` files the AOSP recorder writes to `Movies/`.
 */
private suspend fun findLatestRecording(context: Context): LatestRecording = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val path = MediaStore.Video.Media.RELATIVE_PATH
    val name = MediaStore.Video.Media.DISPLAY_NAME
    val added = MediaStore.Video.Media.DATE_ADDED
    val selection = "$path LIKE ? OR $path LIKE ? OR ($path LIKE ? AND $name LIKE ?)"
    val args = arrayOf("%Screen recordings%", "%ScreenRecord%", "Movies/%", "screen-%")
    resolver.query(collection, arrayOf(MediaStore.Video.Media._ID, name, added), selection, args, "$added DESC")?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
        val thumbnail = try {
            resolver.loadThumbnail(uri, Size(640, 640), null)
        } catch (e: IOException) {
            null
        }
        LatestRecording.Found(uri, cursor.getString(1) ?: "Screen recording", cursor.getLong(2) * 1000, thumbnail)
    } ?: LatestRecording.Missing
}
