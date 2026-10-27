package com.agentifiers.amp.design.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.GalleryLayout
import kotlinx.coroutines.launch

/** The web's grid column floor, `minmax(min(100%, 14rem), 1fr)`. */
private val GridColumnMin = 224.dp

/** Below the web's `@container (width <= 32rem)` a side-by-side row stacks. */
private val SideBySideMin = 512.dp

/** How much of the next image a carousel shows, so a swipe reads as possible. */
private val CarouselPeek = 32.dp

private const val FRAME_ASPECT = 4f / 3f

/**
 * Amp's web gallery (chunk `99d00ac2.js`, `data-transcript-gallery-*`): each image under its alt text as a caption.
 * A carousel shows one image with the next peeking and a Previous, `1 / N`, Next bar; a grid fills 224dp columns; a
 * side-by-side row puts up to four images in one row and stacks them below 512dp.
 */
@Composable
internal fun Gallery(gallery: MdBlock.Gallery, image: MarkdownImage) {
    val modifier = Modifier.fillMaxWidth().testTag(ThreadTags.gallery)
    when (gallery.layout) {
        GalleryLayout.Carousel -> Carousel(gallery.images, image, modifier)
        GalleryLayout.Grid -> BoxWithConstraints(modifier) {
            val gap = AmpTheme.spacing.md
            Rows(gallery.images.chunked(maxOf(1, ((maxWidth + gap) / (GridColumnMin + gap)).toInt())), image, gap)
        }
        GalleryLayout.SideBySide -> BoxWithConstraints(modifier) {
            val rows = if (maxWidth > SideBySideMin) gallery.images.chunked(sideBySideColumns(gallery.images.size)) else gallery.images.map(::listOf)
            Rows(rows, image, AmpTheme.spacing.md)
        }
    }
}

/** The web's `Vr`: rows of 4 when the count divides by 4, else 2, else 3, else 4. */
internal fun sideBySideColumns(count: Int): Int = listOf(4, 2, 3).firstOrNull { count % it == 0 } ?: 4

@Composable
private fun Rows(rows: List<List<MdBlock.Image>>, image: MarkdownImage, gap: Dp) {
    val columns = rows.first().size
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        var index = 0
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (item in row) Item(item, index++, image, Modifier.weight(1f))
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Carousel(images: List<MdBlock.Image>, image: MarkdownImage, modifier: Modifier) {
    val pager = rememberPagerState { images.size }
    val scope = rememberCoroutineScope()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            pager,
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = if (images.size > 1) CarouselPeek else 0.dp),
            pageSpacing = AmpTheme.spacing.sm,
            verticalAlignment = Alignment.Top,
        ) { page -> Item(images[page], page, image, Modifier) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmpIconButton(
                AmpIcons.ChevronLeft, "Previous image", { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                ThreadTags.galleryPrevious, enabled = pager.currentPage > 0,
            )
            Text(
                "${pager.currentPage + 1} / ${images.size}",
                style = AmpTheme.type.meta,
                color = AmpTheme.colors.text.inkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = AmpTheme.metrics.touch).testTag(ThreadTags.galleryPosition),
            )
            AmpIconButton(
                AmpIcons.ChevronRight, "Next image", { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                ThreadTags.galleryNext, enabled = pager.currentPage < images.size - 1,
            )
        }
    }
}

/** The caption above the image, as the web's `<figure>` stacks its `<figcaption>` first; an empty alt has none. */
@Composable
private fun Item(item: MdBlock.Image, index: Int, image: MarkdownImage, modifier: Modifier) {
    Column(modifier.testTag(ThreadTags.galleryItem(index)), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sectionBottom)) {
        if (item.alt.isNotBlank()) Text(item.alt, style = AmpTheme.type.label, color = AmpTheme.colors.text.inkMuted)
        Box(
            Modifier.fillMaxWidth().aspectRatio(FRAME_ASPECT).clip(AmpTheme.shapes.control).background(AmpTheme.colors.surfaces.raised),
            contentAlignment = Alignment.Center,
        ) { image(item.url, item.alt, Modifier.fillMaxSize()) }
    }
}
