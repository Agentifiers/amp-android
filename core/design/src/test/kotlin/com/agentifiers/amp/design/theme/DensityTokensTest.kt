package com.agentifiers.amp.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.agentifiers.amp.design.AmpTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class DensityTokensTest {
    private val type = AmpType()

    private fun spec(style: TextStyle) = "${style.fontSize}/${style.lineHeight} w${style.fontWeight?.weight}" +
        (if (style.fontStyle == FontStyle.Italic) " italic" else "") + (if (style.letterSpacing != 0.sp) " ${style.letterSpacing}" else "")

    @Test
    fun everyRoleHasDirectionA2sSizeLineHeightAndWeight() {
        val roles = mapOf(
            "threadTitle" to type.threadTitle, "paneTitle" to type.paneTitle, "body" to type.body, "rowTitle" to type.rowTitle,
            "secondary" to type.secondary, "label" to type.label, "meta" to type.meta, "section" to type.section,
            "mono" to type.mono, "tab" to type.tab, "button" to type.button, "nav" to type.nav, "thinking" to type.thinking,
            "tool" to type.tool, "toolMeta" to type.toolMeta,
        ).mapValues { spec(it.value) }
        assertEquals(
            mapOf(
                "threadTitle" to "15.0.sp/20.0.sp w600", "paneTitle" to "15.0.sp/20.0.sp w600", "body" to "14.0.sp/20.0.sp w400",
                "rowTitle" to "14.0.sp/20.0.sp w500", "secondary" to "13.0.sp/18.0.sp w400", "label" to "12.0.sp/16.0.sp w500",
                "meta" to "12.0.sp/16.0.sp w400", "section" to "11.0.sp/16.0.sp w600 0.08.em", "mono" to "13.0.sp/18.0.sp w400",
                "tab" to "13.0.sp/18.0.sp w500", "button" to "13.0.sp/18.0.sp w500", "nav" to "12.0.sp/16.0.sp w500",
                "thinking" to "13.0.sp/18.0.sp w400 italic", "tool" to "13.0.sp/18.0.sp w500", "toolMeta" to "12.0.sp/16.0.sp w400",
            ),
            roles,
        )
    }

    @Test
    fun markdownHeadingsStayUnderThePaneTitleAndMonoSmallMatchesToolMeta() {
        assertEquals(
            listOf("16.0.sp/22.0.sp w600", "15.0.sp/20.0.sp w600", "14.0.sp/20.0.sp w600", "12.0.sp/16.0.sp w400"),
            listOf(type.heading1, type.heading2, type.heading3, type.monoSmall).map(::spec),
        )
    }

    @Test
    fun materialDefaultsResolveToA2Roles() {
        val m = type.material
        assertEquals(listOf(14.sp, 14.sp, 14.sp, 13.sp, 13.sp), listOf(m.bodyLarge, m.bodyMedium, m.labelLarge, m.titleSmall, m.bodySmall).map { it.fontSize })
        assertEquals(FontWeight.Medium, m.titleSmall.fontWeight)
        assertEquals(20.sp, m.headlineSmall.fontSize)
    }

    @Test
    fun theSectionRoleTracksAt008Em() {
        assertEquals(0.08.em, AmpTheme.type.section.letterSpacing)
    }

    @Test
    fun componentSizesAreDirectionA2s() {
        val m = AmpTheme.metrics
        assertEquals(
            listOf(40.dp, 48.dp, 21.dp, 52.dp, 32.dp, 36.dp, 40.dp, 32.dp, 48.dp, 32.dp, 18.dp, 24.dp, 48.dp),
            listOf(m.windowBar, m.paneHeader, m.headerBaseline, m.row, m.section, m.search, m.tab, m.button, m.touch, m.iconButton, m.icon, m.chip, m.composer),
        )
    }

    @Test
    fun aFormFieldIs40dpOnTheRaisedSurfaceWith12dpSides() {
        assertEquals(40.dp, AmpTheme.metrics.field)
        assertEquals(listOf(12.dp, 8.dp, 4.dp), listOf(AmpTheme.spacing.fieldPadX, AmpTheme.spacing.fieldPadY, AmpTheme.spacing.fieldLabelGap))
        assertEquals(AmpTheme.colors.surfaces.raised, AmpTheme.surfaceRoles.field)
    }

    @Test
    fun everyPaneSitsOnTheWebSurfaceAndTheWindowBarOnTheCanvas() {
        val roles = AmpTheme.surfaceRoles
        val web = Color(0xFF0C1516)
        assertEquals(listOf(web, web, web), listOf(roles.sidebar, roles.thread, roles.machine))
        assertEquals(Color(0xFF020302), roles.window)
        assertEquals(Color(0xFF1D2626), roles.selected)
    }
}
