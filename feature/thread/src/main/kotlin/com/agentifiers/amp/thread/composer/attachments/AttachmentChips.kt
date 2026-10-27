package com.agentifiers.amp.thread.composer.attachments

import com.agentifiers.amp.design.AmpTheme
import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.model.UploadState
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One chip per attachment: thumbnail, name, size, upload progress, Retry when it failed, and Remove. */
@Composable
fun AttachmentChips(tray: AttachmentTray, modifier: Modifier = Modifier) {
    val items = tray.items
    if (items.isEmpty()) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = AmpTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
    ) {
        items.forEachIndexed { index, item -> Chip(tray, item, index) }
    }
}

@Composable
private fun Chip(tray: AttachmentTray, item: Attachment, index: Int) {
    val meta = item.meta
    val upload = item.upload
    Row(
        Modifier
            .width(220.dp)
            .clip(AmpTheme.shapes.control)
            .background(AmpTheme.colors.surfaces.raised)
            .then(if (upload is UploadState.Failed) Modifier.border(AmpTheme.metrics.divider, AmpTheme.colors.status.danger, AmpTheme.shapes.control) else Modifier)
            .padding(AmpTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item, meta?.mediaType)
        Column(Modifier.weight(1f).padding(horizontal = AmpTheme.spacing.sm)) {
            Text(
                meta?.name ?: item.uri.lastPathSegment.orEmpty(),
                style = AmpTheme.type.label, color = AmpTheme.colors.text.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            meta?.let { Text(formatSize(it.size), style = AmpTheme.type.meta, color = AmpTheme.colors.text.inkMuted) }
            when (upload) {
                UploadState.Pending -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = AmpTheme.spacing.xs), color = AmpTheme.colors.accents.accent)
                is UploadState.Uploading -> LinearProgressIndicator(
                    progress = { upload.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = AmpTheme.spacing.xs),
                    color = AmpTheme.colors.accents.accent,
                )
                is UploadState.Failed -> Text(upload.reason, style = AmpTheme.type.meta, color = AmpTheme.colors.status.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                is UploadState.Ready -> Unit
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (tray.canRetry(item)) AmpButton("Retry", { tray.retry(item) }, ThreadTags.attachmentRetry(index), rank = AmpButtonRank.Tertiary)
            AmpButton("Remove", { tray.remove(item) }, ThreadTags.attachmentRemove(index), rank = AmpButtonRank.Secondary)
        }
    }
}

@Composable
private fun Thumbnail(item: Attachment, mediaType: String?) {
    val resolver = LocalContext.current.contentResolver
    val visual = mediaType != null && (mediaType.startsWith("image/") || mediaType.startsWith("video/"))
    val bitmap by produceState<Bitmap?>(null, item.uri, visual) {
        value = if (visual) loadThumbnail(resolver, item) else null
    }
    val shape = AmpTheme.shapes.control
    Box(Modifier.size(40.dp).clip(shape).background(AmpTheme.colors.surfaces.line), contentAlignment = Alignment.Center) {
        val thumb = bitmap
        if (thumb != null) {
            Image(thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp))
        } else {
            Text(extensionOf(item).uppercase(Locale.ROOT).take(4), style = AmpTheme.type.label, color = AmpTheme.colors.text.inkMuted)
        }
    }
}

/** Downsampled by the provider or by ImageDecoder inside loadThumbnail, so a 50 MP photo never decodes at full size. */
private suspend fun loadThumbnail(resolver: ContentResolver, item: Attachment): Bitmap? = withContext(Dispatchers.IO) {
    try {
        resolver.loadThumbnail(item.uri, Size(THUMB_PX, THUMB_PX), null)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

private fun extensionOf(item: Attachment): String =
    (item.meta?.name ?: item.uri.lastPathSegment.orEmpty()).substringAfterLast('.', "")

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private const val THUMB_PX = 160
