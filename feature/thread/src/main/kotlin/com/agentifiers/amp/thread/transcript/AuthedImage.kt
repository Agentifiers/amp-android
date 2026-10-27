package com.agentifiers.amp.thread.transcript

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.theme.MediaTokens
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.tagsAsResourceIds
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Coil loads for a transcript image: the URL, whose Bearer the account's loader adds, or the inline bytes. */
fun imageModel(source: ImageSource): Any = when (source) {
    is ImageSource.Url -> source.url
    is ImageSource.Inline -> Base64.decode(source.base64, Base64.DEFAULT)
}

/**
 * An image through the account's authenticated loader. A tap opens the [Lightbox]. One that fails to load is a
 * labeled placeholder whose Open on Web shows the thread where the web can try it.
 */
@Composable
fun AuthedImage(source: ImageSource, loader: ImageLoader, description: String?, onWeb: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }
    if (failed) {
        Column(
            modifier.background(AmpTheme.colors.surfaces.raised).padding(AmpTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Couldn't load image", style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.testTag(ThreadTags.imageBroken))
            AmpButton("Open on Web", onWeb, ThreadTags.imageBrokenOpenWeb, rank = AmpButtonRank.Link)
        }
        return
    }
    AsyncImage(
        model = imageModel(source),
        contentDescription = description,
        imageLoader = loader,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        modifier = modifier.clickable(role = Role.Image, onClickLabel = "Open image") { open = true },
    )
    if (open) Lightbox(source, loader, description, onClose = { open = false })
}

private enum class LightboxBackground(val label: String) { Dark("Dark"), Light("Light"), Checker("Checkerboard") }

/** Full-screen image with pinch zoom, a background toggle for transparent PNGs, Save, and Share. */
@Composable
fun Lightbox(source: ImageSource, loader: ImageLoader, description: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var background by remember { mutableStateOf(LightboxBackground.Dark) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var notice by remember { mutableStateOf<String?>(null) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset += pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val media = AmpTheme.colors.media
        Column(Modifier.fillMaxSize().tagsAsResourceIds().background(media.letterbox).testTag(ThreadTags.lightbox)) {
            Row(Modifier.fillMaxWidth().padding(AmpTheme.spacing.sm), horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                AmpIconButton(AmpIcons.X, "Close", onClose, ThreadTags.lightboxClose)
                Box(Modifier.weight(1f))
                AmpButton(
                    "Background: ${background.label}",
                    { background = LightboxBackground.entries[(background.ordinal + 1) % LightboxBackground.entries.size] },
                    ThreadTags.lightboxBackground,
                    rank = AmpButtonRank.Secondary,
                )
                AmpButton(
                    "Save",
                    { scope.launch { notice = saveImage(context, loader, source)?.let { "Saved to Pictures/Amp" } ?: "Couldn't save the image" } },
                    ThreadTags.lightboxSave,
                    rank = AmpButtonRank.Secondary,
                )
                AmpButton(
                    "Share",
                    {
                        scope.launch {
                            val uri = saveImage(context, loader, source)
                            if (uri == null) {
                                notice = "Couldn't share the image"
                            } else {
                                val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                    ThreadTags.lightboxShare,
                    rank = AmpButtonRank.Secondary,
                )
            }
            notice?.let { Text(it, color = AmpTheme.colors.text.inkMuted, modifier = Modifier.padding(horizontal = AmpTheme.spacing.lg)) }
            Box(
                Modifier.weight(1f).fillMaxWidth().drawBehind { paintBackground(background, media) }.transformable(transform),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = imageModel(source),
                    contentDescription = description,
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }.semantics { contentDescription = description ?: "Image" },
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.paintBackground(background: LightboxBackground, media: MediaTokens) {
    when (background) {
        LightboxBackground.Dark -> drawRect(media.lightboxDark)
        LightboxBackground.Light -> drawRect(media.lightboxLight)
        LightboxBackground.Checker -> {
            val cell = 16.dp.toPx()
            drawRect(media.lightboxLight)
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) 0f else cell
                while (x < size.width) {
                    drawRect(media.lightboxChecker, Offset(x, y), Size(cell, cell))
                    x += cell * 2
                }
                y += cell
                row++
            }
        }
    }
}

/** Renders the image through the account loader and writes it as a PNG to Pictures/Amp. */
private suspend fun saveImage(context: Context, loader: ImageLoader, source: ImageSource): Uri? {
    val request = ImageRequest.Builder(context).data(imageModel(source)).allowHardware(false).build()
    val bitmap = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    return withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "amp-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Amp")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return@withContext null
        uri
    }
}
