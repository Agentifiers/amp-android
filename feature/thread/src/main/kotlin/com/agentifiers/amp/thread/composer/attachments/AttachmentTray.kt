package com.agentifiers.amp.thread.composer.attachments

import android.app.Activity
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.BundleCompat
import com.agentifiers.amp.model.AttachmentDraft
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.UploadState
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.AttachmentUploads
import java.io.FileNotFoundException
import java.net.URLConnection
import java.util.UUID
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AttachmentMeta(val name: String, val size: Long, val mediaType: String)

/** One chip. [meta] is null until the ContentResolver has been read. */
@Stable
internal class Attachment(val uri: Uri, meta: AttachmentMeta?, upload: UploadState) {
    var meta by mutableStateOf(meta)
    var upload by mutableStateOf(upload)
    var job: Job? = null
}

/** The composer's attachments. Each one starts uploading the moment it is added. */
@Stable
class AttachmentTray internal constructor(
    private val uploads: AttachmentUploads,
    private val thread: ThreadId?,
    private val resolver: ContentResolver,
    private val activity: Activity?,
    private val coroutines: CoroutineScope,
    internal val owner: String,
) {
    private val list = mutableStateListOf<Attachment>()
    internal val items: List<Attachment> get() = list

    val drafts: PersistentList<AttachmentDraft>
        get() = list.map { AttachmentDraft(it.uri.toString(), it.meta?.mediaType ?: OCTET_STREAM, it.upload) }.toPersistentList()

    /** Why Send has to wait, or null when there is nothing to wait for. */
    val waitNotice: String?
        get() {
            if (list.isEmpty()) return null
            val failed = list.count { it.upload is UploadState.Failed }
            if (failed == 1) return "1 attachment failed. Retry or remove it."
            if (failed > 1) return "$failed attachments failed. Retry or remove them."
            val ready = list.count { it.upload is UploadState.Ready }
            return if (ready == list.size) null else "Uploading $ready of ${list.size}"
        }

    fun add(uri: Uri) {
        val item = Attachment(uri, null, UploadState.Pending)
        list += item
        start(item)
    }

    fun clear() {
        list.forEach { it.job?.cancel() }
        list.clear()
    }

    internal fun remove(item: Attachment) {
        item.job?.cancel()
        list.remove(item)
    }

    internal fun canRetry(item: Attachment): Boolean =
        item.upload is UploadState.Failed && (item.meta?.size ?: 0L) <= uploads.maxBytes

    internal fun retry(item: Attachment) {
        if (canRetry(item)) start(item)
    }

    internal fun claimRecordings() {
        ScreenRecordingService.claim(owner).forEach(::add)
    }

    internal val dropTarget = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val drag = event.toAndroidDragEvent()
            val clip = drag.clipData ?: return false
            val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (uris.isEmpty()) return false
            // The grant lives until the activity is destroyed, which outlasts the upload that reads the stream.
            activity?.requestDragAndDropPermissions(drag)
            uris.forEach(::add)
            return true
        }
    }

    private fun start(item: Attachment) {
        item.job?.cancel()
        item.upload = UploadState.Pending
        item.job = coroutines.launch {
            val meta = item.meta ?: readOrNull { readMeta(item.uri) }?.also { item.meta = it }
            when {
                meta == null -> item.upload = UploadState.Failed("Could not read this file")
                meta.size > uploads.maxBytes -> item.upload = UploadState.Failed(tooLarge())
                else -> uploads.upload(thread, meta.name, meta.mediaType, meta.size, bytesOf(item.uri))
                    .catch { emit(UploadState.Failed("Upload failed")) }
                    .collect { item.upload = it }
            }
        }
    }

    private fun tooLarge() = "Too large to attach (limit ${uploads.maxBytes / (1024 * 1024)} MB)"

    private suspend fun readMeta(uri: Uri): AttachmentMeta = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        AttachmentMeta(displayName, size ?: measure(uri), mediaTypeOf(uri, displayName))
    }

    private fun mediaTypeOf(uri: Uri, name: String): String =
        resolver.getType(uri) ?: URLConnection.guessContentTypeFromName(name) ?: OCTET_STREAM

    /** For providers that leave SIZE empty. The upload declares its size up front, so it has to be exact. */
    private fun measure(uri: Uri): Long {
        resolver.openAssetFileDescriptor(uri, "r")?.use { if (it.length != AssetFileDescriptor.UNKNOWN_LENGTH) return it.length }
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("attachment is not readable")
        return input.use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }

    private fun bytesOf(uri: Uri): Flow<ByteArray> = flow {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("attachment is not readable")
        input.use { stream ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) emit(buffer.copyOf(read))
            }
        }
    }.flowOn(Dispatchers.IO)

    internal fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_OWNER, owner)
        putParcelableArrayList(
            KEY_ITEMS,
            list.mapTo(ArrayList()) { item ->
                Bundle().apply {
                    putString(KEY_URI, item.uri.toString())
                    item.meta?.let {
                        putString(KEY_NAME, it.name)
                        putLong(KEY_SIZE, it.size)
                        putString(KEY_TYPE, it.mediaType)
                    }
                    (item.upload as? UploadState.Ready)?.let { putString(KEY_URL, it.url) }
                }
            },
        )
    }

    /** Ready attachments come back as they were; the rest upload again. */
    internal fun restore(saved: Bundle) {
        BundleCompat.getParcelableArrayList(saved, KEY_ITEMS, Bundle::class.java).orEmpty().forEach { b ->
            val uri = Uri.parse(b.getString(KEY_URI))
            val name = b.getString(KEY_NAME)
            val type = b.getString(KEY_TYPE)
            val meta = if (name != null && type != null) AttachmentMeta(name, b.getLong(KEY_SIZE), type) else null
            val url = b.getString(KEY_URL)
            val item = Attachment(uri, meta, if (url != null) UploadState.Ready(url) else UploadState.Pending)
            list += item
            if (url == null) start(item)
        }
    }

    internal companion object {
        fun ownerOf(saved: Bundle): String = requireNotNull(saved.getString(KEY_OWNER))

        private const val OCTET_STREAM = "application/octet-stream"
        private const val CHUNK_BYTES = 64 * 1024
        private const val KEY_OWNER = "owner"
        private const val KEY_ITEMS = "items"
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_SIZE = "size"
        private const val KEY_TYPE = "type"
        private const val KEY_URL = "url"
    }
}

private suspend fun <T> readOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

@Composable
fun rememberAttachmentTray(scope: AccountScope, thread: ThreadId?): AttachmentTray {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val make = { owner: String ->
        AttachmentTray(scope.attachments, thread, context.contentResolver, context.findActivity(), coroutines, owner)
    }
    val saver = Saver<AttachmentTray, Bundle>(
        save = { it.toBundle() },
        restore = { saved -> make(AttachmentTray.ownerOf(saved)).also { it.restore(saved) } },
    )
    val tray = rememberSaveable(scope, thread, saver = saver) { make(UUID.randomUUID().toString()) }
    LaunchedEffect(tray) {
        ScreenRecordingService.finished.collect { if (tray.owner in it) tray.claimRecordings() }
    }
    return tray
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.attachmentDropTarget(tray: AttachmentTray): Modifier =
    dragAndDropTarget(shouldStartDragAndDrop = { it.mimeTypes().isNotEmpty() }, target = tray.dropTarget)
