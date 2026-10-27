package com.agentifiers.amp.files

import com.agentifiers.amp.design.AmpTheme
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.agentifiers.amp.design.FilesTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.FileContent
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The viewer's lifecycle for one path. No viewer is open when the state is null. */
internal sealed interface ViewerState {
    val path: String

    data class ConfirmLarge(override val path: String) : ViewerState
    data class Reading(override val path: String) : ViewerState
    data class Shown(override val path: String, val content: FileContent) : ViewerState
    data class Unavailable(override val path: String, val reason: String) : ViewerState
}

private val largeExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tif", "tiff", "heic", "heif", "avif", "psd",
    "mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "mp4", "m4v", "mov", "mkv", "avi", "webm",
    "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "jar", "war", "aar", "apk", "aab", "ipa", "dmg", "iso",
    "db", "sqlite", "sqlite3", "mdb", "parquet", "arrow",
    "bin", "exe", "dll", "so", "dylib", "o", "a", "class", "wasm", "pyc", "pdf", "woff", "woff2", "ttf", "otf", "eot",
)
private val largeFolders = setOf("node_modules", ".git", "build", "dist", "out")

/** The protocol gives no size before a read, so the warning goes by extension and folder. */
internal fun likelyLarge(path: String): Boolean {
    val segments = path.split('/')
    val extension = segments.last().substringAfterLast('.', "").lowercase()
    return extension in largeExtensions || segments.dropLast(1).any { it in largeFolders }
}

internal fun opening(path: String): ViewerState = if (likelyLarge(path)) ViewerState.ConfirmLarge(path) else ViewerState.Reading(path)

private fun FileContent.isMarkdown(): Boolean = this is FileContent.Text &&
    (language.equals("markdown", ignoreCase = true) || path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdx"))

@Composable
internal fun FileViewer(
    state: ViewerState,
    onOpenAnyway: () -> Unit,
    onClose: () -> Unit,
    openFilesOnWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val content = (state as? ViewerState.Shown)?.content
    val payload = remember(content) { content?.payload() }
    val lines = remember(content) { (content as? FileContent.Text)?.let { splitLines(it.text) } }
    var rendered by remember(content) { mutableStateOf(true) }
    var notice by remember(state.path) { mutableStateOf<String?>(null) }
    val saveContract = remember(payload?.mime) { ActivityResultContracts.CreateDocument(payload?.mime ?: OCTET_STREAM) }
    val saver = rememberLauncherForActivityResult(saveContract) { uri ->
        val bytes = payload
        if (uri != null && bytes != null) scope.launch { notice = save(context, uri, bytes) }
    }

    Column(modifier.testTag(FilesTags.viewer)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.subRow).padding(start = AmpTheme.spacing.paneX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary(state.path, lines?.size),
                Modifier.weight(1f).testTag(FilesTags.viewerSummary),
                style = AmpTheme.type.mono,
                color = AmpTheme.colors.text.ink,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            if (content != null && content.isMarkdown()) {
                AmpButton(if (rendered) "Source" else "Rendered", { rendered = !rendered }, FilesTags.markdownToggle)
            }
            if (payload != null) {
                AmpButton("Save", { saver.launch(payload.name) }, FilesTags.save)
                AmpIconButton(AmpIcons.Share2, "Share", { scope.launch { notice = share(context, payload) } }, FilesTags.share)
            }
            AmpIconButton(AmpIcons.X, "Close", onClose, FilesTags.viewerClose)
        }
        notice?.let { Text(it, Modifier.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.xs), color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary) }
        val body = Modifier.fillMaxSize()
        when (state) {
            is ViewerState.ConfirmLarge -> Column(
                body.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.machineTop),
                verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
            ) {
                Text("This file may be large. Opening it reads the whole file over the thread connection.", color = AmpTheme.colors.text.ink, style = AmpTheme.type.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.actionGap)) {
                    AmpButton("Open Anyway", onOpenAnyway, FilesTags.openAnyway, rank = AmpButtonRank.Primary)
                    AmpButton("Cancel", onClose, FilesTags.openCancel)
                }
            }
            is ViewerState.Reading -> Box(body, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AmpTheme.colors.text.inkMuted) }
            is ViewerState.Unavailable -> Box(body, contentAlignment = Alignment.TopCenter) {
                AmpEmptyState(AmpIcons.CircleAlert, "Can't open this file", state.reason, FilesTags.viewerUnavailable) {
                    AmpButton("Open Files on web", openFilesOnWeb, FilesTags.openWeb, rank = AmpButtonRank.Link)
                }
            }
            is ViewerState.Shown -> when (val shown = state.content) {
                is FileContent.Text -> if (shown.isMarkdown() && rendered) {
                    RenderedMarkdown(shown.text, body)
                } else {
                    CodeLines(lines.orEmpty(), shown, body)
                }
                is FileContent.Image -> DecodedImage(shown, body)
                is FileContent.Binary -> Text(
                    "Binary file, ${Formatter.formatShortFileSize(context, shown.sizeBytes)}",
                    body.padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.machineTop),
                    color = AmpTheme.colors.text.inkMuted,
                    style = AmpTheme.type.secondary,
                )
            }
        }
    }
}

@Composable
private fun summary(path: String, lineCount: Int?): AnnotatedString {
    if (lineCount == null) return AnnotatedString(path)
    val count = AmpTheme.type.meta.toSpanStyle().copy(color = AmpTheme.colors.text.inkMuted)
    return buildAnnotatedString {
        append(path)
        withStyle(count) { append(" · $lineCount ${if (lineCount == 1) "line" else "lines"}") }
    }
}

@Composable
private fun CodeLines(lines: List<String>, content: FileContent.Text, modifier: Modifier) {
    val code = AmpTheme.colors.code
    val colored by produceState<List<AnnotatedString>?>(null, lines, code.syntax) {
        value = withContext(Dispatchers.Default) { highlight(lines, grammarFor(content.language, content.path), code.syntax) }
    }
    val gutter = lines.size.toString().length
    LazyColumn(modifier.horizontalScroll(rememberScrollState()).padding(vertical = AmpTheme.spacing.xs)) {
        items(lines.size) { index ->
            Row(Modifier.padding(horizontal = AmpTheme.spacing.paneX)) {
                Text((index + 1).toString().padStart(gutter), style = AmpTheme.type.mono, color = AmpTheme.colors.text.inkFaint)
                Spacer(Modifier.width(AmpTheme.spacing.md))
                Text(colored?.getOrNull(index) ?: AnnotatedString(lines[index]), style = AmpTheme.type.mono, color = code.editorText, softWrap = false, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RenderedMarkdown(text: String, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = AmpTheme.spacing.paneX, vertical = AmpTheme.spacing.machineTop)) {
        Markdown(
            content = text,
            colors = markdownColor(text = AmpTheme.colors.text.ink, codeBackground = AmpTheme.colors.surfaces.raised, inlineCodeBackground = AmpTheme.colors.surfaces.raised, dividerColor = AmpTheme.colors.surfaces.line, tableBackground = AmpTheme.colors.surfaces.pane),
        )
    }
}

private sealed interface Decoded {
    data object Pending : Decoded
    data class Ready(val bitmap: ImageBitmap) : Decoded
    data object Undecodable : Decoded
}

private const val MAX_IMAGE_EDGE = 4096

/** Decodes at a power-of-two sample so a huge image never allocates more than a 4096 px edge. */
private fun decodeBounded(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@Composable
private fun DecodedImage(content: FileContent.Image, modifier: Modifier) {
    val decoded by produceState<Decoded>(Decoded.Pending, content) {
        value = withContext(Dispatchers.Default) { decodeBounded(content.bytes) }?.let { Decoded.Ready(it) } ?: Decoded.Undecodable
    }
    when (val image = decoded) {
        Decoded.Pending -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AmpTheme.colors.text.inkMuted) }
        Decoded.Undecodable -> Text("This image could not be decoded.", modifier.padding(AmpTheme.spacing.lg), color = AmpTheme.colors.text.inkMuted)
        is Decoded.Ready -> Column(modifier.verticalScroll(rememberScrollState())) {
            Image(
                bitmap = image.bitmap,
                contentDescription = fileName(content.path),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
