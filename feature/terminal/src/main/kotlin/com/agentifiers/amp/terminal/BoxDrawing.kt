package com.agentifiers.amp.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * U+2500 to U+257F as four arm weights, up, right, down, left: 0 none, 1 light, 2 heavy, 3 double. Dashed lines
 * draw solid; arcs and diagonals are drawn by [drawBoxGlyph] itself and read "0000" here.
 */
private const val ARMS =
    "0101020210102020010102021010202001010202101020200110021001200220001100120021002211001200210022001001100220012002" +
        "1110121021101120212022101220222010111012201110212021201210222022011101120211021201210122022102221101110212011202" +
        "2101210222012202111111121211121221111121212121122211112212212212122221222221222201010202101020200303303003100130" +
        "0330001300310033130031003300100330013003131031303330101330313033031301310333130331013303131331313333011000111001" +
        "1100000000000000000110000100001000022000020000200201102001022010"

private fun arm(cp: Int, side: Int): Int = ARMS[(cp - 0x2500) * 4 + side] - '0'

internal fun isBoxGlyph(cp: Int): Boolean = cp in 0x2500..0x259F

/** Draws [cp] (U+2500 to U+259F) as shapes filling the cell, so lines join across rows and columns. */
internal fun drawBoxGlyph(canvas: Canvas, cp: Int, left: Float, top: Float, width: Float, height: Float, paint: Paint) {
    paint.style = Paint.Style.FILL
    if (cp >= 0x2580) return drawBlock(canvas, cp, left, top, width, height, paint)
    val light = max(1f, (width / 8f).roundToInt().toFloat())
    val cx = left + width / 2f
    val cy = top + height / 2f
    when (cp) {
        in 0x256D..0x2570 -> return drawArc(canvas, cp, left, top, width, height, cx, cy, light, paint)
        in 0x2571..0x2573 -> return drawDiagonals(canvas, cp, left, top, width, height, light, paint)
    }
    fun thickness(weight: Int) = light * if (weight == 0) 1 else weight
    val up = arm(cp, 0)
    val right = arm(cp, 1)
    val down = arm(cp, 2)
    val leftArm = arm(cp, 3)
    val vertical = thickness(max(up, down)) / 2f
    val horizontal = thickness(max(right, leftArm)) / 2f
    fun verticalArm(weight: Int, y0: Float, y1: Float) {
        val half = thickness(weight) / 2f
        if (weight == 3) {
            canvas.drawRect(cx - half, y0, cx - half + light, y1, paint)
            canvas.drawRect(cx + half - light, y0, cx + half, y1, paint)
        } else if (weight != 0) {
            canvas.drawRect(cx - half, y0, cx + half, y1, paint)
        }
    }
    fun horizontalArm(weight: Int, x0: Float, x1: Float) {
        val half = thickness(weight) / 2f
        if (weight == 3) {
            canvas.drawRect(x0, cy - half, x1, cy - half + light, paint)
            canvas.drawRect(x0, cy + half - light, x1, cy + half, paint)
        } else if (weight != 0) {
            canvas.drawRect(x0, cy - half, x1, cy + half, paint)
        }
    }
    verticalArm(up, top, cy + horizontal)
    verticalArm(down, cy - horizontal, top + height)
    horizontalArm(right, cx - vertical, left + width)
    horizontalArm(leftArm, left, cx + vertical)
}

/** Drawing happens on the main thread only, so one path serves every arc. */
private val arcPath = Path()

private fun drawArc(canvas: Canvas, cp: Int, left: Float, top: Float, width: Float, height: Float, cx: Float, cy: Float, light: Float, paint: Paint) {
    val endX = if (cp == 0x256D || cp == 0x2570) left + width else left
    val endY = if (cp == 0x256D || cp == 0x256E) top + height else top
    arcPath.rewind()
    arcPath.moveTo(endX, cy)
    arcPath.quadTo(cx, cy, cx, endY)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = light
    canvas.drawPath(arcPath, paint)
    paint.style = Paint.Style.FILL
}

private fun drawDiagonals(canvas: Canvas, cp: Int, left: Float, top: Float, width: Float, height: Float, light: Float, paint: Paint) {
    paint.strokeWidth = light
    if (cp != 0x2572) canvas.drawLine(left + width, top, left, top + height, paint)
    if (cp != 0x2571) canvas.drawLine(left, top, left + width, top + height, paint)
}

private fun drawBlock(canvas: Canvas, cp: Int, left: Float, top: Float, width: Float, height: Float, paint: Paint) {
    val right = left + width
    val bottom = top + height
    fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = canvas.drawRect(x0, y0, x1, y1, paint)
    fun quadrants(ul: Boolean, ur: Boolean, ll: Boolean, lr: Boolean) {
        val mx = left + width / 2f
        val my = top + height / 2f
        if (ul) rect(left, top, mx, my)
        if (ur) rect(mx, top, right, my)
        if (ll) rect(left, my, mx, bottom)
        if (lr) rect(mx, my, right, bottom)
    }
    when (cp) {
        0x2580 -> rect(left, top, right, top + height / 2f)
        in 0x2581..0x2588 -> rect(left, bottom - height * (cp - 0x2580) / 8f, right, bottom)
        in 0x2589..0x258F -> rect(left, top, left + width * (0x2590 - cp) / 8f, bottom)
        0x2590 -> rect(left + width / 2f, top, right, bottom)
        in 0x2591..0x2593 -> {
            val alpha = paint.alpha
            paint.alpha = alpha * (cp - 0x2590) / 4
            rect(left, top, right, bottom)
            paint.alpha = alpha
        }
        0x2594 -> rect(left, top, right, top + height / 8f)
        0x2595 -> rect(right - width / 8f, top, right, bottom)
        0x2596 -> quadrants(ul = false, ur = false, ll = true, lr = false)
        0x2597 -> quadrants(ul = false, ur = false, ll = false, lr = true)
        0x2598 -> quadrants(ul = true, ur = false, ll = false, lr = false)
        0x2599 -> quadrants(ul = true, ur = false, ll = true, lr = true)
        0x259A -> quadrants(ul = true, ur = false, ll = false, lr = true)
        0x259B -> quadrants(ul = true, ur = true, ll = true, lr = false)
        0x259C -> quadrants(ul = true, ur = true, ll = false, lr = true)
        0x259D -> quadrants(ul = false, ur = true, ll = false, lr = false)
        0x259E -> quadrants(ul = false, ur = true, ll = true, lr = false)
        0x259F -> quadrants(ul = false, ur = true, ll = true, lr = true)
    }
}
