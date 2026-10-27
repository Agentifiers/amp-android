package com.agentifiers.amp.design.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import com.agentifiers.amp.model.GalleryLayout
import com.agentifiers.amp.model.MarkdownPart

/** The styles the parser bakes into inline spans: code spans take [code], links take [link]. */
internal data class MdStyles(val code: SpanStyle, val link: Color)

/** Marks each code span, so the renderer can draw its rounded fill behind exactly that range. */
internal const val CODE_SPAN_TAG = "code"

/** Plain text of the markdown as a reader sees it: no markup, one line per block, table cells split by tabs. */
fun markdownPlainText(markdown: String): String = plainText(parseMarkdown(markdown, MdStyles(SpanStyle(), Color.Unspecified)))

/** Marks the end of text still streaming. */
internal const val STREAMING_CURSOR = " ▍"

/**
 * Amp's `:::` directives first ([MarkdownPart]), then each run of prose between them as GFM. A link definition in one
 * run serves every run, as it would in one document. While [streaming], the last block ends with the cursor.
 */
internal fun parseMarkdown(md: String, styles: MdStyles, streaming: Boolean = false): List<MdBlock> {
    val parts = MarkdownPart.parse(md, streaming)
    val documents = HashMap<String, Pair<String, ASTNode>>()
    fun collect(parts: List<MarkdownPart>) {
        for (part in parts) when (part) {
            is MarkdownPart.Prose -> documents.getOrPut(part.markdown) {
                separateTables(part.markdown).let { it to MarkdownParser(flavour).buildMarkdownTreeFromString(it) }
            }
            is MarkdownPart.Directive -> collect(part.children)
        }
    }
    collect(parts)
    val links = HashMap<String, String>()
    for ((source, root) in documents.values) linkDefinitions(source, root).forEach(links::putIfAbsent)
    fun blocks(parts: List<MarkdownPart>): List<MdBlock> = parts.flatMap { part ->
        when (part) {
            is MarkdownPart.Prose -> documents.getValue(part.markdown).let { (source, root) -> MdReader(source, links, styles).blocks(root.children) }
            is MarkdownPart.Directive -> directive(part, blocks(part.children))
        }
    }
    return blocks(parts).let { if (streaming) withCursor(it) else it }
}

/**
 * A closed gallery of only images is a [MdBlock.Gallery]. Any other directive, and a gallery still open or holding
 * anything else, shows its children without the fences, as the web shows an open or invalid gallery.
 */
private fun directive(directive: MarkdownPart.Directive, children: List<MdBlock>): List<MdBlock> {
    val layout = GalleryLayout.of(directive)
    val images = children.filterIsInstance<MdBlock.Image>()
    return if (layout != null && directive.closed && images.isNotEmpty() && images.size == children.size) listOf(MdBlock.Gallery(layout, images)) else children
}

private fun withCursor(blocks: List<MdBlock>): List<MdBlock> {
    val rest = blocks.dropLast(1)
    return when (val last = blocks.lastOrNull()) {
        is MdBlock.Paragraph -> rest + MdBlock.Paragraph(last.inline + AnnotatedString(STREAMING_CURSOR))
        is MdBlock.Heading -> rest + last.copy(inline = last.inline + AnnotatedString(STREAMING_CURSOR))
        is MdBlock.Code -> rest + last.copy(text = last.text + STREAMING_CURSOR)
        is MdBlock.ListBlock -> rest + last.copy(items = last.items.dropLast(1) + listOf(withCursor(last.items.last())))
        is MdBlock.Quote -> rest + MdBlock.Quote(withCursor(last.blocks))
        else -> blocks + MdBlock.Paragraph(AnnotatedString(STREAMING_CURSOR.trimStart()))
    }
}

private val flavour = GFMFlavourDescriptor()

private val strongStyle = SpanStyle(fontWeight = FontWeight.Bold)
private val emphasisStyle = SpanStyle(fontStyle = FontStyle.Italic)
private val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)

private val atxLevels: Map<IElementType, Int> = listOf(
    MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
).withIndex().associate { (index, type) -> type to index + 1 }

private val linkTypes = setOf(
    MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK,
)

private fun plainText(blocks: List<MdBlock>): String =
    blocks.map(::plainText).filter { it.isNotEmpty() }.joinToString("\n")

private fun plainText(block: MdBlock): String = when (block) {
    is MdBlock.Heading -> block.inline.text
    is MdBlock.Paragraph -> block.inline.text
    is MdBlock.Code -> block.text
    is MdBlock.ListBlock -> plainText(block.items.flatten())
    is MdBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString("\t") { it.text } }
    is MdBlock.Quote -> plainText(block.blocks)
    MdBlock.Rule, is MdBlock.Image -> ""
    is MdBlock.Gallery -> block.images.map { it.alt }.filter { it.isNotEmpty() }.joinToString("\n")
}

private val delimiterRow = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
private val fenceLine = Regex("""^\s*(```|~~~)""")

/**
 * GFM lets a table start right under a paragraph line ("Results:\n| a | b |\n|---|---|"), which agents write
 * often; the intellij parser only sees a table after a blank line, so one is inserted outside code fences.
 */
private fun separateTables(md: String): String {
    if ('|' !in md) return md
    val lines = md.split('\n')
    var inFence = false
    var changed = false
    val out = ArrayList<String>(lines.size + 4)
    lines.forEachIndexed { i, line ->
        if (fenceLine.containsMatchIn(line)) inFence = !inFence
        val previous = lines.getOrNull(i - 1)
        val next = lines.getOrNull(i + 1)
        if (!inFence && '|' in line && !line.trimStart().startsWith('>') && next != null && delimiterRow.matches(next) &&
            '|' in next && previous != null && previous.isNotBlank() && '|' !in previous
        ) {
            out += ""
            changed = true
        }
        out += line
    }
    return if (changed) out.joinToString("\n") else md
}

private fun linkDefinitions(src: String, root: ASTNode): Map<String, String> {
    val definitions = HashMap<String, String>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            val label = node.child(MarkdownElementTypes.LINK_LABEL) ?: return
            val destination = node.child(MarkdownElementTypes.LINK_DESTINATION) ?: return
            definitions.putIfAbsent(labelKey(label.text(src)), destination(destination.text(src)))
        } else {
            node.children.forEach(::visit)
        }
    }
    visit(root)
    return definitions
}

private val whitespaceRun = Regex("""\s+""")

private fun labelKey(label: String): String =
    label.trim().removePrefix("[").removeSuffix("]").trim().lowercase().replace(whitespaceRun, " ")

private fun destination(raw: String): String = unescape(raw.trim().removePrefix("<").removeSuffix(">"))

private const val escapable = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
private val entity = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);""")
private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0")

private fun unescape(text: String): String {
    if ('\\' !in text && '&' !in text) return text
    val unescaped = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] in escapable) {
                append(text[i + 1])
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
    return entity.replace(unescaped) { match ->
        val name = match.groupValues[1]
        namedEntities[name] ?: run {
            val codePoint = if (name[1] == 'x' || name[1] == 'X') name.substring(2).toInt(16) else name.substring(1).toInt()
            if (codePoint == 0 || !Character.isValidCodePoint(codePoint)) "\uFFFD" else String(Character.toChars(codePoint))
        }
    }
}

private fun ASTNode.text(src: String): String = src.substring(startOffset, endOffset)
private fun ASTNode.child(type: IElementType): ASTNode? = children.firstOrNull { it.type == type }

private fun List<ASTNode>.trimmed(): List<ASTNode> {
    fun blank(node: ASTNode) = node.type == MarkdownTokenTypes.WHITE_SPACE || node.type == MarkdownTokenTypes.EOL
    return dropWhile(::blank).dropLastWhile(::blank)
}

private fun List<ASTNode>.insideBrackets(): List<ASTNode> {
    val start = if (firstOrNull()?.type == MarkdownTokenTypes.LBRACKET) 1 else 0
    val end = if (size > start && last().type == MarkdownTokenTypes.RBRACKET) size - 1 else size
    return subList(start, end)
}

private class LinkTarget(val text: List<ASTNode>, val url: String?)

private class MdReader(private val src: String, private val links: Map<String, String>, styles: MdStyles) {
    private val codeStyle = styles.code
    private val linkStyles = TextLinkStyles(SpanStyle(color = styles.link))

    fun blocks(nodes: List<ASTNode>): List<MdBlock> = nodes.flatMap(::block)

    private fun block(node: ASTNode): List<MdBlock> = when (val type = node.type) {
        in atxLevels -> heading(atxLevels.getValue(type), node.child(MarkdownTokenTypes.ATX_CONTENT))
        MarkdownElementTypes.SETEXT_1 -> heading(1, node.child(MarkdownTokenTypes.SETEXT_CONTENT))
        MarkdownElementTypes.SETEXT_2 -> heading(2, node.child(MarkdownTokenTypes.SETEXT_CONTENT))
        MarkdownElementTypes.PARAGRAPH -> paragraph(node.children)
        MarkdownElementTypes.CODE_FENCE -> listOf(fence(node))
        MarkdownElementTypes.CODE_BLOCK -> listOf(MdBlock.Code(null, codeLines(node.children, MarkdownTokenTypes.CODE_LINE, ::stripIndent)))
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> listOf(list(node))
        MarkdownElementTypes.BLOCK_QUOTE -> listOf(MdBlock.Quote(blocks(node.children)))
        MarkdownTokenTypes.HORIZONTAL_RULE -> listOf(MdBlock.Rule)
        GFMElementTypes.TABLE -> listOf(table(node))
        MarkdownElementTypes.HTML_BLOCK, GFMElementTypes.BLOCK_MATH -> listOf(MdBlock.Paragraph(AnnotatedString(node.text(src).trim())))
        else -> emptyList()
    }

    private fun heading(level: Int, content: ASTNode?): List<MdBlock> {
        val text = inline(content?.children.orEmpty())
        return if (text.isEmpty()) emptyList() else listOf(MdBlock.Heading(level, text))
    }

    /** Images become blocks of their own, splitting the paragraph around them. */
    private fun paragraph(nodes: List<ASTNode>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var run = mutableListOf<ASTNode>()
        fun flush() {
            val text = inline(run)
            if (text.isNotEmpty()) out += MdBlock.Paragraph(text)
            run = mutableListOf()
        }
        for (node in nodes) {
            if (node.type == MarkdownElementTypes.IMAGE) {
                flush()
                out += image(node)
            } else {
                run += node
            }
        }
        flush()
        return out
    }

    private fun image(node: ASTNode): MdBlock {
        val target = node.children.firstOrNull { it.type in linkTypes }?.let(::linkTarget)
        val url = target?.url ?: return MdBlock.Paragraph(AnnotatedString(node.text(src)))
        return MdBlock.Image(url, inline(target.text).text)
    }

    private fun fence(node: ASTNode): MdBlock.Code {
        val language = node.child(MarkdownTokenTypes.FENCE_LANG)?.text(src)?.trim()?.substringBefore(' ')?.ifEmpty { null }
        val body = node.children.dropWhile { it.type != MarkdownTokenTypes.EOL }.drop(1)
        return MdBlock.Code(language, codeLines(body, MarkdownTokenTypes.CODE_FENCE_CONTENT) { it })
    }

    /** Container indentation and quote markers arrive as separate tokens, so only [content] tokens are kept. */
    private fun codeLines(nodes: List<ASTNode>, content: IElementType, strip: (String) -> String): String {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for (node in nodes) {
            when (node.type) {
                content -> line.append(strip(node.text(src)))
                MarkdownTokenTypes.EOL -> {
                    lines += line.toString()
                    line.clear()
                }
                MarkdownTokenTypes.CODE_FENCE_END -> break
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.joinToString("\n").trimEnd('\n')
    }

    private fun stripIndent(line: String): String =
        if (line.startsWith('\t')) line.substring(1) else line.substring(line.takeWhile { it == ' ' }.length.coerceAtMost(4))

    private fun list(node: ASTNode): MdBlock.ListBlock {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val start = items.firstOrNull()?.child(MarkdownTokenTypes.LIST_NUMBER)
            ?.let { it.text(src).trim().takeWhile(Char::isDigit).toIntOrNull() } ?: 1
        return MdBlock.ListBlock(node.type == MarkdownElementTypes.ORDERED_LIST, start, items.map(::listItem))
    }

    private fun listItem(item: ASTNode): List<MdBlock> {
        val blocks = blocks(item.children)
        val box = item.child(GFMTokenTypes.CHECK_BOX) ?: return blocks
        val mark = AnnotatedString(if (box.text(src).contains('x', ignoreCase = true)) "☑ " else "☐ ")
        val first = blocks.firstOrNull()
        return if (first is MdBlock.Paragraph) {
            listOf(MdBlock.Paragraph(mark + first.inline)) + blocks.drop(1)
        } else {
            listOf(MdBlock.Paragraph(mark)) + blocks
        }
    }

    private fun table(node: ASTNode): MdBlock.Table {
        val header = node.child(GFMElementTypes.HEADER)?.let(::cells).orEmpty()
        val width = header.size
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val cells = cells(row)
            cells.take(width) + List((width - cells.size).coerceAtLeast(0)) { AnnotatedString("") }
        }
        val delimiter = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR && '-' in it.text(src) }
        val aligns = delimiter?.text(src)?.trim()?.removePrefix("|")?.removeSuffix("|")?.split('|')?.map(::align).orEmpty()
        return MdBlock.Table(header, rows, List(width) { aligns.getOrElse(it) { ColumnAlign.Start } })
    }

    private fun cells(row: ASTNode): List<AnnotatedString> =
        row.children.filter { it.type == GFMTokenTypes.CELL }.map { inline(it.children) }

    private fun align(spec: String): ColumnAlign {
        val s = spec.trim()
        return when {
            s.startsWith(':') && s.endsWith(':') -> ColumnAlign.Center
            s.endsWith(':') -> ColumnAlign.End
            else -> ColumnAlign.Start
        }
    }

    private fun linkTarget(node: ASTNode): LinkTarget {
        val label = node.child(MarkdownElementTypes.LINK_LABEL)
        val text = (node.child(MarkdownElementTypes.LINK_TEXT) ?: label)?.children?.insideBrackets().orEmpty()
        val url = if (node.type == MarkdownElementTypes.INLINE_LINK) {
            node.child(MarkdownElementTypes.LINK_DESTINATION)?.let { destination(it.text(src)) }
        } else {
            label?.let { links[labelKey(it.text(src))] }
        }
        return LinkTarget(text, url)
    }

    fun inline(nodes: List<ASTNode>): AnnotatedString = InlineWriter().apply { write(nodes.trimmed()) }.result()

    private inner class InlineWriter {
        private val out = AnnotatedString.Builder()

        /** True right after a line break, where container indentation and quote markers are skipped. */
        private var lineStart = false

        fun result(): AnnotatedString = out.toAnnotatedString()

        fun write(nodes: List<ASTNode>) {
            nodes.forEachIndexed { i, node ->
                val previous = nodes.getOrNull(i - 1)?.type
                val next = nodes.getOrNull(i + 1)?.type
                val skip = when (node.type) {
                    MarkdownTokenTypes.LT -> next == MarkdownTokenTypes.EMAIL_AUTOLINK
                    MarkdownTokenTypes.GT -> previous == MarkdownTokenTypes.EMAIL_AUTOLINK
                    MarkdownTokenTypes.WHITE_SPACE -> next == MarkdownTokenTypes.EOL
                    else -> false
                }
                if (!skip) write(node)
            }
        }

        private fun write(node: ASTNode) {
            when (node.type) {
                MarkdownTokenTypes.EOL -> {
                    if (!lineStart) out.append(' ')
                    lineStart = true
                    return
                }
                MarkdownTokenTypes.HARD_LINE_BREAK -> {
                    out.append('\n')
                    lineStart = true
                    return
                }
                MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.BLOCK_QUOTE -> {
                    if (!lineStart) out.append(node.text(src))
                    return
                }
                MarkdownElementTypes.STRONG -> out.withStyle(strongStyle) { write(node.children.drop(2).dropLast(2)) }
                MarkdownElementTypes.EMPH -> out.withStyle(emphasisStyle) { write(node.children.drop(1).dropLast(1)) }
                GFMElementTypes.STRIKETHROUGH -> out.withStyle(strikeStyle) {
                    write(node.children.dropWhile { it.type == GFMTokenTypes.TILDE }.dropLastWhile { it.type == GFMTokenTypes.TILDE })
                }
                MarkdownElementTypes.CODE_SPAN -> {
                    out.pushStringAnnotation(CODE_SPAN_TAG, "")
                    out.withStyle(codeStyle) { append(codeSpan(node)) }
                    out.pop()
                }
                in linkTypes -> {
                    val target = linkTarget(node)
                    val url = target.url
                    if (url == null) out.append(unescape(node.text(src))) else link(url) { write(target.text) }
                }
                MarkdownElementTypes.IMAGE -> node.children.firstOrNull { it.type in linkTypes }
                    ?.let { write(linkTarget(it).text) }
                MarkdownElementTypes.AUTOLINK -> {
                    val url = node.children.insideAngles().joinToString("") { it.text(src) }
                    link(url) { out.append(url) }
                }
                GFMTokenTypes.GFM_AUTOLINK -> {
                    val text = node.text(src)
                    link(if (text.startsWith("www.")) "http://$text" else text) { out.append(text) }
                }
                MarkdownTokenTypes.EMAIL_AUTOLINK -> {
                    val text = node.text(src)
                    link("mailto:$text") { out.append(text) }
                }
                GFMElementTypes.INLINE_MATH, MarkdownTokenTypes.HTML_TAG -> out.append(node.text(src))
                else -> if (node.children.isEmpty()) out.append(unescape(node.text(src))) else write(node.children)
            }
            lineStart = false
        }

        private inline fun link(url: String, body: () -> Unit) {
            out.withLink(LinkAnnotation.Url(url, linkStyles)) { body() }
        }

        private fun List<ASTNode>.insideAngles(): List<ASTNode> =
            filterNot { it.type == MarkdownTokenTypes.LT || it.type == MarkdownTokenTypes.GT }

        /** CommonMark: line endings become spaces, and one space is stripped from each side when both are present. */
        private fun codeSpan(node: ASTNode): String {
            val ticks = node.children
            if (ticks.size < 2) return node.text(src)
            val inner = src.substring(ticks.first().endOffset, ticks.last().startOffset).replace('\n', ' ')
            return if (inner.length >= 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.isNotBlank()) {
                inner.substring(1, inner.length - 1)
            } else {
                inner
            }
        }
    }
}
