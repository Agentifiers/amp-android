package com.agentifiers.amp.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.agentifiers.amp.model.FileContent
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Files
import com.agentifiers.amp.sync.api.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLConnection

private const val CHUNK_BYTES = 64 * 1024
internal const val OCTET_STREAM = "application/octet-stream"

/** A document the user picked to upload into [dir]. */
internal data class Pick(val uri: Uri, val name: String, val size: Long, val dir: String)

/** The upload control's lifecycle. Exactly one of these at a time. */
internal sealed interface Transfer {
    data object Idle : Transfer
    data class Sending(val pick: Pick) : Transfer
    data class AskReplace(val pick: Pick) : Transfer
    data class Settled(val message: String, val offerWeb: Boolean) : Transfer
}

internal fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

internal fun describe(error: EngineError): String = when (error) {
    EngineError.Offline -> "The thread is offline."
    EngineError.Unauthorized -> "This account needs to sign in again."
    EngineError.StepUpRequired -> "This action needs a fresh sign-in."
    EngineError.PaymentRequired -> "This workspace is out of credits."
    is EngineError.NotFound -> "${error.what} was not found."
    is EngineError.Server -> error.message.ifBlank { error.code }
    is EngineError.Drift -> "The runner answered in a shape the app does not understand (${error.where})."
    else -> error.toString()
}

/** Name and size from the provider. A null size means the provider would not say, and the upload cannot start. */
internal suspend fun ContentResolver.describePick(uri: Uri): Pair<String, Long?> = withContext(Dispatchers.IO) {
    var name: String? = null
    var size: Long? = null
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
        if (row.moveToFirst()) {
            name = row.getString(0)
            size = if (row.isNull(1)) null else row.getLong(1)
        }
    }
    val known = size ?: try {
        openAssetFileDescriptor(uri, "r")?.use { fd -> fd.length.takeIf { it >= 0 } }
    } catch (e: FileNotFoundException) {
        null
    }
    (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload") to known
}

private fun ContentResolver.chunks(uri: Uri): Flow<ByteArray> = flow {
    val input = openInputStream(uri) ?: throw FileNotFoundException("The provider returned no stream.")
    input.use {
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            if (read > 0) emit(buffer.copyOf(read))
        }
    }
}.flowOn(Dispatchers.IO)

internal suspend fun upload(files: Files, resolver: ContentResolver, pick: Pick, overwrite: Boolean): Transfer = try {
    when (val outcome = files.upload(pick.dir, pick.name, resolver.chunks(pick.uri), pick.size, overwrite)) {
        is Outcome.Ok -> Transfer.Settled("Uploaded ${outcome.value}", offerWeb = false)
        is Outcome.Failed -> {
            val exists = (outcome.error as? EngineError.Server)?.code == "ALREADY_EXISTS"
            if (exists && !overwrite) Transfer.AskReplace(pick) else Transfer.Settled(describe(outcome.error), offerWeb = true)
        }
        Outcome.Unknown -> Transfer.Settled("Upload sent, but the connection dropped before the runner answered.", offerWeb = false)
        is Outcome.Refused -> Transfer.Settled(outcome.why, offerWeb = false)
    }
} catch (e: IOException) {
    Transfer.Settled("Could not read ${pick.name}: ${e.message}", offerWeb = false)
} catch (e: SecurityException) {
    Transfer.Settled("The app lost access to ${pick.name}.", offerWeb = false)
}

/** Bytes the app already holds for Save and Share. */
internal class Payload(val name: String, val mime: String, val bytes: ByteArray)

internal fun FileContent.payload(): Payload = when (this) {
    is FileContent.Text -> Payload(fileName(path), mimeOf(path) ?: "text/plain", text.encodeToByteArray())
    is FileContent.Image -> Payload(fileName(path), mimeOf(path) ?: OCTET_STREAM, bytes)
    is FileContent.Binary -> Payload(fileName(path), mimeOf(path) ?: OCTET_STREAM, bytes)
}

private fun mimeOf(path: String): String? = URLConnection.guessContentTypeFromName(fileName(path))

internal suspend fun save(context: Context, uri: Uri, payload: Payload): String = withContext(Dispatchers.IO) {
    try {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("The provider returned no stream.")
        out.use { it.write(payload.bytes) }
        "Saved ${payload.name}"
    } catch (e: IOException) {
        "Could not save ${payload.name}: ${e.message}"
    }
}

/** Writes the bytes under cacheDir/shared, the only directory the files provider serves, and opens the share sheet. */
internal suspend fun share(context: Context, payload: Payload): String? {
    val file = try {
        withContext(Dispatchers.IO) {
            File(context.cacheDir, "shared").apply { mkdirs() }.resolve(payload.name).apply { writeBytes(payload.bytes) }
        }
    } catch (e: IOException) {
        return "Could not share ${payload.name}: ${e.message}"
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(payload.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, payload.name))
    return null
}
