package com.agentifiers.amp.design.icons

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Test

class AmpIconsTest {
    @Test
    fun theRegistryHoldsTheGeneratedIconsInNameOrder() {
        val names = AmpIcons.all.map { it.first }
        assertEquals(68, names.size)
        assertEquals(listOf("activity", "app-window", "archive"), names.take(3))
        assertEquals(names.sorted(), names)
    }

    @Test
    fun everyIconIsRoundStrokesOfWidthTwoOnTheTwentyFourGrid() {
        val shapes = AmpIcons.all.flatMap { (name, icon) ->
            icon.root.map { node ->
                val path = node as VectorPath
                listOf(name, icon.viewportWidth, icon.viewportHeight, path.strokeLineWidth, path.strokeLineCap, path.strokeLineJoin)
            }
        }
        assertEquals(emptyList<List<Any>>(), shapes.filter { it.drop(1) != listOf(24f, 24f, 2f, StrokeCap.Round, StrokeJoin.Round) })
    }

    @Test
    fun xIsTwoCrossingStrokes() {
        val x = AmpIcons.X
        assertEquals("X", x.name)
        assertEquals(2, x.root.size)
        assertEquals(listOf(null, null), x.root.map { (it as VectorPath).fill })
    }

    @Test
    fun tagDrawsItsHoleAsAFilledDot() {
        val fills = AmpIcons.Tag.root.map { (it as VectorPath).fill != null }
        assertEquals(listOf(false, true), fills)
    }
}
