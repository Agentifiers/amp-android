package com.agentifiers.amp.layout

import kotlin.math.abs

/** Override is the debug `geometry-override` app event. */
enum class CreaseSource { Calibrated, DeviceTable, Reported, Override, None }

/**
 * Which way up the display is, measured from its natural orientation (`Surface.ROTATION_*`). The TriFold inner
 * display is naturally landscape, 2160x1584 px.
 */
enum class DisplayRotation { Natural, Quarter, Half, ThreeQuarter }

/**
 * Creases the user placed on the physical hinges for one window width, in natural-orientation dp. Ranks above the
 * device table.
 */
data class CreaseCalibration(val windowWidthDp: Float, val creasesDp: List<Float>)

/**
 * The creases layout uses, and where they came from. [reportedDp] keeps what WindowManager reported even when the
 * device table wins, so the debug overlay can show both.
 */
data class Creases(val positionsDp: List<Float>, val source: CreaseSource, val reportedDp: List<Float>) {
    companion object {
        val None = Creases(emptyList(), CreaseSource.None, emptyList())
    }
}

/** M3 window width classes: Compact under 600dp, Medium to 839, Expanded to 1199, Large to 1599, ExtraLarge above. */
enum class WidthClass {
    Compact, Medium, Expanded, Large, ExtraLarge;

    companion object {
        fun of(widthDp: Float): WidthClass = when {
            widthDp < 600f -> Compact
            widthDp < 840f -> Medium
            widthDp < 1200f -> Expanded
            widthDp < 1600f -> Large
            else -> ExtraLarge
        }
    }
}

data class WindowGeometry(val widthDp: Float, val heightDp: Float, val creases: Creases) {
    val widthClass: WidthClass get() = WidthClass.of(widthDp)
}

/** One device-table row: windows [windowWidthDp] wide on models starting [modelPrefix] have [creasesDp], in natural orientation. */
data class CreaseTableEntry(val modelPrefix: String, val windowWidthDp: Float, val creasesDp: List<Float>)

/**
 * Devices whose WindowManager report does not match their hinges, in natural-orientation dp. The TriFold inner
 * display reports one FoldingFeature at 540dp but has two
 * physical hinges. The user placed them on the device with the debug crease calibration: 335dp (670 px) and 717dp
 * (1434 px), so the panels are 335, 382, and 363dp wide.
 */
private val CREASE_TABLE = listOf(
    CreaseTableEntry(modelPrefix = "SM-F968", windowWidthDp = 1080f, creasesDp = listOf(335f, 717f)),
)

/**
 * Maps crease x positions between natural orientation and the window. Turned upside down, the left hinge becomes the
 * right one; every other rotation leaves vertical positions alone. The mapping is its own inverse.
 */
fun flipForRotation(creasesDp: List<Float>, windowWidthDp: Float, rotation: DisplayRotation): List<Float> =
    if (rotation == DisplayRotation.Half) creasesDp.map { windowWidthDp - it }.sorted() else creasesDp

/** [standIns] are extra device-table rows for emulators that stand in for a device; debug builds pass them. */
fun resolveCreases(
    model: String,
    windowWidthDp: Float,
    rotation: DisplayRotation,
    reportedVerticalFoldsDp: List<Float>,
    calibration: CreaseCalibration? = null,
    standIns: List<CreaseTableEntry> = emptyList(),
): Creases {
    val entry = (CREASE_TABLE + standIns).firstOrNull { model.startsWith(it.modelPrefix) && abs(it.windowWidthDp - windowWidthDp) < 1f }
    return when {
        calibration != null && abs(calibration.windowWidthDp - windowWidthDp) < 1f ->
            Creases(flipForRotation(calibration.creasesDp, windowWidthDp, rotation), CreaseSource.Calibrated, reportedVerticalFoldsDp)
        entry != null -> Creases(flipForRotation(entry.creasesDp, windowWidthDp, rotation), CreaseSource.DeviceTable, reportedVerticalFoldsDp)
        reportedVerticalFoldsDp.isNotEmpty() -> Creases(reportedVerticalFoldsDp, CreaseSource.Reported, reportedVerticalFoldsDp)
        else -> Creases.None
    }
}
