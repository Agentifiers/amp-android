package com.agentifiers.amp.debug

import org.json.JSONObject
import java.security.MessageDigest

/**
 * `files-stored {threadId, path}`: logs the size and SHA-256 of the bytes the running :fake-amp scenario holds
 * for that workspace path, so a flow's upload can be checked against the source file byte for byte.
 */
object FilesStored : AppEventHandler {
    override val name = "files-stored"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val thread = payload.requireString("threadId")
        val path = payload.requireString("path")
        val server = UseServer.running ?: throw BadPayload("no :fake-amp scenario is running")
        val bytes = server.workspaceFile(thread, path) ?: return "$path: not stored"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "$path: size=${bytes.size} sha256=$digest"
    }
}
