package com.agentifiers.amp.model.diff

import com.agentifiers.amp.model.Language
import com.agentifiers.amp.model.SyntaxKind
import com.agentifiers.amp.model.SyntaxSpan
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** Lines longer than this get no syntax tokens and no word-level highlights, as on the web. */
internal const val HIGHLIGHT_LINE_LIMIT = 1_000

private val FILE_NAMES = mapOf(
    "Dockerfile" to Language.Dockerfile,
    "Containerfile" to Language.Dockerfile,
    "Makefile" to Language.Makefile,
    "makefile" to Language.Makefile,
    "GNUmakefile" to Language.Makefile,
    "Gemfile" to Language.Ruby,
    "Rakefile" to Language.Ruby,
    "Podfile" to Language.Ruby,
    "Fastfile" to Language.Ruby,
    "Appfile" to Language.Ruby,
    "Brewfile" to Language.Ruby,
    "Guardfile" to Language.Ruby,
    "Vagrantfile" to Language.Ruby,
    ".bashrc" to Language.Shell,
    ".bash_profile" to Language.Shell,
    ".zshrc" to Language.Shell,
    ".zprofile" to Language.Shell,
    ".profile" to Language.Shell,
    ".envrc" to Language.Shell,
)

private val EXTENSIONS = mapOf(
    "kt" to Language.Kotlin, "kts" to Language.Kotlin,
    "java" to Language.Java, "gradle" to Language.Java,
    "py" to Language.Python, "pyi" to Language.Python,
    "js" to Language.JavaScript, "mjs" to Language.JavaScript, "cjs" to Language.JavaScript, "jsx" to Language.JavaScript,
    "ts" to Language.TypeScript, "tsx" to Language.TypeScript, "mts" to Language.TypeScript, "cts" to Language.TypeScript,
    "rs" to Language.Rust,
    "go" to Language.Go,
    "swift" to Language.Swift,
    "c" to Language.C, "h" to Language.C,
    "cc" to Language.Cpp, "cpp" to Language.Cpp, "cxx" to Language.Cpp, "hh" to Language.Cpp, "hpp" to Language.Cpp, "hxx" to Language.Cpp,
    "sh" to Language.Shell, "bash" to Language.Shell, "zsh" to Language.Shell,
    "rb" to Language.Ruby, "rake" to Language.Ruby, "gemspec" to Language.Ruby,
    "json" to Language.Json,
    "yml" to Language.Yaml, "yaml" to Language.Yaml,
    "toml" to Language.Toml,
    "md" to Language.Markdown, "markdown" to Language.Markdown,
    "html" to Language.Html, "htm" to Language.Html, "xhtml" to Language.Html, "xml" to Language.Html, "svg" to Language.Html,
    "css" to Language.Css, "scss" to Language.Css, "less" to Language.Css,
    "sql" to Language.Sql,
    "dockerfile" to Language.Dockerfile,
    "mk" to Language.Makefile,
)

fun languageOf(path: String): Language {
    val name = path.substringAfterLast('/')
    FILE_NAMES[name]?.let { return it }
    if (name.startsWith("Dockerfile.")) return Language.Dockerfile
    return EXTENSIONS[name.substringAfterLast('.', "").lowercase()] ?: Language.Plain
}

/**
 * A hand-written lexer per language family. Each side of a diff is tokenized as a whole so block comments and
 * multi-line strings carry their state into the following lines.
 */
object Syntax {
    fun tokenize(language: Language, lines: List<String>): List<PersistentList<SyntaxSpan>> {
        if (language == Language.Markdown) return markdown(lines)
        val grammar = GRAMMARS[language] ?: return List(lines.size) { persistentListOf() }
        var pending: Pending? = null
        return lines.map { line ->
            if (line.length > HIGHLIGHT_LINE_LIMIT) return@map persistentListOf()
            val lexer = LineLexer(grammar, line)
            pending = lexer.run(pending)
            lexer.spans.toPersistentList()
        }
    }

    private fun markdown(lines: List<String>): List<PersistentList<SyntaxSpan>> {
        var inFence = false
        return lines.map { line ->
            if (line.length > HIGHLIGHT_LINE_LIMIT) return@map persistentListOf()
            val trimmed = line.trimStart()
            val fence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            when {
                fence || inFence -> {
                    if (fence) inFence = !inFence
                    if (line.isEmpty()) persistentListOf() else persistentListOf(SyntaxSpan(0, line.length, SyntaxKind.Literal))
                }
                isHeading(line) -> persistentListOf(SyntaxSpan(0, line.length, SyntaxKind.Keyword))
                else -> inlineCode(line)
            }
        }
    }

    private fun isHeading(line: String): Boolean {
        val start = line.indexOfFirst { it != ' ' }
        if (start !in 0..3 || line[start] != '#') return false
        var end = start
        while (end < line.length && line[end] == '#') end++
        return end - start <= 6 && (end == line.length || line[end] == ' ')
    }

    private fun inlineCode(line: String): PersistentList<SyntaxSpan> {
        val spans = ArrayList<SyntaxSpan>()
        var i = line.indexOf('`')
        while (i >= 0) {
            val close = line.indexOf('`', i + 1)
            if (close < 0) break
            spans += SyntaxSpan(i, close + 1, SyntaxKind.Literal)
            i = line.indexOf('`', close + 1)
        }
        return spans.toPersistentList()
    }
}

/** A comment or string that is still open at the end of a line. */
private class Pending(val kind: SyntaxKind, val close: String, val escapes: Boolean)

private class LineComment(val prefix: String, val needsBoundary: Boolean)

private class Quote(
    val open: String,
    val close: String = open,
    val multiline: Boolean = false,
    val escapes: Boolean = true,
    /** A one-character literal such as `'a'`; anything else starting with the quote is punctuation, e.g. a Rust lifetime. */
    val charLiteral: Boolean = false,
    /** Only an attribute value (`=` before it) is a string, so prose apostrophes in markup stay plain. */
    val afterEquals: Boolean = false,
)

private class Grammar(
    keywords: String,
    val lineComments: List<LineComment> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val quotes: List<Quote> = emptyList(),
    val capitalizedTypes: Boolean = false,
    val ignoreKeywordCase: Boolean = false,
    val identifierExtras: String = "",
    val markupTags: Boolean = false,
) {
    val keywords: Set<String> = keywords.split(' ').filter { it.isNotEmpty() }.toSet()
}

private class LineLexer(private val grammar: Grammar, private val line: String) {
    val spans = ArrayList<SyntaxSpan>()
    private var i = 0

    fun run(carried: Pending?): Pending? {
        if (carried != null) {
            val end = closeOf(0, carried)
            if (end < 0) {
                add(0, line.length, carried.kind)
                return carried
            }
            add(0, end, carried.kind)
            i = end
        }
        while (i < line.length) {
            val open = step()
            if (open != null) return open
        }
        return null
    }

    private fun step(): Pending? {
        val c = line[i]
        if (c.isWhitespace()) {
            i++
            return null
        }
        if (grammar.lineComments.any { it.matchesAt() }) {
            add(i, line.length, SyntaxKind.Comment)
            i = line.length
            return null
        }
        grammar.blockComment?.let { (open, close) ->
            if (line.startsWith(open, i)) return region(i, i + open.length, Pending(SyntaxKind.Comment, close, escapes = false), multiline = true)
        }
        grammar.quotes.firstOrNull { line.startsWith(it.open, i) }?.let { quote -> return string(quote) }
        when {
            c.isDigit() -> number()
            c.isLetter() || c == '_' || c in grammar.identifierExtras -> word()
            else -> i++
        }
        return null
    }

    private fun LineComment.matchesAt(): Boolean =
        line.startsWith(prefix, i) && (!needsBoundary || i == 0 || line[i - 1].isWhitespace())

    private fun string(quote: Quote): Pending? {
        if (quote.afterEquals && previousNonSpace() != '=') {
            i++
            return null
        }
        if (quote.charLiteral) {
            val end = charLiteralEnd()
            if (end < 0) i++ else add(i, end, SyntaxKind.Literal).also { i = end }
            return null
        }
        return region(i, i + quote.open.length, Pending(SyntaxKind.Literal, quote.close, quote.escapes), quote.multiline)
    }

    private fun region(start: Int, bodyStart: Int, pending: Pending, multiline: Boolean): Pending? {
        val end = closeOf(bodyStart, pending)
        if (end < 0) {
            add(start, line.length, pending.kind)
            i = line.length
            return if (multiline) pending else null
        }
        add(start, end, pending.kind)
        i = end
        return null
    }

    /** Index just past the close delimiter, or -1 when the line ends first. */
    private fun closeOf(from: Int, pending: Pending): Int {
        var j = from
        while (j <= line.length - pending.close.length) {
            if (pending.escapes && line[j] == '\\') {
                j += 2
                continue
            }
            if (line.startsWith(pending.close, j)) return j + pending.close.length
            j++
        }
        return -1
    }

    private fun charLiteralEnd(): Int {
        val quote = line[i]
        if (i + 1 >= line.length) return -1
        if (line[i + 1] == '\\') {
            val close = line.indexOf(quote, i + 3)
            return if (close in i + 3..i + 10) close + 1 else -1
        }
        val width = if (line[i + 1].isHighSurrogate()) 2 else 1
        val close = i + 1 + width
        return if (line[i + 1] != quote && close < line.length && line[close] == quote) close + 1 else -1
    }

    private fun number() {
        val start = i
        while (i < line.length) {
            val ch = line[i]
            val decimalPoint = ch == '.' && i + 1 < line.length && line[i + 1].isDigit()
            if (!(ch.isLetterOrDigit() || ch == '_' || decimalPoint)) break
            i++
        }
        add(start, i, SyntaxKind.Number)
    }

    private fun word() {
        val start = i
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] in grammar.identifierExtras)) i++
        val text = line.substring(start, i)
        val kind = when {
            (if (grammar.ignoreKeywordCase) text.lowercase() else text) in grammar.keywords -> SyntaxKind.Keyword
            grammar.markupTags && isTagName(start) -> SyntaxKind.Keyword
            grammar.capitalizedTypes && text[0].isUpperCase() -> SyntaxKind.Type
            else -> return
        }
        add(start, i, kind)
    }

    private fun isTagName(start: Int): Boolean =
        (start >= 1 && line[start - 1] == '<') || (start >= 2 && line[start - 1] == '/' && line[start - 2] == '<')

    private fun previousNonSpace(): Char? {
        var j = i - 1
        while (j >= 0 && line[j] == ' ') j--
        return if (j >= 0) line[j] else null
    }

    private fun add(start: Int, end: Int, kind: SyntaxKind) {
        if (end > start) spans += SyntaxSpan(start, end, kind)
    }
}

private val SLASH_COMMENTS = listOf(LineComment("//", needsBoundary = false))
private val HASH_COMMENT = listOf(LineComment("#", needsBoundary = false))
private val HASH_COMMENT_AT_BOUNDARY = listOf(LineComment("#", needsBoundary = true))
private val C_BLOCK = "/*" to "*/"
private val DOUBLE = Quote("\"")
private val SINGLE = Quote("'")
private val CHAR = Quote("'", charLiteral = true)

private val C_FAMILY_KEYWORDS = "auto break case char const continue default do double else enum extern float for goto if " +
    "inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile " +
    "while true false NULL bool"

private val GRAMMARS: Map<Language, Grammar> = mapOf(
    Language.Kotlin to Grammar(
        "val var fun class object interface if else when for while do return break continue in is as null true false this " +
            "super package import private public internal protected override open abstract sealed data enum companion init " +
            "constructor try catch finally throw typealias suspend inline reified lateinit const vararg out by get set where " +
            "operator infix tailrec external annotation inner value crossinline noinline actual expect",
        SLASH_COMMENTS, C_BLOCK,
        listOf(Quote("\"\"\"", multiline = true, escapes = false), DOUBLE, CHAR),
        capitalizedTypes = true,
    ),
    Language.Java to Grammar(
        "abstract assert boolean break byte case catch char class const continue default do double else enum extends final " +
            "finally float for goto if implements import instanceof int interface long native new package private protected " +
            "public return short static strictfp super switch synchronized this throw throws transient try void volatile while " +
            "var record sealed permits yield true false null",
        SLASH_COMMENTS, C_BLOCK,
        listOf(Quote("\"\"\"", multiline = true), DOUBLE, CHAR),
        capitalizedTypes = true,
    ),
    Language.JavaScript to jsGrammar(""),
    Language.TypeScript to jsGrammar(
        "type interface enum implements namespace declare abstract private public protected readonly keyof infer is as " +
            "satisfies any unknown never string number boolean",
    ),
    Language.Rust to Grammar(
        "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut " +
            "pub ref return self Self static struct super trait true type unsafe use where while",
        SLASH_COMMENTS, C_BLOCK,
        listOf(Quote("\"", multiline = true), CHAR),
        capitalizedTypes = true,
    ),
    Language.Go to Grammar(
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package " +
            "range return select struct switch type var true false nil iota",
        SLASH_COMMENTS, C_BLOCK,
        listOf(Quote("`", multiline = true, escapes = false), DOUBLE, CHAR),
        capitalizedTypes = true,
    ),
    Language.Swift to Grammar(
        "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private " +
            "protocol public rethrows static struct subscript typealias var break case continue default defer do else " +
            "fallthrough for guard if in repeat return switch where while as catch false is nil self Self super throw throws " +
            "true try async await some any",
        SLASH_COMMENTS, C_BLOCK,
        listOf(Quote("\"\"\"", multiline = true), DOUBLE),
        capitalizedTypes = true,
    ),
    Language.C to Grammar(C_FAMILY_KEYWORDS, SLASH_COMMENTS, C_BLOCK, listOf(DOUBLE, CHAR), capitalizedTypes = true),
    Language.Cpp to Grammar(
        "$C_FAMILY_KEYWORDS class namespace template typename public private protected virtual override final new delete " +
            "this using operator friend explicit constexpr noexcept nullptr try catch throw mutable decltype",
        SLASH_COMMENTS, C_BLOCK, listOf(DOUBLE, CHAR),
        capitalizedTypes = true,
    ),
    Language.Python to Grammar(
        "False None True and as assert async await break class continue def del elif else except finally for from global if " +
            "import in is lambda nonlocal not or pass raise return try while with yield match case self",
        HASH_COMMENT, null,
        listOf(Quote("\"\"\"", multiline = true), Quote("'''", multiline = true), DOUBLE, SINGLE),
    ),
    Language.Shell to Grammar(
        "if then else elif fi case esac for select while until do done in function time return exit export local readonly " +
            "declare unset shift source true false",
        HASH_COMMENT_AT_BOUNDARY, null,
        listOf(Quote("\"", multiline = true), Quote("'", multiline = true, escapes = false), Quote("`", multiline = true)),
    ),
    Language.Ruby to Grammar(
        "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo " +
            "rescue retry return self super then true undef unless until when while yield require attr_reader attr_accessor",
        HASH_COMMENT, null,
        listOf(Quote("\"", multiline = true), Quote("'", multiline = true), Quote("`", multiline = true)),
    ),
    Language.Json to Grammar("true false null", quotes = listOf(DOUBLE)),
    Language.Yaml to Grammar(
        "true false null yes no on off True False Null",
        HASH_COMMENT_AT_BOUNDARY, null,
        listOf(DOUBLE, Quote("'", escapes = false)),
    ),
    Language.Toml to Grammar(
        "true false",
        HASH_COMMENT, null,
        listOf(Quote("\"\"\"", multiline = true), Quote("'''", multiline = true, escapes = false), DOUBLE, Quote("'", escapes = false)),
    ),
    Language.Sql to Grammar(
        "select from where insert into values update set delete create table drop alter add column index view primary key " +
            "foreign references not null default unique and or in is like between join inner left right outer full on as " +
            "group by order having limit offset union all distinct case when then else end exists begin commit rollback " +
            "transaction with returning integer int text varchar boolean true false",
        listOf(LineComment("--", needsBoundary = false)), C_BLOCK,
        listOf(Quote("'", multiline = true, escapes = false), DOUBLE),
        ignoreKeywordCase = true,
    ),
    Language.Html to Grammar(
        "",
        blockComment = "<!--" to "-->",
        quotes = listOf(Quote("\"", escapes = false, afterEquals = true), Quote("'", escapes = false, afterEquals = true)),
        identifierExtras = "-:",
        markupTags = true,
    ),
    Language.Css to Grammar(
        "important inherit initial unset none auto",
        blockComment = C_BLOCK,
        quotes = listOf(DOUBLE, SINGLE),
        identifierExtras = "-",
    ),
    Language.Dockerfile to Grammar(
        "from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal " +
            "healthcheck shell as",
        HASH_COMMENT_AT_BOUNDARY, null,
        listOf(DOUBLE, SINGLE),
        ignoreKeywordCase = true,
    ),
    Language.Makefile to Grammar(
        "ifeq ifneq ifdef ifndef else endif include define endef export unexport override vpath",
        HASH_COMMENT_AT_BOUNDARY,
    ),
)

private fun jsGrammar(extraKeywords: String) = Grammar(
    "break case catch class const continue debugger default delete do else export extends finally for function if import " +
        "in instanceof let new return super switch this throw try typeof var void while with yield async await of static " +
        "get set true false null undefined $extraKeywords",
    SLASH_COMMENTS, C_BLOCK,
    listOf(Quote("`", multiline = true), DOUBLE, SINGLE),
    capitalizedTypes = true,
    identifierExtras = "$",
)
