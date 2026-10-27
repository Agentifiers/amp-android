package com.agentifiers.amp.model

import com.agentifiers.amp.model.MarkdownPart.Directive
import com.agentifiers.amp.model.MarkdownPart.Prose
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownDirectivesTest {
    /** The user's message: prose, a carousel of eight attachment images one per line with alt text and no blank lines, prose. */
    private val images = (1..8).joinToString("\n") { "![Screen $it of the app](https://ampcode.com/user-content/attachments/$it-shot.png)" }
    private val real = "The app now has three screens.\n\n:::gallery carousel\n$images\n:::\n\nThe sidebar opens on the left of each one."

    @Test
    fun `the user's carousel is one closed gallery directive between two paragraphs`() {
        assertEquals(
            listOf(Prose("The app now has three screens."), Directive("gallery", "carousel", true, listOf(Prose(images))), Prose("The sidebar opens on the left of each one.")),
            MarkdownPart.parse(real),
        )
        assertEquals(GalleryLayout.Carousel, GalleryLayout.of(MarkdownPart.parse(real)[1] as Directive))
    }

    @Test
    fun `each layout word and alias picks its layout, and anything else is a grid`() {
        val cases = listOf(
            ":::gallery carousel", ":::gallery grid", ":::gallery side-by-side", ":::gallery", ":::gallery Carousel", ":::gallery masonry",
            ":::gallery {layout=carousel}", ":::carousel", ":::grid", ":::side-by-side", "   :::  gallery   carousel  ",
        )
        assertEquals(
            listOf(
                GalleryLayout.Carousel, GalleryLayout.Grid, GalleryLayout.SideBySide, GalleryLayout.Grid, GalleryLayout.Carousel, GalleryLayout.Grid,
                GalleryLayout.Grid, GalleryLayout.Carousel, GalleryLayout.Grid, GalleryLayout.SideBySide, GalleryLayout.Carousel,
            ),
            cases.map { GalleryLayout.of(MarkdownPart.parse("$it\n![a](u)\n:::").single() as Directive) },
        )
    }

    @Test
    fun `an unknown directive keeps its name and attributes and is not a gallery`() {
        val parts = MarkdownPart.parse(":::note{.warning}\n**Heads up:** rebuilt.\n:::")
        assertEquals(listOf(Directive("note", "{.warning}", true, listOf(Prose("**Heads up:** rebuilt.")))), parts)
        assertEquals(listOf(null, null), listOf(parts.single() as Directive, Directive("carousel", "now", true, emptyList())).map(GalleryLayout::of))
    }

    @Test
    fun `nested markdown stays inside and directives nest`() {
        assertEquals(
            listOf(Directive("note", "", true, listOf(Prose("- one\n- two"), Directive("gallery", "grid", true, listOf(Prose("![a](u)"))), Prose("> quoted")))),
            MarkdownPart.parse(":::note\n- one\n- two\n:::gallery grid\n![a](u)\n:::\n> quoted\n:::"),
        )
    }

    @Test
    fun `an unclosed fence mid-stream is an open directive, and a fence still arriving is dropped`() {
        assertEquals(
            listOf(Prose("Here is the sidebar."), Directive("gallery", "carousel", false, listOf(Prose("![a](u)")))),
            MarkdownPart.parse("Here is the sidebar.\n\n:::gallery carousel\n![a](u)\n", streaming = true),
        )
        assertEquals(
            listOf(Prose("Here is the sidebar."), Directive("gallery", "carousel", false, listOf(Prose("![a](u)")))),
            MarkdownPart.parse("Here is the sidebar.\n\n:::gallery carousel\n![a](u)\n:::", streaming = true),
        )
        assertEquals(listOf(Prose("Here is the sidebar.\n")), MarkdownPart.parse("Here is the sidebar.\n\n::", streaming = true))
        assertEquals(listOf(Prose("Here is the sidebar."), Directive("gal", "", false, emptyList())), MarkdownPart.parse("Here is the sidebar.\n:::gal", streaming = true))
        assertEquals(listOf(Prose("Ratio:\n::")), MarkdownPart.parse("Ratio:\n::"))
    }

    @Test
    fun `a mid-line triple colon, a fence in a code block, and an indented code line stay text`() {
        val cases = listOf("The ratio is 1:::2 at most.", "Use `:::gallery` to group images.", "```md\n:::gallery carousel\n![a](u)\n:::\n```", "    :::gallery\n    :::")
        assertEquals(cases.map { listOf(Prose(it)) }, cases.map { MarkdownPart.parse(it) })
        assertEquals(
            listOf(Directive("note", "", true, listOf(Prose("~~~\n:::\n~~~")))),
            MarkdownPart.parse(":::note\n~~~\n:::\n~~~\n:::"),
        )
    }

    @Test
    fun `a closing fence with nothing open is dropped`() {
        assertEquals(listOf(Prose("Done."), Prose("Next.")), MarkdownPart.parse("Done.\n:::\nNext."))
    }

    @Test
    fun `without fences each directive's markdown stays in its place`() {
        assertEquals("The app now has three screens.\n\n$images\n\nThe sidebar opens on the left of each one.", MarkdownPart.withoutFences(real))
        assertEquals("Here is the sidebar.\n\n![a](u)", MarkdownPart.withoutFences("Here is the sidebar.\n\n:::gallery carousel\n![a](u)\n::", streaming = true))
        assertEquals("No directives here.\n", MarkdownPart.withoutFences("No directives here.\n"))
    }
}
