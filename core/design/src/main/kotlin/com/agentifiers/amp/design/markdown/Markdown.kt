package com.agentifiers.amp.design.markdown

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.code.CodeBlock
import com.agentifiers.amp.design.code.rememberHighlighted

/** Draws one markdown image at [modifier]'s size; the caller loads [url] and opens it on a tap. */
typealias MarkdownImage = @Composable (url: String, alt: String, modifier: Modifier) -> Unit

/**
 * Markdown rendered natively: one Text per paragraph, heading, list item, and table cell; code through [CodeBlock];
 * Amp's `:::gallery` through [Gallery]. While [streaming], the text ends with a cursor and an unclosed fence stays hidden.
 */
@Composable
fun Markdown(
    markdown: String,
    modifier: Modifier = Modifier,
    highlight: String? = null,
    streaming: Boolean = false,
    onLink: (url: String) -> Unit,
    image: MarkdownImage = { _, _, _ -> },
) {
    val inline = MdStyles(code = AmpTheme.type.inlineCode, link = AmpTheme.colors.status.link)
    val blocks = remember(markdown, inline, streaming) { parseMarkdown(markdown, inline, streaming) }
    val currentOnLink by rememberUpdatedState(onLink)
    // LinkAnnotation.Url without a listener opens through LocalUriHandler, so every link lands in onLink.
    val uriHandler = remember { object : UriHandler { override fun openUri(uri: String) = currentOnLink(uri) } }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Blocks(blocks, highlight, image, modifier)
    }
}

private val CellMaxWidth = 240.dp
private val CellPadding = 8.dp
private val TableShape = RoundedCornerShape(6.dp)

@Composable
private fun Blocks(
    blocks: List<MdBlock>,
    highlight: String?,
    image: MarkdownImage,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { Block(it, highlight, image) }
    }
}

@Composable
private fun Block(block: MdBlock, highlight: String?, image: MarkdownImage) {
    val body = AmpTheme.type.body
    when (block) {
        is MdBlock.Heading -> MdText(block.inline, highlight, headingStyle(block.level))
        is MdBlock.Paragraph -> MdText(block.inline, highlight, body)
        is MdBlock.Code -> CodeBlock(block.text, block.language, highlight = highlight)
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        if (block.ordered) "${block.start + index}." else "•",
                        style = body,
                        color = AmpTheme.colors.text.inkMuted,
                        modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp),
                    )
                    Blocks(item, highlight, image, Modifier.weight(1f))
                }
            }
        }
        is MdBlock.Table -> TableView(block, highlight, body)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(AmpTheme.colors.surfaces.line))
            CompositionLocalProvider(LocalContentColor provides AmpTheme.colors.text.inkMuted) {
                Blocks(block.blocks, highlight, image, Modifier.padding(start = 10.dp))
            }
        }
        MdBlock.Rule -> HorizontalDivider(color = AmpTheme.colors.surfaces.line)
        is MdBlock.Image -> image(block.url, block.alt, Modifier)
        is MdBlock.Gallery -> Gallery(block, image)
    }
}

private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> AmpTheme.type.heading1
    2 -> AmpTheme.type.heading2
    else -> AmpTheme.type.heading3
}

/** Paragraph text. Each code span gets A2's inline-code fill: rounded, a little wider than its glyphs, as tall as the code. */
@Composable
private fun MdText(
    text: AnnotatedString,
    highlight: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val display = rememberHighlighted(text, highlight)
    val code = remember(display) { display.getStringAnnotations(CODE_SPAN_TAG, 0, display.length) }
    if (code.isEmpty()) {
        Text(display, modifier, style = style, textAlign = textAlign)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fill = AmpTheme.colors.surfaces.raised
    val shape = AmpTheme.shapes.inlineCode
    val padX = AmpTheme.spacing.inlineCodePadX
    val codeSize = AmpTheme.type.inlineCode.fontSize
    Text(
        display,
        modifier.drawBehind {
            val laidOut = layout ?: return@drawBehind
            val height = (style.fontSize * codeSize.value).toPx() * CODE_CONTENT_AREA
            code.forEach { span -> drawCodeFill(laidOut, span.start, span.end, height, padX.toPx(), shape, fill) }
        },
        style = style,
        textAlign = textAlign,
        onTextLayout = { layout = it },
    )
}

/** A font's ascent plus descent as a share of its size, about 1.17 for Roboto and Droid Sans Mono: the height CSS fills. */
private const val CODE_CONTENT_AREA = 1.17f

/** One [shape] fill per line the span [start] to [end] covers, centered on the line and [padX] wider on each side. */
private fun DrawScope.drawCodeFill(layout: TextLayoutResult, start: Int, end: Int, height: Float, padX: Float, shape: Shape, color: Color) {
    if (start >= end) return
    for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
        val from = maxOf(start, layout.getLineStart(line))
        val to = minOf(end, layout.getLineEnd(line, visibleEnd = true))
        if (from >= to) continue
        val left = minOf(layout.getBoundingBox(from).left, layout.getBoundingBox(to - 1).left) - padX
        val right = maxOf(layout.getBoundingBox(from).right, layout.getBoundingBox(to - 1).right) + padX
        val top = (layout.getLineTop(line) + layout.getLineBottom(line) - height) / 2
        translate(left, top) { drawOutline(shape.createOutline(Size(right - left, height), layoutDirection, this), color) }
    }
}

@Composable
private fun TableView(table: MdBlock.Table, highlight: String?, body: TextStyle) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val headerStyle = body.copy(fontWeight = FontWeight.SemiBold)
    val widths = remember(table, body, density) {
        val cap = Constraints(maxWidth = with(density) { CellMaxWidth.roundToPx() })
        table.header.indices.map { column ->
            val widest = maxOf(
                measurer.measure(table.header[column], headerStyle, constraints = cap).size.width,
                table.rows.maxOfOrNull { measurer.measure(it[column], body, constraints = cap).size.width } ?: 0,
            )
            with(density) { widest.toDp() } + CellPadding * 2
        }
    }
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .width(widths.fold(0.dp) { sum, w -> sum + w })
            .clip(TableShape)
            .border(1.dp, AmpTheme.colors.surfaces.line, TableShape),
    ) {
        TableRow(table.header, widths, table.aligns, highlight, headerStyle, Modifier.background(AmpTheme.colors.surfaces.raised))
        table.rows.forEach { row ->
            HorizontalDivider(color = AmpTheme.colors.surfaces.line)
            TableRow(row, widths, table.aligns, highlight, body)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    widths: List<Dp>,
    aligns: List<ColumnAlign>,
    highlight: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        cells.forEachIndexed { column, cell ->
            MdText(
                cell,
                highlight,
                style,
                Modifier.width(widths[column]).padding(horizontal = CellPadding, vertical = 6.dp),
                aligns[column].textAlign,
            )
        }
    }
}
