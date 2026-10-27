package com.agentifiers.amp.waymote

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A remote display size in pixels and its device scale in 120ths (120 = 1x). */
public data class RemoteSize(val width: Int, val height: Int, val scale120: Int) {
    internal companion object {
        private const val MIN_WIDTH = 320
        private const val MIN_HEIGHT = 180
        private const val MAX_SIDE = 6000
        private const val MAX_PIXELS = 8_294_400.0

        /**
         * The size the web client requests for a viewport (e88f9517.js `i()` then `on()`): scaled down to fit the
         * side and pixel limits, floored to multiples of 16, clamped to 320x180..6000x6000, scale clamped to 1x..4x.
         */
        fun fit(widthPx: Int, heightPx: Int, density: Float): RemoteSize? {
            if (widthPx <= 0 || heightPx <= 0) return null
            val w = widthPx.toDouble()
            val h = heightPx.toDouble()
            val downscale = minOf(1.0, MAX_SIDE / w, MAX_SIDE / h, sqrt(MAX_PIXELS / (w * h)))
            return RemoteSize(
                width = (floor(w * downscale / 16) * 16).toInt().coerceIn(MIN_WIDTH, MAX_SIDE),
                height = (floor(h * downscale / 16) * 16).toInt().coerceIn(MIN_HEIGHT, MAX_SIDE),
                scale120 = (density * downscale * 120).roundToInt().coerceIn(120, 480),
            )
        }
    }
}
