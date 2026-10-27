package com.agentifiers.amp.vt

import java.io.File

/** A file under the repository root, found by walking up from the test's working directory. */
internal fun repoFile(path: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val candidate = File(dir, path)
        if (candidate.exists()) return candidate
        dir = dir.parentFile
    }
    error("$path not found above ${System.getProperty("user.dir")}")
}

/** `data.ptyOutput` of `s08-terminal.jsonl` line 96: the whole PTY output of the recorded session at 80x24. */
internal fun s08PtyOutput(): ByteArray {
    val line = repoFile("spikes/protocol/captures/s08-terminal.jsonl").readLines()[95]
    return jsonStringValue(line, "ptyOutput").toByteArray(Charsets.UTF_8)
}

/** The JSON string value that follows `"key":` in [json], unescaped. Enough JSON for one capture field. */
internal fun jsonStringValue(json: String, key: String): String {
    val marker = "\"$key\":\""
    var i = json.indexOf(marker).also { require(it >= 0) { "no $key" } } + marker.length
    val out = StringBuilder()
    while (json[i] != '"') {
        val c = json[i++]
        if (c != '\\') {
            out.append(c)
            continue
        }
        when (val e = json[i++]) {
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            'u' -> {
                out.append(json.substring(i, i + 4).toInt(16).toChar())
                i += 4
            }
            else -> out.append(e)
        }
    }
    return out.toString()
}
