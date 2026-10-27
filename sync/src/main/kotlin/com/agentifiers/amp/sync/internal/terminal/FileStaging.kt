package com.agentifiers.amp.sync.internal.terminal

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.coroutines.flow.Flow

/** `POST /api/attachments/resumable` (terminal.md, "File upload by paste or drop", step 2). Ok holds the attachment URL. */
internal fun interface AttachmentUpload {
    suspend fun upload(thread: ThreadId?, name: String, mediaType: String, size: Long, bytes: Flow<ByteArray>, progress: (Float) -> Unit): Outcome<String>
}

/** The web's file name rules (6cc0622c.js:986): a cleaned name has no spaces, so the inserted path needs no quoting. */
internal fun cleanFileName(name: String, mediaType: String): String {
    val cleaned = name.replace(OUTSIDE, "-").replace(LEADING, "").take(MAX_NAME)
    if (cleaned.isNotEmpty() && !cleaned.all { it == '.' }) return cleaned
    val base = if (mediaType.startsWith("image/")) "image" else "file"
    return EXTENSIONS[mediaType]?.let { "$base.$it" } ?: base
}

private val OUTSIDE = Regex("[^A-Za-z0-9._-]+")
private val LEADING = Regex("^[-.]+")
private const val MAX_NAME = 64
private val EXTENSIONS = mapOf(
    "application/json" to "json", "application/pdf" to "pdf", "application/zip" to "zip", "image/avif" to "avif",
    "image/bmp" to "bmp", "image/gif" to "gif", "image/jpeg" to "jpg", "image/png" to "png", "image/svg+xml" to "svg",
    "image/tiff" to "tiff", "image/webp" to "webp", "text/csv" to "csv", "text/markdown" to "md", "text/plain" to "txt",
)
