package com.agentifiers.amp.fake

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.UUID

/** Uploaded bytes and their media type, as the attachment URL serves them. */
internal class StoredAttachment(val mediaType: String, val bytes: ByteArray)

/**
 * The S12 resumable upload (`captures/s12-attachment.jsonl:1-5`): `prepare` hands out an upload URL on this server
 * in place of storage.googleapis.com, the PUT stores the bytes, and `complete` names the content URL.
 */
internal class Attachments(private val origin: String) {
    private val uploads = HashMap<String, StoredAttachment>()
    private val stored = HashMap<String, StoredAttachment>()
    private var generation = 1_792_730_223_820_603L

    fun prepare(body: JsonObject): JsonObject {
        val name = body.string("jobID") ?: UUID.randomUUID().toString()
        return json("uploadURL" to "$origin$UPLOAD_PATH?name=$name&uploadType=resumable&ifGenerationMatch=0&upload_id=${UUID.randomUUID()}")
    }

    fun put(name: String, mediaType: String, bytes: ByteArray): JsonObject {
        uploads[name] = StoredAttachment(mediaType, bytes)
        return json("generation" to (generation++).toString(), "name" to name, "size" to bytes.size.toString(), "contentType" to mediaType)
    }

    fun complete(body: JsonObject): JsonObject? {
        val upload = uploads[body.string("jobID")] ?: return null
        val key = key(upload.bytes, body.string("filename") ?: "attachment")
        stored[key] = upload
        return json("url" to "$origin$CONTENT_PATH/$key")
    }

    /** Content a scenario serves without an upload, under [key] from [Companion.key]. */
    fun seed(key: String, attachment: StoredAttachment) {
        stored[key] = attachment
    }

    fun get(key: String): StoredAttachment? = stored[key]

    companion object {
        const val UPLOAD_PATH = "/upload/storage/v1/b/amp-attachments-fake/o"
        const val CONTENT_PATH = "/user-content/attachments"

        /** Amp names attachment content `<sha256 of the bytes>-<filename>` (`captures/s12-attachment.jsonl:5`). */
        fun key(bytes: ByteArray, filename: String): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "-" + filename
    }
}
