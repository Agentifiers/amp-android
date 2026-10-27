package com.agentifiers.amp.sync.api

import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.UploadState
import kotlinx.coroutines.flow.Flow

/**
 * `POST /api/attachments/resumable` prepare, a PUT of the bytes to the returned upload URL, then complete
 * (`spikes/protocol/RESULTS.md:22`). The resulting URL needs the account's Bearer to read.
 */
public interface AttachmentUploads {
    /**
     * Cold: each collection uploads once. Emits [UploadState.Uploading] as bytes go out, then ends with
     * [UploadState.Ready] carrying the attachment URL, or [UploadState.Failed]. [thread] is null for a thread
     * that does not exist yet.
     */
    public fun upload(thread: ThreadId?, name: String, mediaType: String, size: Long, bytes: Flow<ByteArray>): Flow<UploadState>

    /** Per-file limit of the default plan (Amp's docs). */
    public val maxBytes: Long
}
