package com.agentifiers.amp.sync.internal.attachments

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.UploadState
import com.agentifiers.amp.sync.api.AttachmentUploads
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.net.Bearer
import com.agentifiers.amp.sync.internal.net.BearerTokens
import com.agentifiers.amp.sync.internal.net.Endpoint
import com.agentifiers.amp.sync.internal.wire.AmpJson
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The S12 resumable upload (`captures/s12-attachment.jsonl:1-5`): `prepare` names an upload URL, one PUT sends the
 * bytes there, and `complete` with the PUT's `generation` returns the attachment URL. Bearer goes on the two `/api`
 * POSTs only; the upload URL is another origin. A 401 on a POST refreshes the account's token and retries it once.
 */
internal class AttachmentUploader(
    private val client: OkHttpClient,
    private val endpoint: Endpoint,
    private val tokens: BearerTokens,
) {
    suspend fun upload(
        thread: ThreadId?,
        name: String,
        mediaType: String,
        size: Long,
        bytes: Flow<ByteArray>,
        progress: (Float) -> Unit = {},
    ): Outcome<String> {
        if (size > MAX_BYTES) return Outcome.Refused("Too large to attach (limit 100 MB)")
        progress(0f)
        val job = buildJsonObject {
            put("jobID", UUID.randomUUID().toString())
            put("mediaType", mediaType)
            put("sizeBytes", size)
            put("filename", name)
            thread?.let { put("threadID", it.raw) }
        }
        val prepared = when (val answer = post(job, "prepare")) {
            is Outcome.Ok -> answer.value
            else -> return answer.failure()
        }
        val target = prepared.string("uploadURL")?.toHttpUrlOrNull() ?: return Outcome.Failed(EngineError.Drift("attachments prepare"))
        val generation = when (val stored = put(target, mediaType, size, bytes, progress)) {
            is Outcome.Ok -> stored.value
            else -> return stored.failure()
        }
        val completed = when (val answer = post(JsonObject(job + ("generation" to JsonPrimitive(generation))), "complete")) {
            is Outcome.Ok -> answer.value
            else -> return answer.failure()
        }
        return completed.string("url")?.let { Outcome.Ok(it) } ?: Outcome.Failed(EngineError.Drift("attachments complete"))
    }

    private suspend fun post(job: JsonObject, action: String): Outcome<JsonObject> {
        val body = JsonObject(job + ("action" to JsonPrimitive(action)))
        val first = tokens.current()
        val answer = post(body, first)
        val unauthorized = answer is Outcome.Failed && answer.error == EngineError.Unauthorized
        return if (unauthorized && first is Bearer.Token) post(body, tokens.rejected(first.value)) else answer
    }

    private suspend fun post(body: JsonObject, bearer: Bearer): Outcome<JsonObject> {
        val token = when (bearer) {
            is Bearer.Token -> bearer.value
            Bearer.SignInNeeded -> return Outcome.Failed(EngineError.Unauthorized)
            is Bearer.Unavailable -> return Outcome.Failed(EngineError.Offline)
        }
        val request = Request.Builder()
            .url(endpoint.base.newBuilder().addPathSegments("api/attachments/resumable").build())
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return exchange(request) { response ->
            val json = parse(response.body.string())
            when {
                response.code == 401 -> Outcome.Failed(EngineError.Unauthorized)
                response.isSuccessful && json != null -> Outcome.Ok(json)
                else -> Outcome.Failed(EngineError.Server("HTTP ${response.code}", json?.string("error") ?: "The upload was refused."))
            }
        }
    }

    /** The upload URL answers with the stored object's metadata; its `generation` is what `complete` checks. */
    private suspend fun put(target: HttpUrl, mediaType: String, size: Long, bytes: Flow<ByteArray>, progress: (Float) -> Unit): Outcome<String> {
        val request = Request.Builder().url(target).put(StreamedBody(bytes, size, mediaType.toMediaTypeOrNull(), progress)).build()
        return exchange(request) { response ->
            val generation = parse(response.body.string())?.get("generation")?.let { it as? JsonPrimitive }?.content
                ?: response.header("x-goog-generation")
            when {
                !response.isSuccessful -> Outcome.Failed(EngineError.Server("HTTP ${response.code}", "The upload storage refused the file."))
                generation == null -> Outcome.Failed(EngineError.Drift("attachment upload without a generation"))
                else -> Outcome.Ok(generation)
            }
        }
    }

    private suspend fun <T> exchange(request: Request, read: (Response) -> Outcome<T>): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await().use(read)
        } catch (e: UnreadableSource) {
            Outcome.Refused("Couldn't read the file: ${e.cause?.message ?: "it is no longer available"}")
        } catch (_: IOException) {
            Outcome.Failed(EngineError.Offline)
        }
    }

    private fun Outcome<*>.failure(): Outcome<Nothing> = when (this) {
        is Outcome.Ok -> error("not a failure")
        is Outcome.Failed -> this
        is Outcome.Refused -> this
        Outcome.Unknown -> Outcome.Unknown
    }

    /**
     * Streams [bytes] as the PUT body with a known length. One-shot, because a retried PUT would read the source
     * twice. A failure of the source is wrapped so it is not mistaken for a network failure.
     */
    private class StreamedBody(
        private val bytes: Flow<ByteArray>,
        private val size: Long,
        private val type: MediaType?,
        private val progress: (Float) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = size
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            runBlocking {
                bytes.catch { throw UnreadableSource(it) }.collect { chunk ->
                    sink.write(chunk)
                    sent += chunk.size
                    progress(if (size > 0) (sent.toFloat() / size).coerceAtMost(1f) else 1f)
                }
            }
        }
    }

    private class UnreadableSource(cause: Throwable) : IOException(cause)

    companion object {
        /** The default plan's per-file limit (Amp's docs), in binary megabytes. */
        const val MAX_BYTES: Long = 100L * 1024 * 1024
        private val JSON = "application/json".toMediaType()

        private fun parse(text: String): JsonObject? = try {
            AmpJson.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }
}

/** [AttachmentUploads] for the UI: each collection runs one [AttachmentUploader.upload] and reports it as [UploadState]s. */
internal class AttachmentUploadsEngine(private val uploader: AttachmentUploader) : AttachmentUploads {
    override val maxBytes: Long = AttachmentUploader.MAX_BYTES

    override fun upload(thread: ThreadId?, name: String, mediaType: String, size: Long, bytes: Flow<ByteArray>): Flow<UploadState> =
        channelFlow {
            val outcome = uploader.upload(thread, name, mediaType, size, bytes) { trySend(UploadState.Uploading(it)) }
            send(
                when (outcome) {
                    is Outcome.Ok -> UploadState.Ready(outcome.value)
                    is Outcome.Refused -> UploadState.Failed(outcome.why)
                    is Outcome.Failed -> UploadState.Failed(describe(outcome.error))
                    Outcome.Unknown -> UploadState.Failed("The upload may not have finished. Try again.")
                },
            )
        }.buffer(Channel.CONFLATED)

    private fun describe(error: EngineError): String = when (error) {
        EngineError.Offline -> "No connection. Try again."
        EngineError.Unauthorized -> "Amp no longer accepts this account's sign-in."
        is EngineError.Server -> "Upload failed: ${error.message}"
        is EngineError.Drift -> "Amp answered the upload in a way this app doesn't understand."
        else -> "Upload failed."
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, closing, _ -> closing.close() }
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
    })
}
