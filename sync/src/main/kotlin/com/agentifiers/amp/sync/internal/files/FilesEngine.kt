package com.agentifiers.amp.sync.internal.files

import com.agentifiers.amp.model.Children
import com.agentifiers.amp.model.FileContent
import com.agentifiers.amp.model.FileNode
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Files
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.internal.thread.Call
import com.agentifiers.amp.sync.internal.thread.ThreadEngine
import com.agentifiers.amp.sync.internal.wire.bool
import com.agentifiers.amp.sync.internal.wire.long
import com.agentifiers.amp.sync.internal.wire.obj
import com.agentifiers.amp.sync.internal.wire.string
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * The executor's files over the thread socket (S19): `client_filesystem_read_directory_v2`, `read_file_v2` and
 * `write_file_v3`, each answered by a `*_result` notification with the request's `requestId`.
 */
internal class FilesEngine(private val thread: ThreadEngine, private val scope: CoroutineScope) : Files {
    override val maxDirectWriteBytes: Long = 1_048_576

    private val trees = ConcurrentHashMap<String, Tree>()
    private val reading = Mutex()

    /**
     * Reads on first collection and again whenever collection restarts after [STOP_MS] without collectors, or
     * after an upload into [path]. A re-read keeps the old children on screen until the new listing lands.
     */
    private inner class Tree(path: String) {
        val reloads = MutableStateFlow(0)
        val state: StateFlow<FileNode> = reloads.map { FileNode.Dir(path, list(path)) }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_MS), FileNode.Dir(path, Children.Loading))
    }

    override fun tree(path: String): StateFlow<FileNode> = trees.computeIfAbsent(path) { Tree(it) }.state

    /** Child directories come back [Children.NotLoaded]; the UI expands one by collecting its own [tree]. */
    internal suspend fun list(path: String): Children =
        when (val call = thread.call(READ_DIRECTORY, buildJsonObject { put("path", path) })) {
            is Call.Done -> if (call.body.isOk()) {
                call.body["entries"]?.let { it as? JsonArray }?.let { nodes(path, it) } ?: Children.Failed("The folder listing was unreadable.")
            } else {
                Children.Failed(call.body.errorMessage())
            }
            is Call.Rejected -> Children.Failed(call.error.message)
            Call.NotLive -> Children.Failed(NOT_LIVE)
            Call.Lost -> Children.Failed("The folder listing never arrived.")
        }

    override suspend fun read(path: String): Outcome<FileContent> = reading.withLock {
        when (val call = thread.call(READ_FILE, buildJsonObject { put("path", path) })) {
            is Call.Done -> if (call.body.isOk()) withContext(Dispatchers.Default) { content(path, call.body) } else Outcome.Failed(call.body.serverError())
            is Call.Rejected -> Outcome.Failed(EngineError.Server(call.error.code?.toString() ?: "rpc", call.error.message))
            Call.NotLive -> Outcome.Failed(EngineError.Offline)
            Call.Lost -> Outcome.Unknown
        }
    }

    override suspend fun upload(destDir: String, name: String, bytes: Flow<ByteArray>, size: Long, overwrite: Boolean): Outcome<String> {
        if (size > maxDirectWriteBytes) return Outcome.Refused(TOO_LARGE_TO_WRITE)
        val content = ByteArrayOutputStream(size.coerceAtLeast(0).toInt())
        try {
            bytes.flowOn(Dispatchers.IO).collect { chunk ->
                content.write(chunk)
                if (content.size() > maxDirectWriteBytes) throw TooLarge()
            }
        } catch (_: TooLarge) {
            return Outcome.Refused(TOO_LARGE_TO_WRITE)
        } catch (e: IOException) {
            return Outcome.Refused("Couldn't read the file: ${e.message ?: e.javaClass.simpleName}")
        }
        val path = childPath(destDir, name)
        val encoded = withContext(Dispatchers.Default) { Base64.getEncoder().encodeToString(content.toByteArray()) }
        val params = buildJsonObject {
            put("path", path)
            put("contentBase64", encoded)
            put("overwrite", overwrite)
        }
        return when (val call = thread.call(WRITE_FILE, params)) {
            is Call.Done -> if (call.body.isOk()) {
                invalidate(destDir)
                Outcome.Ok(path)
            } else {
                Outcome.Failed(call.body.serverError())
            }
            is Call.Rejected -> Outcome.Failed(EngineError.Server(call.error.code?.toString() ?: "rpc", call.error.message))
            Call.NotLive -> Outcome.Failed(EngineError.Offline)
            Call.Lost -> {
                invalidate(destDir)
                Outcome.Unknown
            }
        }
    }

    private fun invalidate(path: String) {
        trees[path]?.reloads?.update { it + 1 }
    }

    private fun nodes(parent: String, entries: JsonArray): Children {
        val nodes = entries.mapNotNull { entry ->
            val fields = entry as? JsonArray ?: return@mapNotNull null
            val name = (fields.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val path = childPath(parent, name)
            if ((fields.getOrNull(1) as? JsonPrimitive)?.content == "directory") {
                FileNode.Dir(path, Children.NotLoaded)
            } else {
                FileNode.File(path, (fields.getOrNull(2) as? JsonObject)?.long("mtimeMs")?.let(Instant::fromEpochMilliseconds))
            }
        }
        return Children.Loaded(nodes.sortedWith(DIRS_FIRST).toPersistentList())
    }

    private fun content(path: String, body: JsonObject): Outcome<FileContent> {
        val encoded = body.string("contentBase64") ?: return Outcome.Failed(EngineError.Drift("$READ_FILE result without contentBase64"))
        if (decodedLength(encoded) > MAX_READ_BYTES) return Outcome.Refused("This file is too large to open in the app (over 4 MiB).")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return Outcome.Failed(EngineError.Drift("$READ_FILE result with invalid base64"))
        }
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (extension in IMAGE_EXTENSIONS || isImage(bytes)) return Outcome.Ok(FileContent.Image(path, bytes))
        val text = utf8(bytes) ?: return Outcome.Ok(FileContent.Binary(path, bytes))
        return Outcome.Ok(FileContent.Text(path, text, languageOf(path, extension)))
    }

    private class TooLarge : Exception()

    private companion object {
        const val READ_DIRECTORY = "client_filesystem_read_directory_v2"
        const val READ_FILE = "client_filesystem_read_file_v2"
        const val WRITE_FILE = "client_filesystem_write_file_v3"
        const val STOP_MS = 5_000L
        const val MAX_READ_BYTES = 4L * 1024 * 1024
        const val NOT_LIVE = "Not connected to the thread."
        const val TOO_LARGE_TO_WRITE = "Files over 1 MiB can't be written directly yet."

        val DIRS_FIRST: Comparator<FileNode> = compareBy<FileNode> { it !is FileNode.Dir }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.path.substringAfterLast('/') }
            .thenBy { it.path }

        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        val LANGUAGES = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "gradle" to "groovy", "groovy" to "groovy", "scala" to "scala",
            "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "jsx", "ts" to "typescript",
            "mts" to "typescript", "cts" to "typescript", "tsx" to "tsx", "py" to "python", "rb" to "ruby", "go" to "go",
            "rs" to "rust", "swift" to "swift", "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
            "hpp" to "cpp", "hh" to "cpp", "m" to "objectivec", "cs" to "csharp", "php" to "php", "lua" to "lua", "dart" to "dart",
            "sh" to "bash", "bash" to "bash", "zsh" to "bash", "fish" to "fish", "ps1" to "powershell", "json" to "json",
            "jsonc" to "json", "yaml" to "yaml", "yml" to "yaml", "toml" to "toml", "ini" to "ini", "md" to "markdown",
            "markdown" to "markdown", "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss", "less" to "less",
            "svelte" to "svelte", "vue" to "vue", "sql" to "sql", "xml" to "xml", "svg" to "xml", "proto" to "protobuf",
            "graphql" to "graphql", "gql" to "graphql", "tf" to "hcl", "hcl" to "hcl", "nix" to "nix", "zig" to "zig",
            "ex" to "elixir", "exs" to "elixir", "erl" to "erlang", "hs" to "haskell", "ml" to "ocaml", "clj" to "clojure",
            "r" to "r", "pl" to "perl", "diff" to "diff", "patch" to "diff",
        )

        val FILENAMES = mapOf("Dockerfile" to "dockerfile", "Makefile" to "makefile", "GNUmakefile" to "makefile", "CMakeLists.txt" to "cmake")

        fun languageOf(path: String, extension: String): String =
            FILENAMES[path.substringAfterLast('/')] ?: LANGUAGES[extension] ?: "text"

        fun childPath(parent: String, name: String): String =
            if (parent == "." || parent.isEmpty()) name else "${parent.trimEnd('/')}/$name"

        fun decodedLength(encoded: String): Long {
            val padding = encoded.takeLast(2).count { it == '=' }
            return encoded.length / 4L * 3 - padding
        }

        fun isImage(bytes: ByteArray): Boolean {
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
            return starts(0x89, 0x50, 0x4E, 0x47) || starts(0xFF, 0xD8, 0xFF) || starts(0x47, 0x49, 0x46, 0x38) || starts(0x42, 0x4D) ||
                (starts(0x52, 0x49, 0x46, 0x46) && bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP")
        }

        /** Strict UTF-8 with no NUL bytes, so binaries that happen to decode stay binary. */
        fun utf8(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        fun JsonObject.isOk(): Boolean = bool("ok") == true

        fun JsonObject.serverError(): EngineError {
            val error = obj("error")
            return EngineError.Server(error?.string("code") ?: "error", error?.string("message") ?: "The executor refused the request.")
        }

        fun JsonObject.errorMessage(): String = obj("error")?.string("message") ?: "The executor refused the request."
    }
}
