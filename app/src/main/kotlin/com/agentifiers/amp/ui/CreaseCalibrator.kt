package com.agentifiers.amp.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agentifiers.amp.layout.CreaseCalibration
import com.agentifiers.amp.layout.DisplayRotation
import com.agentifiers.amp.layout.flipForRotation
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Debug builds only. Holds the creases the user dragged onto the physical hinges, persists them across launches in
 * natural-orientation dp, and logs each placement under `AmpCrease` so the calibration can be read back with logcat.
 */
internal class CreaseCalibrator(context: Context, private val deviceModel: String) {
    private val prefs = context.getSharedPreferences("amp_debug_creases", Context.MODE_PRIVATE)

    var calibration: CreaseCalibration? by mutableStateOf(load())
        private set

    /** Moves crease [index] of [current], in window dp, to [dp], snapped to a whole physical pixel. */
    fun place(index: Int, dp: Float, current: List<Float>, widthDp: Float, heightDp: Float, density: Float, rotation: DisplayRotation) {
        val px = (dp.coerceIn(0f, widthDp) * density).roundToInt()
        val snapped = px / density
        val natural = flipForRotation(current.toMutableList().also { it[index] = snapped }, widthDp, rotation)
        calibration = CreaseCalibration(widthDp, natural)
        prefs.edit().putFloat(KEY_WIDTH, widthDp).putString(KEY_CREASES, natural.joinToString(",")).apply()
        Log.i(
            TAG,
            "calibration crease=$index dp=${formatDp(snapped)} px=$px rotation=$rotation natural=${natural.joinToString(",", transform = ::formatDp)} " +
                "window=${widthDp.roundToInt()}x${heightDp.roundToInt()}dp model=$deviceModel",
        )
    }

    fun reset() {
        calibration = null
        prefs.edit().clear().apply()
        Log.i(TAG, "calibration reset model=$deviceModel")
    }

    private fun load(): CreaseCalibration? {
        val creases = prefs.getString(KEY_CREASES, null)?.split(",")?.mapNotNull(String::toFloatOrNull) ?: return null
        return CreaseCalibration(prefs.getFloat(KEY_WIDTH, 0f), creases)
    }

    companion object {
        private const val TAG = "AmpCrease"
        private const val KEY_WIDTH = "windowWidthDp"
        private const val KEY_CREASES = "creasesDp"

        fun formatDp(dp: Float): String = String.format(Locale.US, "%.1f", (dp * 2).roundToInt() / 2f)
    }
}
