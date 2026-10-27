package com.agentifiers.amp.files

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.agentifiers.amp.design.theme.SyntaxTokens

/** How one language family marks comments, strings and keywords. Strings never span lines; block comments do. */
internal class Grammar(
    val lineComments: List<String>,
    val blockComment: Pair<String, String>?,
    val quotes: String,
    val keywords: Set<String>,
    val caseInsensitive: Boolean = false,
)

private class TokenStyles(syntax: SyntaxTokens) {
    val comment = SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic)
    val string = SpanStyle(color = syntax.string)
    val number = SpanStyle(color = syntax.number)
    val keyword = SpanStyle(color = syntax.keyword)
}

private val cLikeKeywords = setOf(
    "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "data", "default",
    "defer", "delete", "do", "else", "enum", "export", "extends", "false", "final", "finally", "for", "fun", "func",
    "function", "go", "guard", "if", "impl", "implements", "import", "in", "interface", "internal", "is", "let",
    "match", "mod", "mut", "namespace", "new", "nil", "null", "object", "open", "override", "package", "private",
    "protected", "pub", "public", "return", "sealed", "self", "static", "struct", "super", "suspend", "switch",
    "this", "throw", "throws", "trait", "true", "try", "type", "typeof", "undefined", "use", "val", "var", "void",
    "when", "where", "while", "yield",
)

private val hashKeywords = setOf(
    "and", "as", "assert", "async", "await", "begin", "break", "case", "class", "def", "del", "do", "done", "elif",
    "else", "elsif", "end", "esac", "except", "export", "False", "fi", "finally", "for", "from", "function", "global",
    "if", "import", "in", "is", "lambda", "local", "module", "next", "nil", "None", "not", "or", "pass", "raise",
    "rescue", "return", "self", "then", "True", "true", "false", "unless", "until", "while", "with", "yield",
)

private val sqlKeywords = setOf(
    "all", "alter", "and", "as", "asc", "begin", "between", "by", "case", "commit", "create", "default", "delete",
    "desc", "distinct", "drop", "else", "end", "exists", "foreign", "from", "group", "having", "in", "index",
    "inner", "insert", "into", "is", "join", "key", "left", "like", "limit", "not", "null", "offset", "on", "or",
    "order", "outer", "primary", "references", "returning", "right", "rollback", "select", "set", "table", "then",
    "union", "update", "values", "when", "where", "with",
)

private val luaKeywords = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "if", "in", "local", "nil", "not",
    "or", "repeat", "return", "then", "true", "until", "while",
)

private val cLike = Grammar(listOf("//"), "/*" to "*/", "\"'`", cLikeKeywords)
private val rust = Grammar(listOf("//"), "/*" to "*/", "\"", cLikeKeywords)
private val hash = Grammar(listOf("#"), null, "\"'", hashKeywords)
private val sql = Grammar(listOf("--"), "/*" to "*/", "'\"", sqlKeywords, caseInsensitive = true)
private val lua = Grammar(listOf("--"), null, "\"'", luaKeywords)
private val markup = Grammar(emptyList(), "<!--" to "-->", "\"", emptySet())
private val css = Grammar(emptyList(), "/*" to "*/", "\"'", emptySet())
private val json = Grammar(listOf("//"), "/*" to "*/", "\"", setOf("true", "false", "null"))
private val haskell = Grammar(
    listOf("--"), "{-" to "-}", "\"",
    setOf("case", "class", "data", "deriving", "do", "else", "if", "import", "in", "instance", "let", "module", "newtype", "of", "then", "type", "where"),
)
private val erlang = Grammar(listOf("%"), null, "\"", setOf("after", "begin", "case", "catch", "end", "fun", "if", "of", "receive", "try", "when"))
private val ocaml = Grammar(
    emptyList(), "(*" to "*)", "\"",
    setOf("else", "end", "fun", "function", "if", "in", "let", "match", "module", "of", "open", "rec", "sig", "struct", "then", "type", "with"),
)
private val clojure = Grammar(listOf(";"), null, "\"", setOf("cond", "def", "defn", "defmacro", "do", "fn", "if", "let", "loop", "ns", "recur", "when"))

/** Language names and file extensions (or whole file names) to their family. */
private val grammars: Map<String, Grammar> = buildMap {
    fun family(grammar: Grammar, vararg keys: String) = keys.forEach { put(it, grammar) }
    family(
        cLike, "kotlin", "kt", "kts", "java", "javascript", "js", "mjs", "cjs", "jsx", "javascriptreact", "typescript",
        "ts", "mts", "cts", "tsx", "typescriptreact", "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "hh", "csharp", "cs",
        "go", "golang", "swift", "scala", "sc", "dart", "groovy", "gradle", "php", "objective-c", "objectivec", "objc", "m",
        "mm", "protobuf", "proto", "zig",
    )
    family(rust, "rust", "rs")
    family(
        hash, "python", "py", "pyi", "ruby", "rb", "shell", "shellscript", "sh", "bash", "zsh", "fish", "yaml", "yml",
        "toml", "perl", "pl", "r", "makefile", "mk", "dockerfile", "ini", "conf", "cfg", "properties", "gitignore",
        "elixir", "ex", "exs", "nix", "powershell", "ps1", "graphql", "gql", "hcl", "tf", "cmake",
    )
    family(sql, "sql")
    family(lua, "lua")
    family(markup, "html", "htm", "xhtml", "xml", "svg", "vue", "svelte", "plist")
    family(css, "css", "scss", "less")
    family(json, "json", "jsonc", "json5")
    family(haskell, "haskell", "hs")
    family(erlang, "erlang", "erl")
    family(ocaml, "ocaml", "ml", "mli")
    family(clojure, "clojure", "clj", "cljs", "edn")
}

internal fun grammarFor(language: String, path: String): Grammar? {
    val name = path.substringAfterLast('/').lowercase()
    return grammars[language.lowercase()] ?: grammars[name.substringAfterLast('.', "")] ?: grammars[name]
}

/** Splits a file into display lines; a trailing newline does not count as an extra line. */
internal fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.lines()
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

internal fun highlight(lines: List<String>, grammar: Grammar?, syntax: SyntaxTokens): List<AnnotatedString> {
    if (grammar == null) return lines.map(::AnnotatedString)
    val styles = TokenStyles(syntax)
    var inBlock = false
    return lines.map { line ->
        buildAnnotatedString { inBlock = appendTokens(line, grammar, styles, inBlock) }
    }
}

/** Appends [line] with token styles and returns whether a block comment is still open at its end. */
private fun AnnotatedString.Builder.appendTokens(line: String, grammar: Grammar, styles: TokenStyles, startsInBlock: Boolean): Boolean {
    fun styled(style: SpanStyle, from: Int, to: Int) {
        val start = length
        append(line, from, to)
        addStyle(style, start, length)
    }

    var inBlock = startsInBlock
    var i = 0
    while (i < line.length) {
        val block = grammar.blockComment
        if (inBlock && block != null) {
            val close = line.indexOf(block.second, i)
            val stop = if (close < 0) line.length else close + block.second.length
            styled(styles.comment, i, stop)
            inBlock = close < 0
            i = stop
            continue
        }
        val c = line[i]
        when {
            grammar.lineComments.any { line.startsWith(it, i) } -> {
                styled(styles.comment, i, line.length)
                i = line.length
            }
            block != null && line.startsWith(block.first, i) -> {
                styled(styles.comment, i, i + block.first.length)
                i += block.first.length
                inBlock = true
            }
            c in grammar.quotes -> {
                val end = stringEnd(line, i)
                styled(styles.string, i, end)
                i = end
            }
            c.isDigit() -> {
                val end = wordEnd(line, i) { it.isLetterOrDigit() || it == '.' || it == '_' }
                styled(styles.number, i, end)
                i = end
            }
            c.isLetter() || c == '_' || c == '$' -> {
                val end = wordEnd(line, i) { it.isLetterOrDigit() || it == '_' || it == '$' }
                val word = line.substring(i, end)
                if ((if (grammar.caseInsensitive) word.lowercase() else word) in grammar.keywords) {
                    styled(styles.keyword, i, end)
                } else {
                    append(word)
                }
                i = end
            }
            else -> {
                append(c)
                i++
            }
        }
    }
    return inBlock
}

private inline fun wordEnd(line: String, from: Int, part: (Char) -> Boolean): Int {
    var j = from + 1
    while (j < line.length && part(line[j])) j++
    return j
}

private fun stringEnd(line: String, open: Int): Int {
    val quote = line[open]
    var j = open + 1
    while (j < line.length) {
        when (line[j]) {
            '\\' -> j += 2
            quote -> return j + 1
            else -> j++
        }
    }
    return line.length
}
