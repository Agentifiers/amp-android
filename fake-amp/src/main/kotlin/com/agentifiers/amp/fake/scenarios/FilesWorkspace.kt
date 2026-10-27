package com.agentifiers.amp.fake.scenarios

import com.agentifiers.amp.fake.Capture
import com.agentifiers.amp.fake.array
import com.agentifiers.amp.fake.bool
import com.agentifiers.amp.fake.json
import com.agentifiers.amp.fake.obj
import com.agentifiers.amp.fake.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** The directory the S19 executor ran in (`captures/s19-files.jsonl:34`). */
internal const val S19_ROOT = "/private/tmp/amp-scratch"

/**
 * An executor's files, in memory, for the `client_filesystem_*` requests that carry a `requestId` (S19).
 * Paths are relative to [root]; `.`, relative paths, and absolute paths under [root] all resolve. Listings keep
 * the order entries were added, as the recorded listing is unsorted. Runs on the World loop only.
 */
internal class Workspace(private val root: String) {
    private sealed interface Node {
        data object Dir : Node
        class File(val bytes: ByteArray, val meta: JsonObject) : Node
    }

    /** How the actor answers one request: a `*_result` body for the client's `requestId`, or a JSON-RPC error. */
    sealed interface Answer {
        class Result(val type: String, val body: JsonObject) : Answer
        data object InvalidParams : Answer
    }

    private val nodes = LinkedHashMap<String, Node>()

    fun dir(path: String) {
        ancestors(path).forEach { nodes.putIfAbsent(it, Node.Dir) }
        nodes[path] = Node.Dir
    }

    fun put(path: String, bytes: ByteArray, meta: JsonObject = json("mtimeMs" to System.currentTimeMillis())) {
        ancestors(path).forEach { nodes.putIfAbsent(it, Node.Dir) }
        nodes[path] = Node.File(bytes, meta)
    }

    fun bytes(path: String): ByteArray? = resolve(path)?.let { nodes[it] as? Node.File }?.bytes

    /** null for a method this workspace does not serve, so the actor falls back to its recordings. */
    fun answer(method: String, params: JsonObject): Answer? {
        if (method !in METHODS) return null
        if (params.string("requestId") == null) return Answer.InvalidParams
        val requested = params.string("path") ?: return Answer.InvalidParams
        val path = resolve(requested)
        return when (method) {
            READ_DIRECTORY -> Answer.Result(
                "client_filesystem_read_directory_result",
                if (path != null && isDir(path)) json("ok" to true, "entries" to listing(path)) else notFound(requested),
            )
            READ_FILE -> {
                if ("range" in params) return Answer.InvalidParams
                val file = path?.let { nodes[it] as? Node.File }
                val body = if (file != null) {
                    json("ok" to true, "contentBase64" to Base64.getEncoder().encodeToString(file.bytes))
                } else {
                    notFound(requested)
                }
                Answer.Result("client_filesystem_read_file_result", body)
            }
            else -> {
                val content = params.string("contentBase64")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                    ?: return Answer.InvalidParams
                Answer.Result("client_filesystem_write_file_result", write(path, requested, content, params.bool("overwrite") == true))
            }
        }
    }

    private fun write(path: String?, requested: String, content: ByteArray, overwrite: Boolean): JsonObject {
        if (path == null || ancestors(path).any { nodes[it] is Node.File }) return notFound(requested)
        val existing = nodes[path]
        if (path.isEmpty() || existing is Node.Dir || (existing != null && !overwrite)) {
            return json("ok" to false, "error" to json("code" to "ALREADY_EXISTS", "message" to "File already exists"))
        }
        put(path, content)
        return json("ok" to true)
    }

    private fun isDir(path: String): Boolean = path.isEmpty() || nodes[path] == Node.Dir

    private fun listing(dir: String): List<JsonElement> = nodes.entries.filter { parent(it.key) == dir }.map { (path, node) ->
        val name = JsonPrimitive(path.substringAfterLast('/'))
        when (node) {
            Node.Dir -> JsonArray(listOf(name, JsonPrimitive("directory"), JsonNull))
            is Node.File -> JsonArray(listOf(name, JsonPrimitive("file"), node.meta))
        }
    }

    /** [root]-relative with `.` and `..` folded, `""` for the root itself, or null outside [root]. */
    private fun resolve(path: String): String? {
        val relative = when {
            path == root -> ""
            path.startsWith("$root/") -> path.removePrefix("$root/")
            path.startsWith("/") -> return null
            else -> path
        }
        val parts = ArrayList<String>()
        for (part in relative.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull() ?: return null
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private companion object {
        const val READ_DIRECTORY = "client_filesystem_read_directory_v2"
        const val READ_FILE = "client_filesystem_read_file_v2"
        const val WRITE_FILE = "client_filesystem_write_file_v3"
        val METHODS = setOf(READ_DIRECTORY, READ_FILE, WRITE_FILE)

        fun parent(path: String): String? = if (path.isEmpty()) null else path.substringBeforeLast('/', "")

        fun ancestors(path: String): List<String> {
            val parts = path.split('/')
            return (1 until parts.size).map { parts.take(it).joinToString("/") }
        }

        fun notFound(path: String) = json("ok" to false, "error" to json("code" to "NOT_FOUND", "message" to "No such file or directory: $path"))
    }
}

/** The S19 workspace as recorded: the root listing (`captures/s19-files.jsonl:40`) and `calc.py` (`:52`). */
internal fun s19Workspace(): Workspace {
    val capture = Capture.load("s19-files")
    val contents = mapOf("calc.py" to Base64.getDecoder().decode(capture.line(52).frame!!.obj("params")!!.string("contentBase64")))
    val workspace = Workspace(S19_ROOT)
    for (entry in capture.line(40).frame!!.obj("params")!!.array("entries")!!) {
        val (name, kind, meta) = entry as JsonArray
        val path = (name as JsonPrimitive).content
        if ((kind as JsonPrimitive).content == "directory") workspace.dir(path) else workspace.put(path, contents[path] ?: ByteArray(0), meta as JsonObject)
    }
    return workspace
}
