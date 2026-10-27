package com.agentifiers.amp.design.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Test
import com.agentifiers.amp.model.GalleryLayout

class MarkdownParserTest {
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)
    private val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.93.em)
    private val colors = MdStyles(code = code, link = Color(0xFF6CA0E0))

    private fun paragraph(text: String) = MdBlock.Paragraph(AnnotatedString(text))

    @Test
    fun plainTextOfTheS04ReplyDropsMarkup() {
        assertEquals(
            "Done. Renamed add to add_numbers in calc.py and ran it.",
            markdownPlainText("Done. Renamed `add` to `add_numbers` in [calc.py](/private/tmp/amp-scratch/calc.py) and ran it."),
        )
    }

    @Test
    fun plainTextPutsEachBlockOnItsOwnLineAndTabsBetweenCells() {
        assertEquals(
            "T\na\nb\nx\ty\n1\t2",
            markdownPlainText("# T\n\n- a\n- b\n\n| x | y |\n|---|---|\n| 1 | 2 |"),
        )
    }

    @Test
    fun parsesHeadingTableNestedListFenceAndInlineStyles() {
        val md = """
            # Title

            | A | B |
            |:--|--:|
            | 1 | **2** |

            - a
              - b

            ```kotlin
            val x = 1
            ```

            **bold** and `code`
        """.trimIndent()

        assertEquals(
            listOf(
                MdBlock.Heading(1, AnnotatedString("Title")),
                MdBlock.Table(
                    header = listOf(AnnotatedString("A"), AnnotatedString("B")),
                    rows = listOf(listOf(AnnotatedString("1"), buildAnnotatedString { withStyle(bold) { append("2") } })),
                    aligns = listOf(ColumnAlign.Start, ColumnAlign.End),
                ),
                MdBlock.ListBlock(
                    ordered = false,
                    start = 1,
                    items = listOf(listOf(paragraph("a"), MdBlock.ListBlock(false, 1, listOf(listOf(paragraph("b")))))),
                ),
                MdBlock.Code("kotlin", "val x = 1"),
                MdBlock.Paragraph(
                    buildAnnotatedString {
                        withStyle(bold) { append("bold") }
                        append(" and ")
                        withAnnotation(CODE_SPAN_TAG, "") { withStyle(code) { append("code") } }
                    },
                ),
            ),
            parseMarkdown(md, colors),
        )
    }

    @Test
    fun linkCarriesItsDestinationOverTheLinkText() {
        val inline = (parseMarkdown("see [calc.py](/tmp/calc.py) now", colors).single() as MdBlock.Paragraph).inline

        assertEquals("see calc.py now", inline.text)
        assertEquals(
            listOf(Triple("/tmp/calc.py", 4, 11)),
            inline.getLinkAnnotations(0, inline.length).map { Triple((it.item as LinkAnnotation.Url).url, it.start, it.end) },
        )
    }

    @Test
    fun quoteJoinsContinuationLinesAndUnescapesPunctuation() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(paragraph("one two *x* & y")))),
            parseMarkdown("> one\n> two \\*x\\* &amp; y", colors),
        )
    }

    @Test
    fun imageSplitsItsParagraphAndUnresolvedReferenceStaysLiteral() {
        assertEquals(
            listOf(paragraph("See"), MdBlock.Image("https://h/i.png", "shot"), paragraph("and [y].")),
            parseMarkdown("See ![shot](https://h/i.png) and [y].", colors),
        )
    }

    @Test
    fun tableDirectlyUnderAParagraphLineIsStillATable() {
        assertEquals(
            listOf(
                paragraph("Results:"),
                MdBlock.Table(listOf(AnnotatedString("a")), listOf(listOf(AnnotatedString("1"))), listOf(ColumnAlign.Start)),
            ),
            parseMarkdown("Results:\n| a |\n|---|\n| 1 |", colors),
        )
    }

    @Test
    fun streamingFenceWithoutCloserIsCode() {
        assertEquals(listOf(MdBlock.Code("py", "x = 1\n  y = 2")), parseMarkdown("```py\nx = 1\n  y = 2\n", colors))
    }

    @Test
    fun orderedListKeepsItsStartAndChecklistShowsBoxes() {
        assertEquals(
            listOf(
                MdBlock.ListBlock(true, 3, listOf(listOf(paragraph("c")), listOf(paragraph("d")))),
                MdBlock.ListBlock(false, 1, listOf(listOf(paragraph("☐ todo")), listOf(paragraph("☑ done")))),
            ),
            parseMarkdown("3. c\n4. d\n\n- [ ] todo\n- [x] done", colors),
        )
    }

    private val shots = (1..8).map { MdBlock.Image("https://ampcode.com/user-content/attachments/$it-shot.png", "Screen $it of the app") }
    private val shotLines = shots.joinToString("\n") { "![${it.alt}](${it.url})" }

    @Test
    fun theUsersCarouselIsOneGalleryOfItsEightImagesBetweenTwoParagraphs() {
        assertEquals(
            listOf(paragraph("The app now has three screens."), MdBlock.Gallery(GalleryLayout.Carousel, shots), paragraph("The sidebar opens on the left of each one.")),
            parseMarkdown("The app now has three screens.\n\n:::gallery carousel\n$shotLines\n:::\n\nThe sidebar opens on the left of each one.", colors),
        )
    }

    @Test
    fun eachLayoutIsAGalleryAndBlankLinesBetweenImagesAreAllowed() {
        val body = "![a](https://h/a.png)\n\n![](https://h/b.png)"
        val images = listOf(MdBlock.Image("https://h/a.png", "a"), MdBlock.Image("https://h/b.png", ""))
        assertEquals(
            listOf(GalleryLayout.Grid, GalleryLayout.Grid, GalleryLayout.SideBySide, GalleryLayout.Carousel, GalleryLayout.Grid).map { MdBlock.Gallery(it, images) },
            listOf(":::gallery", ":::gallery grid", ":::side-by-side", ":::carousel", ":::gallery masonry").map { parseMarkdown("$it\n$body\n:::", colors).single() },
        )
    }

    @Test
    fun anUnknownDirectiveAndAGalleryWithProseShowTheirMarkdownWithoutFences() {
        assertEquals(
            listOf(MdBlock.Paragraph(buildAnnotatedString { withStyle(bold) { append("Heads up:") }; append(" rebuilt.") }), MdBlock.ListBlock(false, 1, listOf(listOf(paragraph("one"))))),
            parseMarkdown(":::note\n**Heads up:** rebuilt.\n\n- one\n:::", colors),
        )
        assertEquals(
            listOf(MdBlock.Image("https://h/a.png", "a"), paragraph("Light theme")),
            parseMarkdown(":::gallery grid\n![a](https://h/a.png)\n\nLight theme\n:::", colors),
        )
    }

    @Test
    fun anUnclosedGalleryMidStreamShowsItsImagesWithoutTheFenceAndTheCursorAfter() {
        assertEquals(
            listOf(paragraph("Here is the sidebar."), MdBlock.Image("https://h/a.png", "a"), paragraph("▍")),
            parseMarkdown("Here is the sidebar.\n\n:::gallery carousel\n![a](https://h/a.png)\n:::", colors, streaming = true),
        )
        assertEquals(listOf(paragraph("Deploying ▍")), parseMarkdown("Deploying", colors, streaming = true))
        assertEquals(listOf(MdBlock.Code("py", "x = 1 ▍")), parseMarkdown("```py\nx = 1\n", colors, streaming = true))
    }

    @Test
    fun aMidLineTripleColonStaysText() {
        assertEquals(listOf(paragraph("The ratio is 1:::2 at most.")), parseMarkdown("The ratio is 1:::2 at most.", colors))
    }

    @Test
    fun plainTextOfAGalleryIsItsCaptionsWithoutFences() {
        assertEquals(
            "Here.\nLight theme\nDark theme\nDone.",
            markdownPlainText("Here.\n\n:::gallery grid\n![Light theme](https://h/a.png)\n![Dark theme](https://h/b.png)\n:::\n\nDone."),
        )
    }

    @Test
    fun sideBySideRowsFollowTheWebsColumnChoice() {
        assertEquals(listOf(4, 2, 3, 4, 4, 2, 4, 4), (1..8).map(::sideBySideColumns))
    }
}
