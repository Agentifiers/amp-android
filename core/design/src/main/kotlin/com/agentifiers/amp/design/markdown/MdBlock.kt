package com.agentifiers.amp.design.markdown

import androidx.compose.ui.text.AnnotatedString
import com.agentifiers.amp.model.GalleryLayout
import androidx.compose.ui.text.style.TextAlign

/** One rendered block of a markdown document. Inline content is already styled, and links carry `LinkAnnotation.Url`. */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val inline: AnnotatedString) : MdBlock
    data class Paragraph(val inline: AnnotatedString) : MdBlock
    data class Code(val language: String?, val text: String) : MdBlock

    /** [start] is the first item's number; unordered lists keep 1. */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<MdBlock>>) : MdBlock

    /** Every row has exactly `header.size` cells and [aligns] one entry per column. */
    data class Table(
        val header: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
        val aligns: List<ColumnAlign>,
    ) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
    data class Image(val url: String, val alt: String) : MdBlock

    /** A closed `:::gallery` whose body was only images, as Amp's web requires (`Xr` in chunk `99d00ac2.js`). */
    data class Gallery(val layout: GalleryLayout, val images: List<Image>) : MdBlock
}

internal enum class ColumnAlign(val textAlign: TextAlign) {
    Start(TextAlign.Start),
    Center(TextAlign.Center),
    End(TextAlign.End),
}
